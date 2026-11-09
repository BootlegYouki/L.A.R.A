//! The per-device download cap (PRD FR-3.4, step 3.2, #8): each device on the classroom Wi-Fi gets
//! 2.0 MB/s from the Hub in total, across every file it is fetching at once (video streams, Save for
//! Home downloads, handouts, homework photos). A video player often opens several connections, so a
//! per-connection cap would let one phone take several shares; per device, 20 phones stay within
//! 40 MB/s of router bandwidth. A device is its address on the LAN.
//!
//! The allowance is a token bucket that can go into debt: a send books its bytes and waits until
//! they are paid for, so sends from one device queue fairly in the order they asked.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use axum::body::Body;
use futures_util::StreamExt;

/// 2.0 MB/s (decimal megabytes), the contract's "max 2.0 MB/s per client".
pub const BYTES_PER_SECOND: u64 = 2_000_000;
/// What a device can send at once after a pause, so a seek starts without waiting: one read of the
/// file sender (`tower-http` reads 64 KiB at a time). The allowance refills it in about 33 ms.
pub const BURST_BYTES: u64 = 64 * 1024;
/// A /24 network's worth of devices. Past it, devices with no download running are forgotten.
const MAX_DEVICES: usize = 256;

/// Every device's allowance, by address.
#[derive(Default)]
pub struct Bandwidth {
    devices: Mutex<HashMap<IpAddr, Arc<Allowance>>>,
}

impl Bandwidth {
    /// The device's allowance, shared by all its downloads.
    pub fn device(&self, ip: IpAddr) -> Arc<Allowance> {
        let mut devices = self.devices.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(allowance) = devices.get(&ip) {
            return Arc::clone(allowance);
        }
        if devices.len() >= MAX_DEVICES {
            // Nobody else holds an idle device's allowance; its saved-up 64 KiB is all it loses.
            devices.retain(|_, allowance| Arc::strong_count(allowance) > 1);
        }
        let allowance = Arc::new(Allowance::new(Instant::now()));
        devices.insert(ip, Arc::clone(&allowance));
        allowance
    }

    #[cfg(test)]
    fn devices(&self) -> usize {
        self.devices.lock().unwrap_or_else(PoisonError::into_inner).len()
    }
}

/// One device's 2.0 MB/s.
pub struct Allowance {
    state: Mutex<Credit>,
}

struct Credit {
    /// Bytes the device may still send at once; negative while sends are waiting to be paid for.
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

    /// Waits until the device may send `bytes` more.
    async fn spend(&self, bytes: usize) {
        let wait = self.book(bytes as u64, Instant::now());
        if !wait.is_zero() {
            tokio::time::sleep(wait).await;
        }
    }
}

/// A response body that sends no faster than the device's allowance. Each piece waits for its bytes
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
        let device = Allowance::new(t0);
        assert_eq!(device.book(BURST_BYTES, t0), Duration::ZERO);
        assert!(device.book(1, t0) > Duration::ZERO, "the next byte waits for the allowance");
    }

    #[test]
    fn the_allowance_refills_at_2_mb_a_second() {
        let t0 = Instant::now();
        let device = Allowance::new(t0);
        device.book(BURST_BYTES, t0);
        assert_eq!(device.book(BYTES_PER_SECOND, t0), SECOND);
        // Sends queue in order: one second later the first is paid for and the next waits its own second.
        assert_eq!(device.book(BYTES_PER_SECOND, t0 + SECOND), SECOND);
    }

    #[test]
    fn two_downloads_on_one_device_share_its_allowance() {
        let t0 = Instant::now();
        let device = Allowance::new(t0);
        assert_eq!(device.book(BURST_BYTES + BYTES_PER_SECOND / 2, t0), SECOND / 2);
        assert_eq!(device.book(BYTES_PER_SECOND / 2, t0), SECOND, "the second download waits behind the first");
    }

    #[test]
    fn a_long_pause_saves_up_no_more_than_64_kb() {
        let t0 = Instant::now();
        let device = Allowance::new(t0);
        device.book(BURST_BYTES, t0);
        let later = t0 + 10 * SECOND;
        assert_eq!(device.book(BURST_BYTES + BYTES_PER_SECOND, later), SECOND);
    }

    #[test]
    fn each_device_has_its_own_allowance() {
        let bandwidth = Bandwidth::default();
        let phone = bandwidth.device(ip(10));
        assert!(Arc::ptr_eq(&phone, &bandwidth.device(ip(10))), "a device's downloads share one allowance");
        assert!(!Arc::ptr_eq(&phone, &bandwidth.device(ip(11))), "another device has its own");
    }

    #[test]
    fn devices_no_longer_downloading_are_forgotten_when_the_table_fills() {
        let bandwidth = Bandwidth::default();
        let busy = bandwidth.device(ip(1));
        let idle = |i: usize| IpAddr::V4(Ipv4Addr::new(10, 0, (i / 256) as u8, (i % 256) as u8));
        for i in 1..MAX_DEVICES {
            bandwidth.device(idle(i));
        }
        assert_eq!(bandwidth.devices(), MAX_DEVICES);
        bandwidth.device(ip(2));
        assert_eq!(bandwidth.devices(), 2, "only the device still downloading and the new one are kept");
        assert!(Arc::ptr_eq(&busy, &bandwidth.device(ip(1))), "a device mid-download keeps its allowance");
    }
}
