//! 4-digit PINs, hashed with argon2id (19 MiB, 2 passes: the crate's defaults, the OWASP
//! minimum). Hashing runs on the blocking pool so it never stalls the async runtime, and a
//! semaphore caps how many run at once: 40 learners signing in together would otherwise need
//! 40 × 19 MiB on a 4 GB Hub PC that also holds the AI model.

use std::num::NonZero;

use argon2::password_hash::phc::PasswordHash;
use argon2::password_hash::{PasswordHasher, PasswordVerifier};
use argon2::Argon2;
use axum::http::StatusCode;
use tokio::sync::Semaphore;

use crate::error::{ApiError, ErrorCode};

/// Exactly four ASCII digits, as the contract's `^[0-9]{4}$`.
pub fn is_valid_pin(pin: &str) -> bool {
    pin.len() == 4 && pin.bytes().all(|b| b.is_ascii_digit())
}

pub struct PinHasher {
    permits: Semaphore,
}

impl Default for PinHasher {
    /// One hash per CPU core at a time.
    fn default() -> Self {
        Self::with_permits(std::thread::available_parallelism().map_or(1, NonZero::get))
    }
}

impl PinHasher {
    pub fn with_permits(permits: usize) -> Self {
        Self { permits: Semaphore::new(permits.max(1)) }
    }

    /// The PHC string stored in `users.pin_hash` (`$argon2id$v=19$...`, salt included).
    pub async fn hash(&self, pin: &str) -> Result<String, ApiError> {
        let pin = pin.to_owned();
        self.run(move || Argon2::default().hash_password(pin.as_bytes()).map(|h| h.to_string()))
            .await?
            .map_err(|_| internal("PIN hashing failed"))
    }

    /// `false` for a wrong PIN and for a stored hash that does not parse.
    pub async fn verify(&self, pin: &str, pin_hash: &str) -> Result<bool, ApiError> {
        let (pin, pin_hash) = (pin.to_owned(), pin_hash.to_owned());
        self.run(move || match PasswordHash::new(&pin_hash) {
            Ok(parsed) => Argon2::default().verify_password(pin.as_bytes(), &parsed).is_ok(),
            Err(_) => {
                tracing::warn!("a stored PIN hash does not parse; treating it as a wrong PIN");
                false
            }
        })
        .await
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce() -> T + Send + 'static) -> Result<T, ApiError> {
        // The semaphore is never closed, so acquire cannot fail.
        let _permit = self.permits.acquire().await.map_err(|_| internal("PIN hashing stopped"))?;
        tokio::task::spawn_blocking(work).await.map_err(|_| internal("PIN hashing failed"))
    }
}

fn internal(message: &str) -> ApiError {
    ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, ErrorCode::Internal, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_pins() {
        for pin in ["0000", "1234", "9999"] {
            assert!(is_valid_pin(pin), "{pin:?}");
        }
        for pin in ["", "123", "12345", "12a4", "١٢٣٤", " 1234", "1234 "] {
            assert!(!is_valid_pin(pin), "{pin:?}");
        }
    }

    #[tokio::test]
    async fn hash_is_argon2id_and_verifies() {
        let pins = PinHasher::default();
        let stored = pins.hash("4821").await.unwrap();
        assert!(stored.starts_with("$argon2id$v=19$m=19456,t=2,p=1$"), "{stored}");
        assert!(pins.verify("4821", &stored).await.unwrap());
        assert!(!pins.verify("4822", &stored).await.unwrap());
    }

    #[tokio::test]
    async fn two_hashes_of_one_pin_differ() {
        let pins = PinHasher::default();
        assert_ne!(pins.hash("1234").await.unwrap(), pins.hash("1234").await.unwrap());
    }

    #[tokio::test]
    async fn verify_against_garbage_is_false() {
        let pins = PinHasher::default();
        for stored in ["", "1234", "$argon2id$broken"] {
            assert!(!pins.verify("1234", stored).await.unwrap(), "{stored:?}");
        }
    }

    #[tokio::test]
    async fn sixteen_concurrent_hashes_all_finish() {
        let pins = PinHasher::with_permits(2);
        let results = futures_util::future::join_all((0..16).map(|_| pins.hash("1234"))).await;
        assert!(results.iter().all(Result::is_ok));
    }
}
