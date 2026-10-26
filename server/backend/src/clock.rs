use std::time::{SystemTime, UNIX_EPOCH};

/// Every time the Hub reads goes through `AppState.clock`, so time rules can be tested.
pub trait Clock: Send + Sync {
    fn now_ms(&self) -> i64;
}

pub struct SystemClock;

impl Clock for SystemClock {
    fn now_ms(&self) -> i64 {
        // A PC clock set before 1970 reads as 0 rather than crashing the Hub.
        SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }
}
