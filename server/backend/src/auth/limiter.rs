//! Login attempts per `lrn_or_id`: five in any minute, then 429 until the oldest leaves the
//! window (TECH_SPEC section 14, Q7). Only failures should count, but an attempt is recorded
//! when it starts and cleared by a successful login: recording after the PIN check would let
//! 40 parallel guesses all pass the check before the first failure lands.
//!
//! In memory on purpose: a Hub restart clears it, which costs an attacker nothing they could
//! not get by waiting a minute.

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, PoisonError};

pub const MAX_ATTEMPTS: usize = 5;
pub const WINDOW_MS: i64 = 60_000;
/// Longer `lrn_or_id` values share a key, so a huge one cannot grow the map without limit.
pub const MAX_KEY_CHARS: usize = 64;

#[derive(Default)]
pub struct LoginLimiter {
    attempts: Mutex<HashMap<String, Vec<i64>>>,
}

/// Inside the last minute. A time after `now` means the Hub PC's clock went back; dropping
/// those keeps any lockout under a minute instead of until the clock catches up.
fn recent(t: i64, now: i64) -> bool {
    t <= now && now - t < WINDOW_MS
}

fn key(lrn_or_id: &str) -> String {
    lrn_or_id.chars().take(MAX_KEY_CHARS).collect()
}

/// The seconds until another attempt is allowed (1 to 60), if `times` has reached the limit.
fn refuse(times: &mut [i64], now_ms: i64) -> Result<(), u64> {
    if times.len() < MAX_ATTEMPTS {
        return Ok(());
    }
    times.sort_unstable();
    // Allowed again once enough attempts leave the window to drop below the limit.
    let opens_at = times[times.len() - MAX_ATTEMPTS] + WINDOW_MS;
    let wait_ms = (opens_at - now_ms).max(0) as u64;
    Err(wait_ms.div_ceil(1000).clamp(1, 60))
}

impl LoginLimiter {
    /// Records the attempt, or refuses it with the seconds until one is allowed (1 to 60).
    /// A refused attempt is not recorded, so retrying while locked never extends the wait.
    pub fn try_attempt(&self, lrn_or_id: &str, now_ms: i64) -> Result<(), u64> {
        let mut attempts = self.pruned(now_ms);
        let times = attempts.entry(key(lrn_or_id)).or_default();
        refuse(times, now_ms)?;
        times.push(now_ms);
        Ok(())
    }

    /// The same answer as `try_attempt` without recording anything, for a request that never
    /// reaches the PIN check (the admin from off the Hub PC).
    pub fn check(&self, lrn_or_id: &str, now_ms: i64) -> Result<(), u64> {
        self.pruned(now_ms).get_mut(&key(lrn_or_id)).map_or(Ok(()), |times| refuse(times, now_ms))
    }

    /// The map with only the last minute's attempts left in it.
    fn pruned(&self, now_ms: i64) -> MutexGuard<'_, HashMap<String, Vec<i64>>> {
        let mut attempts = self.attempts.lock().unwrap_or_else(PoisonError::into_inner);
        attempts.retain(|_, times| {
            times.retain(|&t| recent(t, now_ms));
            !times.is_empty()
        });
        attempts
    }

    /// A successful login clears the LRN's attempts.
    pub fn reset(&self, lrn_or_id: &str) {
        self.attempts.lock().unwrap_or_else(PoisonError::into_inner).remove(&key(lrn_or_id));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn five_attempts(limiter: &LoginLimiter, lrn: &str, start: i64) {
        for i in 0..5 {
            assert_eq!(limiter.try_attempt(lrn, start + i * 1000), Ok(()), "attempt {i}");
        }
    }

    #[test]
    fn five_attempts_then_refused() {
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        // The oldest attempt (t=0) leaves the window at 60 000 ms: 55 s from t=5000.
        assert_eq!(limiter.try_attempt("123456789012", 5000), Err(55));
    }

    #[test]
    fn refused_attempts_do_not_extend_the_wait() {
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        for _ in 0..10 {
            assert!(limiter.try_attempt("123456789012", 5000).is_err());
        }
        assert_eq!(limiter.try_attempt("123456789012", 5000), Err(55));
    }

    #[test]
    fn check_answers_like_try_attempt_but_records_nothing() {
        let limiter = LoginLimiter::default();
        for _ in 0..10 {
            assert_eq!(limiter.check("123456789012", 0), Ok(()));
        }
        five_attempts(&limiter, "123456789012", 0);
        assert_eq!(limiter.check("123456789012", 5000), Err(55));
        assert_eq!(limiter.check("123456789013", 5000), Ok(()));
    }

    #[test]
    fn window_slides() {
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        assert_eq!(limiter.try_attempt("123456789012", 60_001), Ok(()));
    }

    #[test]
    fn success_resets() {
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        limiter.reset("123456789012");
        five_attempts(&limiter, "123456789012", 5000);
    }

    #[test]
    fn lrns_are_independent() {
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        assert_eq!(limiter.try_attempt("123456789013", 5000), Ok(()));
    }

    #[test]
    fn clock_going_backwards_never_locks_longer_than_a_minute() {
        let limiter = LoginLimiter::default();
        // Recorded while the Hub PC's clock was a day ahead, then the clock was corrected.
        five_attempts(&limiter, "123456789012", 86_400_000);
        assert_eq!(limiter.try_attempt("123456789012", 0), Ok(()));
        let limiter = LoginLimiter::default();
        five_attempts(&limiter, "123456789012", 0);
        let wait = limiter.try_attempt("123456789012", 4001).unwrap_err();
        assert!((1..=60).contains(&wait), "{wait}");
    }

    #[test]
    fn old_keys_are_pruned() {
        let limiter = LoginLimiter::default();
        limiter.try_attempt("A", 0).unwrap();
        limiter.try_attempt("B", 120_000).unwrap();
        let attempts = limiter.attempts.lock().unwrap();
        assert_eq!(attempts.keys().collect::<Vec<_>>(), ["B"]);
    }

    #[test]
    fn long_lrns_are_kept_short() {
        let limiter = LoginLimiter::default();
        let long = "9".repeat(100_000);
        limiter.try_attempt(&long, 0).unwrap();
        let attempts = limiter.attempts.lock().unwrap();
        assert!(attempts.keys().all(|k| k.len() <= MAX_KEY_CHARS));
    }
}
