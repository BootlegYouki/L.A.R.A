use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::Ipv4Addr,
    sync::Mutex,
    time::{Duration, Instant},
};
use tauri::{Emitter, Manager};
use tokio::{net::UdpSocket, sync::watch};

const SERVICE: &str = "_lara._tcp.local.";
const TTL: Duration = Duration::from_secs(10);
const LIMIT: usize = 64;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct HubInfo {
    pub name: String,
    pub ip: Ipv4Addr,
    pub http_port: u16,
    pub ws_port: u16,
}

fn allowed_ip(ip: Ipv4Addr) -> bool {
    ip.is_private() || ip.is_loopback()
}
fn valid_hub(hub: &HubInfo) -> bool {
    allowed_ip(hub.ip)
        && hub.http_port > 0
        && hub.ws_port > 0
        && !hub.name.trim().is_empty()
        && hub.name.chars().count() <= 160
        && !hub.name.chars().any(char::is_control)
}
#[derive(Deserialize)]
struct Beacon {
    app: String,
    version: String,
    #[serde(flatten)]
    hub: HubInfo,
}
fn parse_beacon(bytes: &[u8]) -> Option<HubInfo> {
    if bytes.len() > 4096 {
        return None;
    }
    let beacon: Beacon = serde_json::from_slice(bytes).ok()?;
    (beacon.app == "lara" && !beacon.version.trim().is_empty() && valid_hub(&beacon.hub))
        .then_some(beacon.hub)
}

#[derive(Default)]
struct Registry {
    // Each transport owns its lease, so losing mDNS cannot remove a live UDP Hub.
    leases: HashMap<String, (HubInfo, Instant)>,
}
impl Registry {
    fn seen(&mut self, source: String, hub: HubInfo, now: Instant) {
        if self.leases.len() < LIMIT * 2 || self.leases.contains_key(&source) {
            self.leases.insert(source, (hub, now));
        }
    }
    fn snapshot(&mut self, now: Instant) -> Vec<HubInfo> {
        self.leases
            .retain(|_, (_, seen)| now.duration_since(*seen) < TTL);
        let mut leases: Vec<_> = self.leases.values().collect();
        leases.sort_by_key(|(_, seen)| *seen);
        let mut hubs = HashMap::new();
        for (hub, _) in leases {
            hubs.insert((hub.ip, hub.http_port), hub.clone());
        }
        let mut hubs: Vec<_> = hubs.into_values().collect();
        hubs.sort_by(|a, b| (&a.name, a.ip, a.http_port).cmp(&(&b.name, b.ip, b.http_port)));
        hubs.truncate(LIMIT);
        hubs
    }
}

#[derive(Default)]
pub struct Discovery {
    registry: Mutex<Registry>,
    stop: Mutex<Option<watch::Sender<bool>>>,
}
#[tauri::command]
pub async fn get_discovered_hubs(
    state: tauri::State<'_, Discovery>,
) -> Result<Vec<HubInfo>, &'static str> {
    Ok(state
        .registry
        .lock()
        .map_err(|_| "discovery_unavailable")?
        .snapshot(Instant::now()))
}

pub fn start(app: tauri::AppHandle) {
    let (stop, receiver) = watch::channel(false);
    if let Ok(mut current) = app.state::<Discovery>().stop.lock() {
        *current = Some(stop);
    }
    tauri::async_runtime::spawn(scan(app, receiver));
}
pub fn stop(app: &tauri::AppHandle) {
    if let Ok(mut current) = app.state::<Discovery>().stop.lock() {
        if let Some(stop) = current.take() {
            let _ = stop.send(true);
        }
    }
}

async fn scan(app: tauri::AppHandle, mut stop: watch::Receiver<bool>) {
    let mut socket: Option<UdpSocket> = None;
    let mut mdns = None;
    let mut events: Option<mdns_sd::Receiver<ServiceEvent>> = None;
    let mut timer = tokio::time::interval(Duration::from_secs(1));
    let mut buffer = [0; 4097];
    let mut previous = Vec::new();
    let mut ticks = 0u32;
    loop {
        tokio::select! {
            _ = stop.changed() => break,
            _ = timer.tick() => {
                ticks = ticks.wrapping_add(1);
                if socket.is_none() { socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 8888)).await.ok(); }
                if mdns.is_none() {
                    if let Ok(daemon) = ServiceDaemon::new() {
                        let _ = daemon.set_ip_check_interval(2);
                        match daemon.browse(SERVICE) {
                            Ok(receiver) => { events = Some(receiver); mdns = Some(daemon); }
                            Err(_) => { let _ = daemon.shutdown(); }
                        }
                    }
                }
                if ticks % 4 == 0 {
                    // Fresh PTR queries renew leases without treating cached records as live peers.
                    if let Some(daemon) = mdns.take() {
                        let _ = daemon.stop_browse(SERVICE);
                        if let Ok(done) = daemon.shutdown() {
                            let _ = tokio::time::timeout(Duration::from_secs(1), done.recv_async()).await;
                        }
                    }
                    events = None;
                }
            },
            packet = async {
                match &socket { Some(socket) => socket.recv_from(&mut buffer).await,
                    None => std::future::pending().await }
            } => {
                match packet {
                    Ok((length, _)) => if let Some(hub) = parse_beacon(&buffer[..length]) {
                        if let Ok(mut registry) = app.state::<Discovery>().registry.lock() {
                            registry.seen(format!("udp:{}:{}", hub.ip, hub.http_port), hub, Instant::now());
                        }
                    },
                    Err(_) => socket = None,
                }
            },
            event = async {
                match &events { Some(events) => events.recv_async().await,
                    None => std::future::pending().await }
            } => {
                match event {
                    Ok(ServiceEvent::ServiceResolved(info)) => {
                        let name = info.get_fullname().strip_suffix(&format!(".{SERVICE}")).unwrap_or("").to_owned();
                        for address in info.get_addresses() {
                            if let std::net::IpAddr::V4(ip) = address.to_ip_addr() {
                                let hub = HubInfo { name: name.clone(), ip, http_port: info.get_port(), ws_port: 8081 };
                                if valid_hub(&hub) {
                                    if let Ok(mut registry) = app.state::<Discovery>().registry.lock() {
                                        registry.seen(format!("mdns:{}", info.get_fullname()), hub, Instant::now());
                                    }
                                    break;
                                }
                            }
                        }
                    },
                    Ok(ServiceEvent::ServiceRemoved(_, name)) => {
                        if let Ok(mut registry) = app.state::<Discovery>().registry.lock() {
                            registry.leases.remove(&format!("mdns:{name}"));
                        }
                    },
                    Err(_) => {
                        if let Some(daemon) = mdns.take() { let _ = daemon.shutdown(); }
                        events = None;
                    },
                    _ => {},
                }
            },
        }
        let hubs = app
            .state::<Discovery>()
            .registry
            .lock()
            .map(|mut r| r.snapshot(Instant::now()))
            .unwrap_or_default();
        for hub in &hubs {
            if !previous.contains(hub) {
                let _ = app.emit("hub-found", hub);
            }
        }
        previous = hubs;
    }
    if let Some(daemon) = mdns {
        let _ = daemon.stop_browse(SERVICE);
        let _ = daemon.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hub() -> HubInfo {
        HubInfo {
            name: "Room 1".into(),
            ip: Ipv4Addr::new(192, 168, 1, 50),
            http_port: 8080,
            ws_port: 8081,
        }
    }
    #[test]
    fn validates_untrusted_beacons() {
        let valid = br#"{"app":"lara","version":"1","name":"Room 1","ip":"192.168.1.50","http_port":8080,"ws_port":8081}"#;
        assert_eq!(parse_beacon(valid), Some(hub()));
        for (from, to) in [
            ("lara", "other"),
            ("192.168.1.50", "8.8.8.8"),
            ("192.168.1.50", "999.1.1.1"),
            ("8080", "0"),
            ("8080", "65536"),
            ("8080", "\"8080\""),
            ("Room 1", ""),
        ] {
            assert!(
                parse_beacon(String::from_utf8_lossy(valid).replace(from, to).as_bytes()).is_none()
            );
        }
        for bytes in [b"{}".as_slice(), b"[]", b"bad", &valid[..30], &[b'x'; 4097]] {
            assert!(parse_beacon(bytes).is_none());
        }
    }
    #[test]
    fn deduplicates_sources_and_expires_silent_hubs() {
        let mut registry = Registry::default();
        let now = Instant::now();
        registry.seen("udp".into(), hub(), now);
        registry.seen("mdns".into(), hub(), now);
        assert_eq!(registry.snapshot(now).len(), 1);
        registry.leases.remove("mdns");
        assert_eq!(registry.snapshot(now).len(), 1);
        assert!(registry.snapshot(now + TTL).is_empty());
        registry.seen("udp".into(), hub(), now + TTL);
        assert_eq!(registry.snapshot(now + TTL).len(), 1);
    }
    #[test]
    fn bounds_untrusted_registry_growth() {
        let mut registry = Registry::default();
        for i in 0..1000 {
            registry.seen(i.to_string(), hub(), Instant::now());
        }
        assert_eq!(registry.leases.len(), LIMIT * 2);
    }
}
