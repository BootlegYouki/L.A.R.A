//! The Hub's log lines, for tests that check something happened or did not. `#[tokio::test]` runs on
//! one thread and the Hub's request handlers run on it too, so a subscriber set for that thread sees
//! their logs (not those of `spawn_blocking` threads).

use std::fmt::Debug;
use std::sync::{Arc, Mutex, PoisonError};

use tracing::field::{Field, Visit};
use tracing::subscriber::DefaultGuard;
use tracing::{Event, Subscriber};
use tracing_subscriber::layer::{Context, SubscriberExt};
use tracing_subscriber::Layer;

/// The messages logged on this thread since [`capture`].
#[derive(Clone, Default)]
pub struct Logs(Arc<Mutex<Vec<String>>>);

impl Logs {
    pub fn count(&self, message: &str) -> usize {
        self.messages().iter().filter(|m| m.as_str() == message).count()
    }

    pub fn messages(&self) -> Vec<String> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }
}

/// Keep the guard for as long as logs should be captured.
pub fn capture() -> (Logs, DefaultGuard) {
    let logs = Logs::default();
    let guard = tracing::subscriber::set_default(tracing_subscriber::registry().with(Capture(logs.clone())));
    (logs, guard)
}

struct Capture(Logs);

impl<S: Subscriber> Layer<S> for Capture {
    fn on_event(&self, event: &Event<'_>, _: Context<'_, S>) {
        let mut message = Message::default();
        event.record(&mut message);
        self.0 .0.lock().unwrap_or_else(PoisonError::into_inner).push(message.0);
    }
}

#[derive(Default)]
struct Message(String);

impl Visit for Message {
    fn record_debug(&mut self, field: &Field, value: &dyn Debug) {
        if field.name() == "message" {
            self.0 = format!("{value:?}");
        }
    }
}
