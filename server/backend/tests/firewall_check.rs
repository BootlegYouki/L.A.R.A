//! The installer scripts and the dashboard's port check must name the same rules and ports, or the
//! dashboard would warn about a port the installer did open (or miss one it did not).

use lara_hub::network::firewall_check::{Protocol, HUB_PORTS};

fn installer_file(path: &str) -> String {
    let full = format!("{}/../src-tauri/{path}", env!("CARGO_MANIFEST_DIR"));
    std::fs::read_to_string(&full).unwrap_or_else(|e| panic!("read {full}: {e}"))
}

fn proto(protocol: Protocol) -> (&'static str, &'static str) {
    match protocol {
        Protocol::Tcp => ("TCP", "tcp"),
        Protocol::Udp => ("UDP", "udp"),
    }
}

#[test]
fn windows_installer_adds_and_removes_every_hub_port_rule() {
    let hooks = installer_file("windows/hooks.nsh");
    for port in HUB_PORTS {
        let (netsh_proto, _) = proto(port.protocol);
        let add = format!(
            "advfirewall firewall add rule name=\"{}\" dir=in action=allow protocol={netsh_proto} localport={}",
            port.rule_name, port.port
        );
        let delete = format!("advfirewall firewall delete rule name=\"{}\"", port.rule_name);
        assert!(hooks.contains(&add), "hooks.nsh is missing: {add}");
        assert!(hooks.contains(&delete), "hooks.nsh is missing: {delete}");
    }
    assert_eq!(hooks.matches("firewall add rule").count(), HUB_PORTS.len(), "hooks.nsh opens an extra port");
}

#[test]
fn linux_postinst_opens_every_hub_port_with_ufw_and_firewalld() {
    let postinst = installer_file("linux/postinst.sh");
    for port in HUB_PORTS {
        let (_, spec_proto) = proto(port.protocol);
        let spec = format!("{}/{spec_proto}", port.port);
        assert!(postinst.contains(&format!("ufw allow {spec}")), "postinst.sh: no ufw rule for {spec}");
        assert!(postinst.contains(&format!("--add-port={spec}")), "postinst.sh: no firewalld rule for {spec}");
    }
    assert!(!postinst.contains('\r'), "postinst.sh must use LF line endings for /bin/sh");
}
