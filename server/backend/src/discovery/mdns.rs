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

/// `libmdns` asserts that every DNS label is under 63 bytes, and panics otherwise.
const MAX_LABEL_BYTES: usize = 62;

/// The name announced over mDNS, which phones show in their Hub list (#167). `libmdns` starts a new
/// DNS label at every dot, so "L.A.R.A Hub" reached phones as "L"; the dots are dropped instead
/// ("LARA Hub"). The beacon and everything else keep the full name. The result is also cut to one
/// label's length on a character boundary, because a longer label panics `libmdns`.
fn announced_name(name: &str) -> String {
    let mut announced: String = name.chars().filter(|&c| c != '.').collect();
    if announced.len() > MAX_LABEL_BYTES {
        let mut end = MAX_LABEL_BYTES;
        while !announced.is_char_boundary(end) {
            end -= 1;
        }
        announced.truncate(end);
    }
    announced
}

/// Keeps the advertisement alive. Dropping it (on Hub shutdown) unregisters the service and stops
/// the responder, so a restarted Hub does not leave a stale record on the network.
pub struct MdnsResponder {
    // Field order matters: `_service` (the registration) is dropped before `_responder` (its host).
    _service: libmdns::Service,
    _responder: libmdns::Responder,
}

/// Starts advertising `_lara._tcp.local` on `http_port`, named `announced_name(name)`. Returns
/// `None` instead of an error: if mDNS cannot start (an unusual hostname, a busy :5353) the UDP
/// beacon and the manual IP box still let clients connect, so the Hub keeps running.
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
    let announced = announced_name(name);
    let service = responder.register(SERVICE_TYPE, &announced, http_port, no_txt);
    tracing::info!("mDNS advertising {SERVICE_TYPE}.local on port {http_port} as {announced:?} (Hub name {name:?})");
    Some(MdnsResponder { _service: service, _responder: responder })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// #167: `libmdns` splits the name at every dot, so phones listed "L.A.R.A Hub" as "L". The
    /// announced name drops the dots; the beacon and everything else keep the full name.
    #[test]
    fn the_announced_name_has_no_dots() {
        assert_eq!(announced_name("L.A.R.A Hub"), "LARA Hub");
        assert_eq!(announced_name("Grade 4 - Science"), "Grade 4 - Science");
    }

    /// `libmdns` asserts that each label is under 63 bytes while it registers the name, so a longer
    /// room name panicked inside `serve()` and the Hub never started. The cut never splits a
    /// multi-byte character.
    #[test]
    fn a_long_name_is_cut_below_the_label_limit() {
        let name = "Paaralang Elementarya ng San Isidro, Silid ng Agham at Teknolohiya, Baitang Apat";
        let announced = announced_name(name);
        assert!(announced.len() <= MAX_LABEL_BYTES, "{} bytes", announced.len());
        assert!(name.starts_with(&announced));

        let with_enye = format!("{}ñ ng Agham", "a".repeat(MAX_LABEL_BYTES - 1));
        let announced = announced_name(&with_enye);
        assert_eq!(announced, "a".repeat(MAX_LABEL_BYTES - 1));
    }
}
