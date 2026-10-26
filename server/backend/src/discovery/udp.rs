//! UDP subnet discovery beacon (`rules/networking-and-lan.md` §2).
//!
//! Every 3 seconds the Hub broadcasts a small JSON heartbeat to `255.255.255.255:8888` so clients
//! on the same subnet can find it without any configuration. Routers with AP isolation drop these
//! packets, which is why every client also keeps a manual IP box (Countermeasure 1).
//!
//! The payload keys match `scripts/mock_hub.py` exactly: `app`, `version`, `name`, `ip`,
//! `http_port`, `ws_port`.

use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::Duration;

use serde::Serialize;
use tokio::net::UdpSocket;
use tokio::sync::watch;

/// Fixed by the rules file. Broadcasting every 3 s lets a client that just joined the network see
/// the Hub within one interval.
pub const BEACON_PORT: u16 = 8888;
pub const BEACON_INTERVAL: Duration = Duration::from_secs(3);

const BROADCAST_ADDR: SocketAddrV4 = SocketAddrV4::new(Ipv4Addr::BROADCAST, BEACON_PORT);

/// One heartbeat packet. `ip` is the address a client should dial; the other adapters (VirtualBox,
/// Hyper-V, VPN) are deliberately left out so a learner never copies an unreachable address.
#[derive(Serialize)]
struct Beacon<'a> {
    app: &'a str,
    version: &'a str,
    name: &'a str,
    ip: String,
    http_port: u16,
    ws_port: u16,
}

/// Inputs the beacon needs that do not change between ticks. The IP is re-read each tick because a
/// dedicated Hub PC may be plugged into the router after the app has already started.
#[derive(Clone)]
pub struct BeaconInfo {
    pub name: String,
    pub http_port: u16,
    pub ws_port: u16,
}

/// Broadcasts until `stop` flips to `true`. Never returns an error to the caller: a classroom
/// router that blocks broadcast must not stop the Hub from serving. Problems are logged once at
/// startup, then silently retried, so a flaky adapter does not spam the console every 3 seconds.
pub async fn run(info: BeaconInfo, mut stop: watch::Receiver<bool>) {
    // Bind to an ephemeral port on all interfaces; the OS picks the source address per packet.
    let socket = match UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).await {
        Ok(socket) => socket,
        Err(e) => {
            tracing::warn!(error = %e, "discovery beacon disabled: could not open a UDP socket");
            return;
        }
    };
    if let Err(e) = socket.set_broadcast(true) {
        tracing::warn!(error = %e, "discovery beacon disabled: this socket cannot broadcast");
        return;
    }

    let version = env!("CARGO_PKG_VERSION");
    tracing::info!("discovery beacon broadcasting to {BROADCAST_ADDR} every {}s", BEACON_INTERVAL.as_secs());

    let mut ticker = tokio::time::interval(BEACON_INTERVAL);
    loop {
        tokio::select! {
            _ = ticker.tick() => {
                // No network yet: skip this tick quietly and try again. A client cannot reach a Hub
                // that is on no network anyway, so an address-less beacon would only mislead.
                let Some(ip) = super::lan_ip() else { continue };
                let beacon = Beacon {
                    app: "lara",
                    version,
                    name: &info.name,
                    ip: ip.to_string(),
                    http_port: info.http_port,
                    ws_port: info.ws_port,
                };
                let payload = match serde_json::to_vec(&beacon) {
                    Ok(payload) => payload,
                    Err(e) => { tracing::error!(error = %e, "beacon failed to serialize"); continue }
                };
                // A dropped packet is normal (AP isolation, transient routing). Clients get the next one.
                if let Err(e) = socket.send_to(&payload, BROADCAST_ADDR).await {
                    tracing::debug!(error = %e, "beacon packet not sent");
                }
            }
            _ = stop.changed() => {
                if *stop.borrow() {
                    tracing::info!("discovery beacon stopped");
                    return;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    /// The beacon payload must match `scripts/mock_hub.py` key for key: clients parse these names.
    /// A renamed or dropped field silently breaks discovery, so pin the exact shape here.
    #[test]
    fn beacon_has_the_mock_hub_keys_and_values() {
        let beacon = Beacon {
            app: "lara",
            version: "9.9.9",
            name: "Grade 4 - Science",
            ip: "192.168.1.50".to_string(),
            http_port: 8080,
            ws_port: 8081,
        };
        let value: Value = serde_json::from_slice(&serde_json::to_vec(&beacon).unwrap()).unwrap();

        let object = value.as_object().unwrap();
        let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["app", "http_port", "ip", "name", "version", "ws_port"]);

        assert_eq!(value["app"], "lara");
        assert_eq!(value["version"], "9.9.9");
        assert_eq!(value["name"], "Grade 4 - Science");
        assert_eq!(value["ip"], "192.168.1.50");
        assert_eq!(value["http_port"], 8080);
        assert_eq!(value["ws_port"], 8081);
    }

    #[test]
    fn beacon_port_and_interval_match_the_rules_file() {
        assert_eq!(BEACON_PORT, 8888);
        assert_eq!(BEACON_INTERVAL, Duration::from_secs(3));
    }
}
