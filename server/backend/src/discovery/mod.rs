use std::net::IpAddr;

/// The IPv4 address of the adapter that holds the default route: the one devices on the
/// classroom router can reach. `None` when the PC is on no network. Step 1.1 adds mDNS, the
/// UDP beacon, and a choice when several adapters qualify (VirtualBox, Hyper-V, VPN).
pub fn lan_ip() -> Option<IpAddr> {
    local_ip_address::local_ip().ok().filter(|ip| !ip.is_loopback() && !ip.is_unspecified())
}
