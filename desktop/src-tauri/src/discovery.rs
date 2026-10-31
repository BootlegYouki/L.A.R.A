use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::Ipv4Addr,
    sync::{Arc, Mutex},
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
        let mut leases: Vec<_> = self.leases.iter().collect();
        // UDP supplies explicit ports; mDNS only supplies the HTTP port.
        leases.sort_by_key(|(source, (_, seen))| (source.starts_with("udp:"), *seen));
        let mut hubs = HashMap::new();
        for (_, (hub, _)) in leases {
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
    registry: Arc<Mutex<Registry>>,
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
    let registry = app.state::<Discovery>().registry.clone();
    tauri::async_runtime::spawn(scan(registry, receiver, move |hub| {
        let _ = app.emit("hub-found", hub);
    }));
}
pub fn stop(app: &tauri::AppHandle) {
    if let Ok(mut current) = app.state::<Discovery>().stop.lock() {
        if let Some(stop) = current.take() {
            let _ = stop.send(true);
        }
    }
}

async fn scan(
    registry: Arc<Mutex<Registry>>,
    mut stop: watch::Receiver<bool>,
    found: impl Fn(HubInfo) + Send + 'static,
) {
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
                        if let Ok(mut registry) = registry.lock() {
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
                                    if let Ok(mut registry) = registry.lock() {
                                        registry.seen(format!("mdns:{}", info.get_fullname()), hub, Instant::now());
                                    }
                                    break;
                                }
                            }
                        }
                    },
                    Ok(ServiceEvent::ServiceRemoved(_, name)) => {
                        if let Ok(mut registry) = registry.lock() {
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
        let hubs = registry
            .lock()
            .map(|mut r| r.snapshot(Instant::now()))
            .unwrap_or_default();
        for hub in &hubs {
            if !previous.contains(hub) {
                found(hub.clone());
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
    fn explicit_udp_ports_win_until_the_udp_lease_expires() {
        let mut registry = Registry::default();
        let now = Instant::now();
        let udp = HubInfo {
            ws_port: 8085,
            ..hub()
        };
        registry.seen("udp:192.168.1.50:8080".into(), udp.clone(), now);
        registry.seen(
            "mdns:Room._lara._tcp.local.".into(),
            hub(),
            now + Duration::from_secs(1),
        );
        assert_eq!(registry.snapshot(now + Duration::from_secs(1)), vec![udp]);
        assert_eq!(registry.snapshot(now + TTL), vec![hub()]);
    }
    #[test]
    fn bounds_untrusted_registry_growth() {
        let mut registry = Registry::default();
        for i in 0..1000 {
            registry.seen(i.to_string(), hub(), Instant::now());
        }
        assert_eq!(registry.leases.len(), LIMIT * 2);
    }

    #[tokio::test]
    #[ignore = "uses UDP 8888 and multicast; run alone with LARA_TEST_MDNS_IP set to this machine's LAN IPv4"]
    async fn native_scanner_network_lifecycle() {
        let registry = Arc::new(Mutex::new(Registry::default()));
        let events = Arc::new(Mutex::new(Vec::new()));
        let (stop, receiver) = watch::channel(false);
        let captured = events.clone();
        let task = tokio::spawn(scan(registry.clone(), receiver, move |hub| {
            captured.lock().unwrap().push(hub);
        }));
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let payload = br#"{"app":"lara","version":"test","name":"UDP lifecycle fixture","ip":"127.0.0.2","http_port":8080,"ws_port":8085}"#;
        for _ in 0..20 {
            socket.send_to(payload, "127.0.0.1:8888").await.unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
            if !registry.lock().unwrap().snapshot(Instant::now()).is_empty() {
                break;
            }
        }
        assert_eq!(
            registry.lock().unwrap().snapshot(Instant::now())[0].ws_port,
            8085
        );
        assert!(
            !events.lock().unwrap().is_empty(),
            "new Hub notification missing"
        );
        tokio::time::sleep(TTL + Duration::from_secs(1)).await;
        assert!(registry.lock().unwrap().snapshot(Instant::now()).is_empty());
        socket.send_to(payload, "127.0.0.1:8888").await.unwrap();
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(registry.lock().unwrap().snapshot(Instant::now()).len(), 1);
        stop.send(true).unwrap();
        tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .unwrap()
            .unwrap();
        let blocker = UdpSocket::bind("0.0.0.0:8888")
            .await
            .expect("scanner did not release UDP port on stop");
        registry.lock().unwrap().leases.clear();

        let ip = std::env::var("LARA_TEST_MDNS_IP").expect("set this machine's LAN IPv4");
        assert!(allowed_ip(ip.parse().unwrap()));
        let publisher = ServiceDaemon::new().unwrap();
        let service = mdns_sd::ServiceInfo::new(
            SERVICE,
            "Desktop mDNS Lifecycle Fixture",
            "lara-lifecycle.local.",
            ip.as_str(),
            8080,
            None,
        )
        .unwrap();
        let fullname = service.get_fullname().to_owned();
        publisher.register(service).unwrap();
        let (stop, receiver) = watch::channel(false);
        let task = tokio::spawn(scan(registry.clone(), receiver, |_| {}));
        for _ in 0..100 {
            if !registry.lock().unwrap().snapshot(Instant::now()).is_empty() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        for _ in 0..20 {
            let hubs = registry.lock().unwrap().snapshot(Instant::now());
            assert!(
                hubs.iter()
                    .any(|h| h.name == "Desktop mDNS Lifecycle Fixture"),
                "mDNS-only fixture missing: {hubs:?}"
            );
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        publisher.unregister(&fullname).unwrap();
        publisher.shutdown().unwrap().recv_async().await.unwrap();
        tokio::time::sleep(TTL + Duration::from_secs(1)).await;
        assert!(registry.lock().unwrap().snapshot(Instant::now()).is_empty());
        stop.send(true).unwrap();
        tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .unwrap()
            .unwrap();
        drop(blocker);
        assert!(UdpSocket::bind("0.0.0.0:8888").await.is_ok());
    }

    #[tokio::test]
    #[ignore = "requires a real Hub on another LAN device; close Desktop and set LARA_TEST_REAL_HUB_IP"]
    async fn real_hub_mdns_without_udp() {
        let expected: Ipv4Addr = std::env::var("LARA_TEST_REAL_HUB_IP")
            .expect("set the second device's real Hub IPv4")
            .parse()
            .unwrap();
        assert!(allowed_ip(expected));
        // Occupying the beacon port forces the actual scanner to use mDNS only.
        let _blocker = UdpSocket::bind("0.0.0.0:8888")
            .await
            .expect("close Desktop and other UDP 8888 listeners first");
        let registry = Arc::new(Mutex::new(Registry::default()));
        let (stop, receiver) = watch::channel(false);
        let task = tokio::spawn(scan(registry.clone(), receiver, |_| {}));
        let deadline = Instant::now() + Duration::from_secs(25);
        let mut resolved = false;
        while Instant::now() < deadline {
            if registry
                .lock()
                .unwrap()
                .snapshot(Instant::now())
                .iter()
                .any(|hub| hub.ip == expected && hub.http_port == 8080)
            {
                resolved = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        let mut stable = resolved;
        if resolved {
            for _ in 0..20 {
                tokio::time::sleep(Duration::from_secs(1)).await;
                stable &= registry
                    .lock()
                    .unwrap()
                    .snapshot(Instant::now())
                    .iter()
                    .any(|hub| hub.ip == expected && hub.http_port == 8080);
            }
        }
        stop.send(true).unwrap();
        tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .unwrap()
            .unwrap();
        assert!(resolved, "real Hub did not resolve with UDP unavailable");
        assert!(
            stable,
            "real Hub mDNS candidate disappeared during observation"
        );
        println!(
            "Real Hub {expected}:8080 resolved and stayed visible for 20 seconds with UDP blocked"
        );
    }
}
