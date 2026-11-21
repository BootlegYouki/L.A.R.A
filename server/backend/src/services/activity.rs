use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;
use sqlx::SqlitePool;
use tokio::sync::{mpsc, watch};
use tokio::task::JoinHandle;

use crate::clock::Clock;

macro_rules! catalog {
    ($name:ident { $($variant:ident => $value:literal),+ $(,)? }) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        pub enum $name { $($variant),+ }
        impl $name {
            pub fn as_str(self) -> &'static str { match self { $(Self::$variant => $value),+ } }
        }
    };
}
catalog!(Code {
    HubStarted => "HUB_STARTED", HubStopping => "HUB_STOPPING", UncleanRestart => "UNCLEAN_RESTART",
    ServiceUnavailable => "SERVICE_UNAVAILABLE", ServiceRecovered => "SERVICE_RECOVERED", ConnectionProblem => "CONNECTION_PROBLEM",
    DocumentUploaded => "DOCUMENT_UPLOADED", DocumentUploadFailed => "DOCUMENT_UPLOAD_FAILED",
    DocumentProcessed => "DOCUMENT_PROCESSED", DocumentProcessingFailed => "DOCUMENT_PROCESSING_FAILED",
    QuizStarted => "QUIZ_STARTED", QuizStartFailed => "QUIZ_START_FAILED", QuizClosed => "QUIZ_CLOSED", QuizCloseFailed => "QUIZ_CLOSE_FAILED",
    AccountCreated => "ACCOUNT_CREATED", AccountCreateFailed => "ACCOUNT_CREATE_FAILED", PinReset => "PIN_RESET", PinResetFailed => "PIN_RESET_FAILED", LogGap => "LOG_GAP"
});
catalog!(Service { Hub => "HUB", Database => "DATABASE", Http => "HTTP", Websocket => "WEBSOCKET", Discovery => "DISCOVERY", Storage => "STORAGE", Documents => "DOCUMENTS", Quizzes => "QUIZZES", Accounts => "ACCOUNTS", ActivityLog => "ACTIVITY_LOG" });
catalog!(Reason { PortInUse => "PORT_IN_USE", NetworkUnavailable => "NETWORK_UNAVAILABLE", Timeout => "TIMEOUT", StorageUnavailable => "STORAGE_UNAVAILABLE", DatabaseUnavailable => "DATABASE_UNAVAILABLE", UnreadableDocument => "UNREADABLE_DOCUMENT", NoTextLayer => "NO_TEXT_LAYER", SizeLimit => "SIZE_LIMIT", InvalidInput => "INVALID_INPUT", NotAllowed => "NOT_ALLOWED", Conflict => "CONFLICT", QueueFull => "QUEUE_FULL", WriteFailed => "WRITE_FAILED", RepeatedEvent => "REPEATED_EVENT", UncleanExit => "UNCLEAN_EXIT", InternalFailure => "INTERNAL_FAILURE" });

#[derive(Clone, Debug)]
pub struct Event {
    pub code: Code,
    pub service: Service,
    pub reason: Option<Reason>,
    pub item_count: Option<i64>,
    pub duration_ms: Option<i64>,
    pub occurred_at: i64,
}
impl Event {
    pub fn new(code: Code, service: Service) -> Self {
        Self { code, service, reason: None, item_count: None, duration_ms: None, occurred_at: 0 }
    }
    pub fn reason(mut self, reason: Reason) -> Self {
        self.reason = Some(reason);
        self
    }
    pub fn measurement(mut self, count: usize, duration: Duration) -> Self {
        self.item_count = Some(count.min(1_000_000) as i64);
        self.duration_ms = Some(duration.as_millis().min(86_400_000) as i64);
        self
    }
    pub fn status(&self) -> (&'static str, &'static str) {
        match self.code {
            Code::UncleanRestart | Code::ConnectionProblem | Code::DocumentProcessingFailed | Code::LogGap => {
                ("WARNING", "DEGRADED")
            }
            Code::ServiceUnavailable | Code::DocumentUploadFailed => ("ERROR", "FAILED"),
            Code::AccountCreateFailed | Code::PinResetFailed | Code::QuizStartFailed | Code::QuizCloseFailed => {
                ("WARNING", "FAILED")
            }
            _ => ("INFO", "SUCCEEDED"),
        }
    }
}

pub struct Recorder {
    sender: mpsc::Sender<Event>,
    stop: watch::Sender<bool>,
    task: Mutex<Option<JoinHandle<()>>>,
    health: AtomicU8,
    dropped: AtomicU64,
    write_failed: AtomicU64,
    repeated: AtomicU64,
    recent: Mutex<HashMap<(Code, Service, Option<Reason>), Instant>>,
    clock: Arc<dyn Clock>,
}
impl Recorder {
    pub async fn start(db: SqlitePool, clock: Arc<dyn Clock>) -> Arc<Self> {
        let (sender, receiver) = mpsc::channel(256);
        let (stop, stopping) = watch::channel(false);
        let recorder = Arc::new(Self {
            sender,
            stop,
            task: Mutex::new(None),
            health: AtomicU8::new(0),
            dropped: AtomicU64::new(0),
            write_failed: AtomicU64::new(0),
            repeated: AtomicU64::new(0),
            recent: Mutex::new(HashMap::new()),
            clock,
        });
        let initialized = initialize(&db).await;
        match initialized {
            Ok(false) => recorder.record(Event::new(Code::UncleanRestart, Service::Hub).reason(Reason::UncleanExit)),
            Ok(true) => {}
            Err(_) => recorder.health.store(2, Ordering::Relaxed),
        }
        let task = tokio::spawn(run(Arc::clone(&recorder), db, receiver, stopping));
        if let Ok(mut slot) = recorder.task.lock() {
            *slot = Some(task);
        }
        recorder
    }

    pub fn record(&self, mut event: Event) {
        event.occurred_at = self.clock.now_ms().clamp(0, 9_007_199_254_740_991);
        if event.status().0 != "INFO" && event.code != Code::LogGap {
            if let Ok(mut recent) = self.recent.lock() {
                let key = (event.code, event.service, event.reason);
                let now = Instant::now();
                if recent.get(&key).is_some_and(|previous| now.duration_since(*previous) < Duration::from_secs(30)) {
                    self.repeated.fetch_add(1, Ordering::Relaxed);
                    return;
                }
                recent.insert(key, now);
            }
        }
        if self.sender.try_send(event).is_err() {
            self.dropped.fetch_add(1, Ordering::Relaxed);
            self.health.store(1, Ordering::Relaxed);
        }
    }

    pub fn state(&self) -> &'static str {
        match self.health.load(Ordering::Relaxed) {
            0 => "ACTIVE",
            1 => "DEGRADED",
            _ => "UNAVAILABLE",
        }
    }
    pub fn pending_drops(&self) -> i64 {
        self.dropped
            .load(Ordering::Relaxed)
            .saturating_add(self.repeated.load(Ordering::Relaxed))
            .saturating_add(self.write_failed.load(Ordering::Relaxed))
            .min(9_007_199_254_740_991) as i64
    }

    pub async fn shutdown(&self) {
        self.record(Event::new(Code::HubStopping, Service::Hub));
        let _ = self.stop.send(true);
        let task = self.task.lock().ok().and_then(|mut slot| slot.take());
        if let Some(mut task) = task {
            if tokio::time::timeout(Duration::from_secs(2), &mut task).await.is_err() {
                task.abort();
                let _ = task.await;
            }
        }
    }
}

async fn initialize(db: &SqlitePool) -> Result<bool, sqlx::Error> {
    let mut tx = crate::db::begin_immediate(db).await?;
    sqlx::query("INSERT OR IGNORE INTO hub_activity_state (singleton, history_id) VALUES (1, ?)")
        .bind(uuid::Uuid::new_v4().to_string())
        .execute(&mut *tx)
        .await?;
    let clean: i64 = sqlx::query_scalar("SELECT clean_shutdown FROM hub_activity_state WHERE singleton=1")
        .fetch_one(&mut *tx)
        .await?;
    sqlx::query("UPDATE hub_activity_state SET clean_shutdown=0 WHERE singleton=1").execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(clean == 1)
}

async fn run(
    recorder: Arc<Recorder>,
    db: SqlitePool,
    mut receiver: mpsc::Receiver<Event>,
    mut stop: watch::Receiver<bool>,
) {
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    let mut batch = Vec::with_capacity(100);
    let mut deferred = None;
    let mut backoff = 1;
    let mut last_maintenance = Instant::now().checked_sub(Duration::from_secs(60)).unwrap_or_else(Instant::now);
    loop {
        let shutting_down = tokio::select! {
            biased;
            _ = stop.changed() => true,
            event = receiver.recv(), if batch.len() < 100 => {
                if let Some(event) = event { batch.push(event); }
                if batch.len() < 100 { continue; }
                false
            },
            _ = tick.tick() => false,
        };
        if shutting_down {
            receiver.close();
        }
        loop {
            while batch.len() < 100 {
                if let Some(event) = deferred.take() {
                    batch.push(event);
                    continue;
                }
                match receiver.try_recv() {
                    Ok(event) => batch.push(event),
                    Err(_) => break,
                }
            }
            let drops = recorder.dropped.swap(0, Ordering::Relaxed);
            let repeats = recorder.repeated.swap(0, Ordering::Relaxed);
            let writes = recorder.write_failed.swap(0, Ordering::Relaxed);
            let total_loss = drops.saturating_add(repeats).saturating_add(writes);
            let loss = total_loss.min(1_000_000);
            if total_loss > loss {
                recorder.write_failed.fetch_add(total_loss - loss, Ordering::Relaxed);
            }
            if loss > 0 {
                let mut gap = Event::new(Code::LogGap, Service::ActivityLog).reason(if writes > 0 {
                    Reason::WriteFailed
                } else if drops > 0 {
                    Reason::QueueFull
                } else {
                    Reason::RepeatedEvent
                });
                gap.occurred_at = recorder.clock.now_ms();
                gap.item_count = Some(loss.min(1_000_000) as i64);
                // Reserve one entry for the gap while keeping transactions within 100 events.
                if batch.len() == 100 {
                    deferred = batch.pop();
                }
                batch.push(gap);
            }
            if batch.is_empty() && last_maintenance.elapsed() < Duration::from_secs(60) && !shutting_down {
                break;
            }
            match persist(&db, &batch, recorder.clock.now_ms(), loss).await {
                Ok(()) => {
                    if recorder.health.swap(0, Ordering::Relaxed) == 2 && !shutting_down {
                        recorder.record(Event::new(Code::ServiceRecovered, Service::ActivityLog));
                    }
                    backoff = 1;
                    last_maintenance = Instant::now();
                }
                Err(_) => {
                    let business_events = batch.iter().filter(|event| event.code != Code::LogGap).count() as u64;
                    recorder.write_failed.fetch_add(business_events.saturating_add(loss), Ordering::Relaxed);
                    recorder.health.store(2, Ordering::Relaxed);
                    batch.clear();
                    if shutting_down {
                        return;
                    }
                    tokio::time::sleep(Duration::from_secs(backoff)).await;
                    backoff = (backoff * 2).min(5);
                    break;
                }
            }
            batch.clear();
            if !shutting_down || (receiver.is_empty() && deferred.is_none() && recorder.pending_drops() == 0) {
                break;
            }
        }
        if shutting_down {
            if recorder.pending_drops() == 0 {
                let _ =
                    sqlx::query("UPDATE hub_activity_state SET clean_shutdown=1 WHERE singleton=1").execute(&db).await;
            }
            return;
        }
    }
}

async fn persist(db: &SqlitePool, batch: &[Event], now: i64, dropped: u64) -> Result<(), sqlx::Error> {
    let mut tx = crate::db::begin_immediate(db).await?;
    sqlx::query("INSERT OR IGNORE INTO hub_activity_state (singleton,history_id,clean_shutdown) VALUES (1,?,0)")
        .bind(uuid::Uuid::new_v4().to_string())
        .execute(&mut *tx)
        .await?;
    for event in batch {
        let (severity, result) = event.status();
        sqlx::query("INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service,reason,item_count,duration_ms) VALUES (?,?,?,?,?,?,?,?)")
            .bind(event.occurred_at).bind(event.code.as_str()).bind(severity).bind(result).bind(event.service.as_str())
            .bind(event.reason.map(Reason::as_str)).bind(event.item_count).bind(event.duration_ms).execute(&mut *tx).await?;
    }
    let expired: Vec<i64> = sqlx::query_scalar("DELETE FROM hub_activity_events WHERE occurred_at < ? RETURNING seq")
        .bind(now.saturating_sub(7 * 86_400_000))
        .fetch_all(&mut *tx)
        .await?;
    let capped: Vec<i64> = sqlx::query_scalar("DELETE FROM hub_activity_events WHERE seq IN (SELECT seq FROM hub_activity_events ORDER BY seq DESC LIMIT -1 OFFSET 20000) RETURNING seq").fetch_all(&mut *tx).await?;
    let pruned = expired.into_iter().chain(capped).max().unwrap_or(0);
    sqlx::query("UPDATE hub_activity_state SET pruned_through_seq=MAX(pruned_through_seq,?), dropped_total=MIN(9007199254740991,dropped_total+?) WHERE singleton=1")
        .bind(pruned).bind(dropped.min(9_007_199_254_740_991) as i64).execute(&mut *tx).await?;
    tx.commit().await
}

#[derive(Serialize)]
pub struct Details {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item_count: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
}
#[derive(Serialize)]
pub struct Entry {
    pub seq: i64,
    pub occurred_at: i64,
    pub event_code: String,
    pub severity: String,
    pub result: String,
    pub service: String,
    pub details: Details,
}
