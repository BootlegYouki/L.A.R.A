//! The 2.0 MB/s download cap (PRD FR-3.4, step 3.2, #8): `rules/networking-and-lan.md` section 3
//! counts it per signed-in user, across all their streams and downloads, not per connection. A
//! video player often opens several connections, and a learner may use a phone and a desktop; both
//! share the one 2.0 MB/s, so 20 learners stay within 40 MB/s of router bandwidth. Keying on the
//! user rather than the address also keeps learners apart when a classroom router translates
//! addresses and every phone looks the same to the Hub. The portal's installers have no signed-in
//! user, so there the cap is per device address (server TECH_SPEC, Bandwidth).
//!
//! The allowance is a token bucket that can go into debt: a send books its bytes and waits until
//! they are paid for, so one client's downloads queue fairly in the order they asked.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use axum::body::Body;
use futures_util::StreamExt;

/// 2.0 MB/s (decimal megabytes), the contract's "max 2.0 MB/s per client".
pub const BYTES_PER_SECOND: u64 = 2_000_000;
/// What a client can get at once after a pause, so a seek starts without waiting: one read of the
/// file sender (`tower-http` reads 64 KiB at a time). The allowance refills it in about 33 ms.
pub const BURST_BYTES: u64 = 64 * 1024;
/// Past this many known clients, those with no download running are forgotten.
const MAX_CLIENTS: usize = 256;

/// Whose allowance it is.
#[derive(Clone, PartialEq, Eq, Hash)]
enum Client {
    User(String),
    Device(IpAddr),
}

/// Every client's allowance (TECH_SPEC `BandwidthLimiter`).
#[derive(Default)]
pub struct BandwidthLimiter {
    clients: Mutex<HashMap<Client, Arc<Allowance>>>,
}

impl BandwidthLimiter {
    /// A signed-in user's allowance, shared by all their downloads on every device.
    pub fn user(&self, user_id: &str) -> Arc<Allowance> {
        self.allowance(Client::User(user_id.to_owned()))
    }

    /// A device's allowance, for the portal, where no one is signed in.
    pub fn device(&self, ip: IpAddr) -> Arc<Allowance> {
        self.allowance(Client::Device(ip))
    }

    fn allowance(&self, client: Client) -> Arc<Allowance> {
        let mut clients = self.clients.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(allowance) = clients.get(&client) {
            return Arc::clone(allowance);
        }
        if clients.len() >= MAX_CLIENTS {
            // Nobody else holds an idle client's allowance; its saved-up 64 KiB is all it loses.
            clients.retain(|_, allowance| Arc::strong_count(allowance) > 1);
        }
        let allowance = Arc::new(Allowance::new(Instant::now()));
        clients.insert(client, Arc::clone(&allowance));
        allowance
    }

    #[cfg(test)]
    fn clients(&self) -> usize {
        self.clients.lock().unwrap_or_else(PoisonError::into_inner).len()
    }
}

/// One client's 2.0 MB/s.
pub struct Allowance {
    state: Mutex<Credit>,
}

struct Credit {
    /// Bytes the client may still get at once; negative while sends are waiting to be paid for.
    bytes: f64,
    at: Instant,
}

impl Allowance {
    fn new(now: Instant) -> Self {
        Self { state: Mutex::new(Credit { bytes: BURST_BYTES as f64, at: now }) }
    }

    /// Books `bytes` and returns how long to wait before sending them.
    fn book(&self, bytes: u64, now: Instant) -> Duration {
        let mut credit = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        let earned = now.saturating_duration_since(credit.at).as_secs_f64() * BYTES_PER_SECOND as f64;
        credit.bytes = (credit.bytes + earned).min(BURST_BYTES as f64) - bytes as f64;
        credit.at = credit.at.max(now);
        if credit.bytes >= 0.0 {
            Duration::ZERO
        } else {
            Duration::from_secs_f64(-credit.bytes / BYTES_PER_SECOND as f64)
        }
    }

    /// Waits until the client may get `bytes` more.
    async fn spend(&self, bytes: usize) {
        let wait = self.book(bytes as u64, Instant::now());
        if !wait.is_zero() {
            tokio::time::sleep(wait).await;
        }
    }
}

/// A response body that sends no faster than the client's allowance. Each piece waits for its bytes
/// before it goes out; a client that disconnects drops the body and stops spending at once.
pub fn throttle(body: Body, allowance: Arc<Allowance>) -> Body {
    Body::from_stream(body.into_data_stream().then(move |piece| {
        let allowance = Arc::clone(&allowance);
        async move {
            if let Ok(bytes) = &piece {
                allowance.spend(bytes.len()).await;
            }
            piece
        }
    }))
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    use super::*;

    const SECOND: Duration = Duration::from_secs(1);

    fn ip(last: u8) -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(192, 168, 1, last))
    }

    #[test]
    fn after_a_pause_64_kb_go_at_once() {
        let t0 = Instant::now();
        let client = Allowance::new(t0);
        assert_eq!(client.book(BURST_BYTES, t0), Duration::ZERO);
        assert!(client.book(1, t0) > Duration::ZERO, "the next byte waits for the allowance");
    }

    #[test]
    fn the_allowance_refills_at_2_mb_a_second() {
        let t0 = Instant::now();
        let client = Allowance::new(t0);
        client.book(BURST_BYTES, t0);
        assert_eq!(client.book(BYTES_PER_SECOND, t0), SECOND);
        // Sends queue in order: one second later the first is paid for and the next waits its own second.
        assert_eq!(client.book(BYTES_PER_SECOND, t0 + SECOND), SECOND);
    }

    #[test]
    fn two_downloads_of_one_client_share_its_allowance() {
        let t0 = Instant::now();
        let client = Allowance::new(t0);
        assert_eq!(client.book(BURST_BYTES + BYTES_PER_SECOND / 2, t0), SECOND / 2);
        assert_eq!(client.book(BYTES_PER_SECOND / 2, t0), SECOND, "the second download waits behind the first");
    }

    #[test]
    fn a_long_pause_saves_up_no_more_than_64_kb() {
        let t0 = Instant::now();
        let client = Allowance::new(t0);
        client.book(BURST_BYTES, t0);
        let later = t0 + 10 * SECOND;
        assert_eq!(client.book(BURST_BYTES + BYTES_PER_SECOND, later), SECOND);
    }

    #[test]
    fn each_signed_in_user_has_their_own_allowance() {
        let limiter = BandwidthLimiter::default();
        let ana = limiter.user("ana");
        assert!(Arc::ptr_eq(&ana, &limiter.user("ana")), "all of a user's downloads share one allowance");
        assert!(!Arc::ptr_eq(&ana, &limiter.user("ben")), "another user has their own");
    }

    #[test]
    fn each_portal_device_has_its_own_allowance() {
        let limiter = BandwidthLimiter::default();
        let phone = limiter.device(ip(10));
        assert!(Arc::ptr_eq(&phone, &limiter.device(ip(10))));
        assert!(!Arc::ptr_eq(&phone, &limiter.device(ip(11))));
        assert!(!Arc::ptr_eq(&phone, &limiter.user("192.168.1.10")), "a device is never mistaken for a user");
    }

    #[test]
    fn clients_no_longer_downloading_are_forgotten_when_the_table_fills() {
        let limiter = BandwidthLimiter::default();
        let busy = limiter.user("busy");
        for i in 1..MAX_CLIENTS {
            limiter.user(&format!("idle-{i}"));
        }
        assert_eq!(limiter.clients(), MAX_CLIENTS);
        limiter.user("new");
        assert_eq!(limiter.clients(), 2, "only the user still downloading and the new one are kept");
        assert!(Arc::ptr_eq(&busy, &limiter.user("busy")), "a user mid-download keeps their allowance");
    }
}
