//! UDP subnet discovery beacon (`rules/networking-and-lan.md` §2).
//!
//! Every 3 seconds the Hub broadcasts a small JSON heartbeat to `255.255.255.255:8888` so clients
//! on the same subnet can find it without any configuration. Routers with AP isolation drop these
//! packets, which is why every client also keeps a manual IP box (Countermeasure 1).
//!
//! The payload keys match `scripts/mock_hub.py` exactly: `app`, `version`, `name`, `ip`,
//! `http_port`, `ws_port`.
//!
//! The socket is bound to the classroom adapter's address (`lan_ip()`), not `0.0.0.0` (#200). With
//! `0.0.0.0`, Windows chose the adapter for `255.255.255.255` itself, and on a PC with VirtualBox it
//! chose the Host-Only adapter (same route metric), so phones on the router never got the beacon.
//! Bound to the LAN address, the packet leaves through that adapter. Payload and port are unchanged.

use std::net::{IpAddr, Ipv4Addr, SocketAddrV4};
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

/// The beacon's socket: an ephemeral port on the classroom adapter's address, allowed to broadcast.
/// Binding to that address is what makes the OS send the broadcast out of that adapter (#200).
pub async fn bind_beacon_socket(ip: Ipv4Addr) -> std::io::Result<UdpSocket> {
    let socket = UdpSocket::bind(SocketAddrV4::new(ip, 0)).await?;
    socket.set_broadcast(true)?;
    Ok(socket)
}

/// Broadcasts until `stop` flips to `true`. Never returns an error to the caller: a classroom
/// router that blocks broadcast must not stop the Hub from serving. A socket that cannot be opened
/// is logged once, then silently retried, so a flaky adapter does not spam the console every 3 s.
pub async fn run(info: BeaconInfo, mut stop: watch::Receiver<bool>) {
    let version = env!("CARGO_PKG_VERSION");
    tracing::info!("discovery beacon broadcasting to {BROADCAST_ADDR} every {}s", BEACON_INTERVAL.as_secs());

    // The address the socket is bound to, and the socket. `lan_ip()` is re-read every tick (DHCP, or
    // the PC plugged into the router after start), so the socket is re-bound when it changes.
    let mut bound: Option<(Ipv4Addr, UdpSocket)> = None;
    let mut bind_failure_logged = false;
    let mut ticker = tokio::time::interval(BEACON_INTERVAL);
    loop {
        tokio::select! {
            _ = ticker.tick() => {
                // No network yet: skip this tick quietly and try again. A client cannot reach a Hub
                // that is on no network anyway, so an address-less beacon would only mislead.
                let Some(IpAddr::V4(ip)) = super::lan_ip() else { continue };
                if bound.as_ref().map(|(at, _)| *at) != Some(ip) {
                    bound = None;
                    match bind_beacon_socket(ip).await {
                        Ok(socket) => {
                            tracing::info!(%ip, "discovery beacon sending from the classroom adapter");
                            bound = Some((ip, socket));
                            bind_failure_logged = false;
                        }
                        Err(e) => {
                            if !bind_failure_logged {
                                tracing::warn!(error = %e, %ip, "discovery beacon could not open a UDP socket on the classroom adapter; retrying every tick");
                                bind_failure_logged = true;
                            }
                            continue;
                        }
                    }
                }
                let Some((_, socket)) = &bound else { continue };
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

    /// #167: the beacon's `name` is plain JSON, so the dots in "L.A.R.A Hub" arrive untouched (only
    /// the mDNS name needed a fix).
    #[test]
    fn beacon_keeps_a_hub_name_with_dots_whole() {
        let beacon = Beacon {
            app: "lara",
            version: "9.9.9",
            name: "L.A.R.A Hub",
            ip: "192.168.1.50".to_string(),
            http_port: 8080,
            ws_port: 8081,
        };
        let value: Value = serde_json::from_slice(&serde_json::to_vec(&beacon).unwrap()).unwrap();
        assert_eq!(value["name"], "L.A.R.A Hub");
    }

    #[test]
    fn beacon_port_and_interval_match_the_rules_file() {
        assert_eq!(BEACON_PORT, 8888);
        assert_eq!(BEACON_INTERVAL, Duration::from_secs(3));
    }

    /// #200: the socket is bound to the adapter address it is given, never `0.0.0.0`, so the OS
    /// cannot pick another adapter (VirtualBox, Hyper-V, VPN) for the broadcast. Loopback stands in
    /// for the classroom adapter, since a test machine's LAN address is not known in advance.
    #[tokio::test]
    async fn beacon_socket_is_bound_to_the_classroom_address() {
        let socket = bind_beacon_socket(Ipv4Addr::LOCALHOST).await.unwrap();
        let local = socket.local_addr().unwrap();
        assert_eq!(local.ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
        assert!(!local.ip().is_unspecified());
        assert_ne!(local.port(), BEACON_PORT, "sends from an ephemeral port; clients listen on 8888");
        assert!(socket.broadcast().unwrap(), "the socket may broadcast");

        // A packet from this socket arrives from that address, which is what puts it on that adapter.
        let receiver = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        socket.send_to(b"beacon", receiver.local_addr().unwrap()).await.unwrap();
        let mut buf = [0u8; 16];
        let (len, from) = tokio::time::timeout(Duration::from_secs(5), receiver.recv_from(&mut buf)).await.unwrap().unwrap();
        assert_eq!(&buf[..len], b"beacon");
        assert_eq!(from, local);
    }
}
