use std::net::IpAddr;

pub mod mdns;
pub mod udp;

/// The IPv4 address of the adapter that holds the default route: the one devices on the
/// classroom router can reach. `None` when the PC is on no network. mDNS (`mdns`) and the UDP
/// beacon (`udp`) both advertise this one address so clients never get an unreachable VirtualBox,
/// Hyper-V or VPN address.
pub fn lan_ip() -> Option<IpAddr> {
    local_ip_address::local_ip().ok().filter(|ip| !ip.is_loopback() && !ip.is_unspecified())
}
