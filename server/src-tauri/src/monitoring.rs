use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use lara_hub::auth::{sessions, Role};
use serde::Serialize;
use sysinfo::{
    DiskRefreshKind, Disks, MemoryRefreshKind, Networks, ProcessRefreshKind, ProcessesToUpdate,
    System,
};

use crate::Hub;

#[derive(Clone, Serialize)]
pub struct Adapter {
    name: String,
    classroom: bool,
    received_per_second: Option<f64>,
    sent_per_second: Option<f64>,
}

#[derive(Clone, Serialize)]
pub struct Snapshot {
    cpu_percent: Option<f32>,
    hub_cpu_percent: Option<f32>,
    ram_used: Option<u64>,
    ram_total: Option<u64>,
    hub_ram: Option<u64>,
    disk_free: Option<u64>,
    disk_total: Option<u64>,
    uptime_seconds: u64,
    adapters: Vec<Adapter>,
}

pub struct Monitor {
    data_dir: PathBuf,
    started: Instant,
    system: Option<System>,
    networks: Option<Networks>,
    previous: HashMap<String, (u64, u64)>,
    sampled: Option<Instant>,
    cached: Option<Snapshot>,
}

impl Monitor {
    pub fn new(data_dir: PathBuf) -> Self {
        Self {
            data_dir,
            started: Instant::now(),
            system: None,
            networks: None,
            previous: HashMap::new(),
            sampled: None,
            cached: None,
        }
    }

    fn sample(&mut self) -> Snapshot {
        if let (Some(at), Some(cached)) = (self.sampled, &self.cached) {
            if at.elapsed() < Duration::from_millis(1500) {
                return cached.clone();
            }
        }
        let now = Instant::now();
        let elapsed = self.sampled.map(|at| now.duration_since(at));
        // First readings and samples after sleep have no valid two-second CPU/rate interval.
        let interval =
            elapsed.filter(|d| *d >= Duration::from_millis(200) && *d <= Duration::from_secs(5));
        let mut snapshot = Snapshot {
            cpu_percent: None,
            hub_cpu_percent: None,
            ram_used: None,
            ram_total: None,
            hub_ram: None,
            disk_free: None,
            disk_total: None,
            uptime_seconds: self.started.elapsed().as_secs(),
            adapters: Vec::new(),
        };
        if self.system.is_none() {
            self.system = System::new().ok();
        }
        if let Some(system) = self.system.as_mut() {
            system.refresh_cpu_usage();
            system.refresh_memory_specifics(MemoryRefreshKind::nothing().with_ram());
            if let Ok(pid) = sysinfo::get_current_pid() {
                system.refresh_processes_specifics(
                    ProcessesToUpdate::Some(&[pid]),
                    true,
                    ProcessRefreshKind::nothing().with_cpu().with_memory(),
                );
                if let Some(process) = system.process(pid) {
                    snapshot.hub_ram = Some(process.memory());
                    snapshot.hub_cpu_percent = interval.and_then(|_| {
                        percent(process.cpu_usage() / system.cpus().len().max(1) as f32)
                    });
                }
            }
            snapshot.cpu_percent = interval.and_then(|_| percent(system.global_cpu_usage()));
            if system.total_memory() > 0 {
                snapshot.ram_total = Some(system.total_memory());
                snapshot.ram_used = Some(system.used_memory());
            }
        }
        if self.networks.is_none() {
            self.networks = Networks::new().ok();
        }
        let mut totals = HashMap::new();
        if let Some(networks) = self.networks.as_mut() {
            networks.refresh(true);
            let lan = lara_hub::discovery::lan_ip();
            for (name, data) in networks.iter() {
                let name = name.to_string_lossy().into_owned();
                let received = data.total_received();
                let sent = data.total_transmitted();
                let previous = self.previous.get(&name);
                snapshot.adapters.push(Adapter {
                    classroom: lan
                        .is_some_and(|ip| data.ip_networks().iter().any(|net| net.addr == ip)),
                    received_per_second: interval
                        .and_then(|d| previous.and_then(|p| rate(received, p.0, d))),
                    sent_per_second: interval
                        .and_then(|d| previous.and_then(|p| rate(sent, p.1, d))),
                    name: name.clone(),
                });
                totals.insert(name, (received, sent));
            }
        }
        snapshot.adapters.sort_by(|a, b| a.name.cmp(&b.name));
        self.previous = totals;
        // Only the volume containing Hub data is relevant; never return filesystem paths.
        if let (Ok(path), Ok(mut disks)) = (
            self.data_dir.canonicalize(),
            Disks::new_with_refreshed_list_specifics(DiskRefreshKind::nothing()),
        ) {
            if let Some(disk) = disks
                .iter_mut()
                .filter(|disk| {
                    disk.mount_point()
                        .canonicalize()
                        .is_ok_and(|mount| path.starts_with(mount))
                })
                .max_by_key(|disk| disk.mount_point().as_os_str().len())
            {
                if disk.refresh_specifics(DiskRefreshKind::nothing().with_storage())
                    && disk.total_space() > 0
                {
                    snapshot.disk_free = Some(disk.available_space());
                    snapshot.disk_total = Some(disk.total_space());
                }
            }
        }
        self.sampled = Some(now);
        self.cached = Some(snapshot.clone());
        snapshot
    }
}

fn percent(value: f32) -> Option<f32> {
    value.is_finite().then(|| value.clamp(0.0, 100.0))
}

fn rate(current: u64, previous: u64, elapsed: Duration) -> Option<f64> {
    if elapsed.is_zero() {
        return None;
    }
    current
        .checked_sub(previous)
        .map(|bytes| bytes as f64 / elapsed.as_secs_f64())
}

#[tauri::command]
pub async fn hub_metrics(
    hub: tauri::State<'_, Hub>,
    monitor: tauri::State<'_, Arc<Mutex<Monitor>>>,
    token: String,
) -> Result<Snapshot, &'static str> {
    let app = hub
        .handle
        .lock()
        .map_err(|_| "HUB_UNAVAILABLE")?
        .as_ref()
        .map(|handle| handle.state().clone())
        .ok_or("HUB_UNAVAILABLE")?;
    authorize(&app, &token).await?;
    let monitor = monitor.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        monitor
            .lock()
            .map_err(|_| "MONITORING_UNAVAILABLE")
            .map(|mut monitor| monitor.sample())
    })
    .await
    .map_err(|_| "MONITORING_UNAVAILABLE")?
}

async fn authorize(app: &lara_hub::AppState, token: &str) -> Result<(), &'static str> {
    let user = sessions::user_for_token(&app.db, &token, app.clock.now_ms())
        .await
        .map_err(|_| "HUB_UNAVAILABLE")?
        .ok_or("UNAUTHORIZED")?;
    if user.role != Role::Admin {
        return Err("FORBIDDEN");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn monitoring_requires_an_active_admin_session() {
        use lara_hub::{db, HubConfig};
        let suffix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory =
            std::env::temp_dir().join(format!("lara-monitor-auth-{}-{suffix}", std::process::id()));
        let hub = lara_hub::serve(HubConfig {
            http_port: 0,
            ws_port: 0,
            ..HubConfig::new(&directory)
        })
        .await
        .unwrap();
        let app = hub.state();
        let now = app.clock.now_ms();
        assert_eq!(authorize(app, "invalid").await, Err("UNAUTHORIZED"));
        for (id, role) in [
            ("ADMIN-0001", Role::Admin),
            ("T-0001", Role::Teacher),
            ("123456789012", Role::Student),
        ] {
            let mut tx = db::begin_immediate(&app.db).await.unwrap();
            let user = db::users::insert(
                &mut tx,
                id,
                "Synthetic account",
                role,
                "unused test hash",
                now,
            )
            .await
            .unwrap();
            let expired = sessions::open(
                &mut tx,
                user.clone(),
                now - lara_hub::auth::token::SESSION_TTL_MS - 1,
            )
            .await
            .unwrap();
            let session = sessions::open(&mut tx, user, now).await.unwrap();
            tx.commit().await.unwrap();
            assert_eq!(authorize(app, &expired.token).await, Err("UNAUTHORIZED"));
            assert_eq!(
                authorize(app, &session.token).await,
                if role == Role::Admin {
                    Ok(())
                } else {
                    Err("FORBIDDEN")
                }
            );
            sessions::close(&app.db, &session.token).await.unwrap();
            assert_eq!(authorize(app, &session.token).await, Err("UNAUTHORIZED"));
        }
        hub.shutdown().await;
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rates_use_elapsed_time_and_reject_counter_resets() {
        assert_eq!(rate(500, 100, Duration::from_millis(2500)), Some(160.0));
        assert_eq!(rate(100, 500, Duration::from_secs(2)), None);
        assert_eq!(rate(100, 100, Duration::from_secs(2)), Some(0.0));
        assert_eq!(rate(500, 100, Duration::ZERO), None);
        assert_eq!(percent(f32::NAN), None);
    }

    #[test]
    fn real_collector_warms_up_then_reads_bounded_values() {
        let mut monitor = Monitor::new(std::env::temp_dir());
        let first = monitor.sample();
        assert!(first.cpu_percent.is_none());
        assert!(first
            .adapters
            .iter()
            .all(|a| a.received_per_second.is_none()));
        std::thread::sleep(Duration::from_secs(2));
        let next = monitor.sample();
        assert!(next.ram_total.is_some_and(|bytes| bytes > 0));
        assert!(next.hub_ram.is_some_and(|bytes| bytes > 0));
        assert!(next
            .cpu_percent
            .is_some_and(|cpu| (0.0..=100.0).contains(&cpu)));
        assert!(next
            .hub_cpu_percent
            .is_some_and(|cpu| (0.0..=100.0).contains(&cpu)));
        assert!(next
            .disk_free
            .zip(next.disk_total)
            .is_some_and(|(free, total)| free <= total && total > 0));
        assert!(!next.adapters.is_empty());
    }
}
