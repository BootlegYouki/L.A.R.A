//! mDNS / DNS-SD responder (`rules/networking-and-lan.md` §2).
//!
//! The Hub advertises `_lara._tcp.local` on the HTTP port so Bonjour/NSD-style discovery finds it
//! without the UDP beacon. `libmdns` is a pure-Rust responder, so this adds no system daemon and no
//! internet dependency. It runs on its own background thread; we just hold the guard.
//!
//! DNS answers are limited to the one LAN address (see `lan_ip`) so clients never receive a
//! VirtualBox, Hyper-V or VPN address they cannot reach.

use std::net::IpAddr;

/// Fixed by the rules file. The leading-underscore form is what `libmdns` expects (it appends
/// `.local`).
const SERVICE_TYPE: &str = "_lara._tcp";

/// Keeps the advertisement alive. Dropping it (on Hub shutdown) unregisters the service and stops
/// the responder, so a restarted Hub does not leave a stale record on the network.
pub struct MdnsResponder {
    // Field order matters: `_service` (the registration) is dropped before `_responder` (its host).
    _service: libmdns::Service,
    _responder: libmdns::Responder,
}

/// Starts advertising `_lara._tcp.local` on `http_port`, named `name`. Returns `None` instead of an
/// error: if mDNS cannot start (an unusual hostname, a busy :5353) the UDP beacon and the manual IP
/// box still let clients connect, so the Hub keeps running.
///
/// `lan_ip` is the single address to advertise. Pass `None` on a machine with no classroom network
/// and the responder advertises whatever interfaces it finds (harmless; clients still fall back to
/// the UDP beacon and the manual IP box).
pub fn start(name: &str, http_port: u16, lan_ip: Option<IpAddr>) -> Option<MdnsResponder> {
    // An empty list lets libmdns advertise every interface it finds; one address pins it to the
    // reachable LAN adapter. `new_with_ip_list` returns an error (never panics) if the hostname is
    // not valid unicode, which we turn into "mDNS off" rather than a failed startup.
    let allowed_ips = lan_ip.into_iter().collect::<Vec<_>>();
    let responder = match libmdns::Responder::new_with_ip_list(allowed_ips) {
        Ok(responder) => responder,
        Err(e) => {
            tracing::warn!(error = %e, "mDNS disabled; UDP beacon and manual IP still work");
            return None;
        }
    };

    // TXT records are optional for us; clients read host/port from the resolved SRV/A records.
    let no_txt: &[&str] = &[];
    let service = responder.register(SERVICE_TYPE, name, http_port, no_txt);
    tracing::info!("mDNS advertising {SERVICE_TYPE}.local on port {http_port} as {name:?}");
    Some(MdnsResponder { _service: service, _responder: responder })
}
