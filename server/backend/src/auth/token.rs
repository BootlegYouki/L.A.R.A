//! Session tokens: opaque, random, and stored only as a SHA-256 (`sessions.token_hash`), so a
//! copied database cannot be used to sign in.

use std::fmt::Write;

use sha2::{Digest, Sha256};

/// 12 hours, as the mock hub's sessions.
pub const SESSION_TTL_MS: i64 = 12 * 60 * 60 * 1000;

/// 32 bytes from the operating system's random source, as 64 lowercase hex characters.
pub fn new_token() -> String {
    let mut raw = [0u8; 32];
    // Only fails when the OS has no random source at all; the Hub cannot issue sessions then.
    getrandom::fill(&mut raw).expect("the operating system's random source failed");
    hex(&raw)
}

pub fn token_hash(token: &str) -> String {
    hex(&Sha256::digest(token.as_bytes()))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_is_64_lowercase_hex_and_unique() {
        let a = new_token();
        assert_eq!(a.len(), 64);
        assert!(a.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)), "{a}");
        assert_ne!(a, new_token());
    }

    #[test]
    fn token_hash_is_sha256_hex() {
        assert_eq!(token_hash("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    #[test]
    fn sessions_last_twelve_hours() {
        assert_eq!(SESSION_TTL_MS, 12 * 60 * 60 * 1000);
    }
}
