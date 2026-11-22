//! Aggregate, process-local counters. No user identifiers, paths or request data.
use std::collections::VecDeque;
use std::sync::{atomic::{AtomicU64, Ordering}, Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};
use serde::Serialize;

#[derive(Default)]
struct Bucket { second: u64, requests: u64, failures: u64, total_ms: f64, slowest_ms: f64 }

pub struct Workload {
    started: Instant,
    requests: Mutex<VecDeque<Bucket>>,
    live: [AtomicU64; 5],
    documents_finished: AtomicU64,
    documents_failed: AtomicU64,
}
impl Default for Workload {
    fn default() -> Self {
        Self { started: Instant::now(), requests: Mutex::default(), live: std::array::from_fn(|_| AtomicU64::new(0)), documents_finished: AtomicU64::new(0), documents_failed: AtomicU64::new(0) }
    }
}

#[derive(Clone, Copy)]
pub enum Task { Upload = 0, Download = 1, Video = 2, DocumentWaiting = 3, DocumentProcessing = 4 }

pub struct TaskGuard { owner: Arc<Workload>, task: Task }
impl TaskGuard {
    pub fn processing(mut self) -> Self {
        self.owner.live[self.task as usize].fetch_sub(1, Ordering::Relaxed);
        self.task = Task::DocumentProcessing;
        self.owner.live[self.task as usize].fetch_add(1, Ordering::Relaxed);
        self
    }
}
impl Drop for TaskGuard {
    fn drop(&mut self) { self.owner.live[self.task as usize].fetch_sub(1, Ordering::Relaxed); }
}

#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub requests_per_second: f64,
    pub failed_per_second: f64,
    pub requests_last_minute: u64,
    pub failed_last_minute: u64,
    pub average_response_ms: Option<f64>,
    pub slowest_response_ms: Option<f64>,
    pub uploads: u64, pub downloads: u64, pub video_streams: u64,
    pub documents_waiting: u64, pub documents_processing: u64,
    pub documents_finished: u64, pub documents_failed: u64,
}

impl Workload {
    pub fn task(self: &Arc<Self>, task: Task) -> TaskGuard {
        self.live[task as usize].fetch_add(1, Ordering::Relaxed);
        TaskGuard { owner: Arc::clone(self), task }
    }
    pub fn document_result(&self, readable: bool) {
        if readable { &self.documents_finished } else { &self.documents_failed }.fetch_add(1, Ordering::Relaxed);
    }
    pub fn request(&self, status: u16, elapsed: Duration) {
        self.request_at(self.started.elapsed().as_secs(), status, elapsed.as_secs_f64() * 1000.0);
    }
    fn request_at(&self, second: u64, status: u16, ms: f64) {
        let mut buckets = self.requests.lock().unwrap_or_else(PoisonError::into_inner);
        let second = buckets.back().map_or(second, |b| second.max(b.second));
        while buckets.front().is_some_and(|b| b.second.saturating_add(60) <= second) { buckets.pop_front(); }
        if buckets.back().is_none_or(|b| b.second != second) { buckets.push_back(Bucket { second, ..Bucket::default() }); }
        if let Some(bucket) = buckets.back_mut() {
            bucket.requests += 1;
            bucket.failures += u64::from(status >= 400);
            bucket.total_ms += ms;
            bucket.slowest_ms = bucket.slowest_ms.max(ms);
        }
    }
    pub fn snapshot(&self) -> Snapshot { self.snapshot_at(self.started.elapsed()) }
    fn snapshot_at(&self, elapsed: Duration) -> Snapshot {
        let mut buckets = self.requests.lock().unwrap_or_else(PoisonError::into_inner);
        while buckets.front().is_some_and(|b| b.second.saturating_add(60) <= elapsed.as_secs()) { buckets.pop_front(); }
        let requests: u64 = buckets.iter().map(|b| b.requests).sum();
        Snapshot {
            requests_per_second: requests as f64 / elapsed.as_secs_f64().clamp(1.0, 60.0),
            failed_per_second: buckets.iter().map(|b| b.failures).sum::<u64>() as f64 / elapsed.as_secs_f64().clamp(1.0, 60.0),
            requests_last_minute: requests,
            failed_last_minute: buckets.iter().map(|b| b.failures).sum(),
            average_response_ms: (requests > 0).then(|| buckets.iter().map(|b| b.total_ms).sum::<f64>() / requests as f64),
            slowest_response_ms: (requests > 0).then(|| buckets.iter().map(|b| b.slowest_ms).fold(0.0, f64::max)),
            uploads: self.live[Task::Upload as usize].load(Ordering::Relaxed),
            downloads: self.live[Task::Download as usize].load(Ordering::Relaxed),
            video_streams: self.live[Task::Video as usize].load(Ordering::Relaxed),
            documents_waiting: self.live[Task::DocumentWaiting as usize].load(Ordering::Relaxed),
            documents_processing: self.live[Task::DocumentProcessing as usize].load(Ordering::Relaxed),
            documents_finished: self.documents_finished.load(Ordering::Relaxed),
            documents_failed: self.documents_failed.load(Ordering::Relaxed),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn minute_window_keeps_all_counts_in_bounded_second_buckets() {
        let metrics = Workload::default();
        for _ in 0..10000 { metrics.request_at(0, 200, 10.0); }
        metrics.request_at(30, 500, 110.0);
        let now = metrics.snapshot_at(Duration::from_secs(30));
        assert_eq!(now.requests_last_minute, 10001);
        assert_eq!(now.failed_last_minute, 1);
        assert_eq!(now.slowest_response_ms, Some(110.0));
        assert_eq!(metrics.requests.lock().unwrap().len(), 2);
        assert_eq!(metrics.snapshot_at(Duration::from_secs(60)).requests_last_minute, 1);
        assert!(metrics.snapshot_at(Duration::from_secs(90)).average_response_ms.is_none());
    }
    #[test]
    fn cancellation_and_processing_release_live_counters() {
        let metrics = Arc::new(Workload::default());
        let waiting = metrics.task(Task::DocumentWaiting);
        assert_eq!(metrics.snapshot().documents_waiting, 1);
        let running = waiting.processing();
        assert_eq!(metrics.snapshot().documents_waiting, 0);
        assert_eq!(metrics.snapshot().documents_processing, 1);
        drop(running);
        let upload = metrics.task(Task::Upload);
        assert_eq!(metrics.snapshot().uploads, 1);
        drop(upload);
        assert_eq!(metrics.snapshot().uploads, 0);
        assert_eq!(metrics.snapshot().documents_processing, 0);
        metrics.document_result(false);
        assert_eq!(metrics.snapshot().documents_failed, 1);
    }
}
