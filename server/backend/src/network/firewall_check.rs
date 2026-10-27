//! LAN port check (step 1.4, `rules/networking-and-lan.md` section 4.1): for each port the installer
//! opens (TCP 8080, TCP 8081, UDP 8888), is the Hub listening, and does the OS firewall let a
//! learner's phone in? The dashboard (step 5.3) shows the result and offers the fix when it fails.
//!
//! A connection from the Hub to itself never crosses the inbound firewall, so reachability cannot
//! be probed from here. Instead we ask the firewall whether a rule allows the port.

use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::process::{Command, Output};
use std::time::Duration;

use crate::config::{HTTP_PORT, WS_PORT};
use crate::discovery::udp::BEACON_PORT;

/// Loopback answers in microseconds; anything slower means nothing is listening.
const CONNECT_TIMEOUT: Duration = Duration::from_millis(300);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Protocol {
    Tcp,
    Udp,
}

impl Protocol {
    #[cfg(any(target_os = "linux", test))]
    fn as_str(self) -> &'static str {
        match self {
            Protocol::Tcp => "tcp",
            Protocol::Udp => "udp",
        }
    }
}

/// One inbound port the Hub needs, and the name the Windows installer gives its firewall rule.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HubPort {
    pub protocol: Protocol,
    pub port: u16,
    pub rule_name: &'static str,
}

/// The installer scripts (`server/src-tauri/windows/hooks.nsh`, `linux/postinst.sh`) open exactly
/// these; `tests/firewall_check.rs` fails if they drift apart.
pub const HUB_PORTS: [HubPort; 3] = [
    HubPort { protocol: Protocol::Tcp, port: HTTP_PORT, rule_name: "LARA Local Hub (HTTP)" },
    HubPort { protocol: Protocol::Tcp, port: WS_PORT, rule_name: "LARA Local Hub (WebSocket)" },
    HubPort { protocol: Protocol::Udp, port: BEACON_PORT, rule_name: "LARA Discovery Beacon (UDP)" },
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FirewallStatus {
    /// A firewall rule lets the port in.
    Allowed,
    /// A firewall is running and nothing lets the port in: learners cannot connect.
    Blocked,
    /// No firewall is running, so nothing blocks the port.
    Off,
    /// The check could not tell (no permission to read the rules, or an unsupported OS).
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PortHealth {
    pub port: HubPort,
    /// Whether the Hub has the TCP socket open. `None` for UDP 8888: the Hub only sends the beacon
    /// to that port, it never binds it.
    pub listening: Option<bool>,
    pub firewall: FirewallStatus,
}

impl PortHealth {
    /// True only when the port is confirmed open to the LAN. `Unknown` counts as not reachable so
    /// the dashboard warns rather than shows a green light it cannot back up.
    pub fn reachable(&self) -> bool {
        self.listening != Some(false) && matches!(self.firewall, FirewallStatus::Allowed | FirewallStatus::Off)
    }
}

/// Checks all of [`HUB_PORTS`]. Blocking (it runs `netsh` or `firewall-cmd` and makes loopback
/// connects), so async callers use `spawn_blocking`.
pub fn check() -> Vec<PortHealth> {
    HUB_PORTS.iter().map(check_port).collect()
}

pub fn check_port(port: &HubPort) -> PortHealth {
    PortHealth { port: *port, listening: listening(port), firewall: firewall_status(port) }
}

fn listening(port: &HubPort) -> Option<bool> {
    match port.protocol {
        Protocol::Tcp => {
            let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port.port));
            Some(TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT).is_ok())
        }
        Protocol::Udp => None,
    }
}

/// Runs a system tool; `None` when it is not installed or could not start.
fn run(command: &mut Command) -> Option<Output> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // The Hub is a windowed app: without this every check flashes a console window.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command.output().ok()
}

/// Looks the rule up by the name the installer gave it. netsh's text is translated on non-English
/// Windows but its exit code is not: 0 when a rule matches, 1 when none does.
#[cfg(windows)]
fn firewall_status(port: &HubPort) -> FirewallStatus {
    use std::os::windows::process::CommandExt;

    let mut show_rule = Command::new("netsh");
    // `raw_arg` keeps netsh's own `name="..."` quoting; Rust's default quoting would escape it.
    show_rule
        .args(["advfirewall", "firewall", "show", "rule"])
        .raw_arg(format!("name=\"{}\"", port.rule_name))
        .arg("dir=in");
    match run(&mut show_rule) {
        Some(out) if out.status.success() => return FirewallStatus::Allowed,
        Some(_) => {}
        None => return FirewallStatus::Unknown,
    }
    match run(Command::new("netsh").args(["advfirewall", "show", "currentprofile", "state"])) {
        Some(out) if out.status.success() => windows_missing_rule_status(&String::from_utf8_lossy(&out.stdout)),
        _ => FirewallStatus::Unknown,
    }
}

/// The rule is missing: it only matters if the active profile's firewall is on. Output looks like
/// `State                                 ON`. Anything unparseable (a translated label) is `Unknown`.
#[cfg(any(windows, test))]
fn windows_missing_rule_status(profile_state: &str) -> FirewallStatus {
    let states: Vec<&str> = profile_state
        .lines()
        .filter_map(|line| {
            let mut words = line.split_whitespace();
            match (words.next(), words.last()) {
                (Some("State"), Some(state)) => Some(state),
                _ => None,
            }
        })
        .collect();
    if states.iter().any(|s| s.eq_ignore_ascii_case("ON")) {
        FirewallStatus::Blocked
    } else if !states.is_empty() && states.iter().all(|s| s.eq_ignore_ascii_case("OFF")) {
        FirewallStatus::Off
    } else {
        FirewallStatus::Unknown
    }
}

/// firewalld first (it answers non-root queries), then ufw, the Ubuntu default. ufw's on/off switch
/// is world-readable but its rules are root-only, so a Hub running as a normal user may get
/// `Unknown` there; the `.deb` post-install step already opened the ports as root.
#[cfg(target_os = "linux")]
fn firewall_status(port: &HubPort) -> FirewallStatus {
    let firewalld_running =
        run(Command::new("firewall-cmd").arg("--state")).is_some_and(|out| out.status.success());
    if firewalld_running {
        let spec = format!("{}/{}", port.port, port.protocol.as_str());
        return match run(Command::new("firewall-cmd").args(["--query-port", &spec])) {
            Some(out) if out.status.success() => FirewallStatus::Allowed,
            Some(_) => FirewallStatus::Blocked,
            None => FirewallStatus::Unknown,
        };
    }
    match std::fs::read_to_string("/etc/ufw/ufw.conf") {
        Ok(conf) if ufw_enabled(&conf) => match std::fs::read_to_string("/etc/ufw/user.rules") {
            Ok(rules) if ufw_allows(&rules, port) => FirewallStatus::Allowed,
            Ok(_) => FirewallStatus::Blocked,
            Err(_) => FirewallStatus::Unknown,
        },
        // ufw installed but switched off, or neither firewall present.
        _ => FirewallStatus::Off,
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
fn firewall_status(_port: &HubPort) -> FirewallStatus {
    FirewallStatus::Unknown
}

/// `/etc/ufw/ufw.conf` holds `ENABLED=yes` once `ufw enable` has run.
#[cfg(any(target_os = "linux", test))]
fn ufw_enabled(conf: &str) -> bool {
    conf.lines().any(|line| line.trim().eq_ignore_ascii_case("ENABLED=yes"))
}

/// `ufw allow 8080/tcp` writes `### tuple ### allow tcp 8080 0.0.0.0/0 any 0.0.0.0/0 in` to
/// `/etc/ufw/user.rules`; the port field may also be a comma list (`8080,8081`).
#[cfg(any(target_os = "linux", test))]
fn ufw_allows(rules: &str, port: &HubPort) -> bool {
    let wanted = port.port.to_string();
    rules.lines().any(|line| {
        let Some(tuple) = line.strip_prefix("### tuple ###") else { return false };
        let fields: Vec<&str> = tuple.split_whitespace().collect();
        matches!(fields.as_slice(), ["allow", proto, ports, ..]
            if (*proto == port.protocol.as_str() || *proto == "any")
                && ports.split(',').any(|p| p == wanted))
    })
}

#[cfg(test)]
mod tests {
    use std::net::TcpListener;

    use super::*;

    fn tcp(port: u16) -> HubPort {
        HubPort { protocol: Protocol::Tcp, port, rule_name: "test" }
    }

    #[test]
    fn listening_sees_an_open_tcp_socket_and_its_absence() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        assert_eq!(listening(&tcp(port)), Some(true));
        drop(listener);
        assert_eq!(listening(&tcp(port)), Some(false));
    }

    #[test]
    fn udp_beacon_port_has_no_listening_state() {
        assert_eq!(listening(&HUB_PORTS[2]), None);
    }

    #[test]
    fn reachable_needs_a_listener_and_an_open_firewall() {
        let health = |listening, firewall| PortHealth { port: HUB_PORTS[0], listening, firewall };
        assert!(health(Some(true), FirewallStatus::Allowed).reachable());
        assert!(health(Some(true), FirewallStatus::Off).reachable());
        assert!(health(None, FirewallStatus::Allowed).reachable());
        assert!(!health(Some(false), FirewallStatus::Allowed).reachable());
        assert!(!health(Some(true), FirewallStatus::Blocked).reachable());
        assert!(!health(Some(true), FirewallStatus::Unknown).reachable());
    }

    #[test]
    fn check_reports_every_hub_port_in_order() {
        let ports: Vec<HubPort> = check().iter().map(|h| h.port).collect();
        assert_eq!(ports, HUB_PORTS);
    }

    #[test]
    fn windows_profile_state_decides_blocked_off_or_unknown() {
        let on = "Public Profile Settings: \n------\nState                                 ON\nOk.\n";
        let off = "Private Profile Settings: \n------\nState                                 OFF\nOk.\n";
        assert_eq!(windows_missing_rule_status(on), FirewallStatus::Blocked);
        assert_eq!(windows_missing_rule_status(off), FirewallStatus::Off);
        assert_eq!(windows_missing_rule_status(&format!("{off}{on}")), FirewallStatus::Blocked);
        assert_eq!(windows_missing_rule_status("Estado    ACTIVADO\n"), FirewallStatus::Unknown);
    }

    #[test]
    fn ufw_conf_switch() {
        assert!(ufw_enabled("# comment\nENABLED=yes\nLOGLEVEL=low\n"));
        assert!(!ufw_enabled("ENABLED=no\n"));
    }

    #[test]
    fn ufw_rules_match_protocol_and_port() {
        let rules = "### tuple ### allow tcp 8080 0.0.0.0/0 any 0.0.0.0/0 in\n\
                     -A ufw-user-input -p tcp --dport 8080 -j ACCEPT\n\
                     ### tuple ### allow udp 8888 0.0.0.0/0 any 0.0.0.0/0 in\n\
                     ### tuple ### deny tcp 8081 0.0.0.0/0 any 0.0.0.0/0 in\n";
        assert!(ufw_allows(rules, &HUB_PORTS[0]));
        assert!(!ufw_allows(rules, &HUB_PORTS[1]), "a deny rule is not an allow");
        assert!(ufw_allows(rules, &HUB_PORTS[2]));
        assert!(!ufw_allows(rules, &HubPort { protocol: Protocol::Udp, ..HUB_PORTS[0] }));
        assert!(ufw_allows("### tuple ### allow any 8080,8081 0.0.0.0/0 any 0.0.0.0/0 in\n", &HUB_PORTS[1]));
    }
}
