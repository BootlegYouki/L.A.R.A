//! The Hub window. It hosts the backend in-process and shows the Hub address; the pages
//! live in `server/ui`. This crate never opens the database (TECH_SPEC section 2).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;
use std::sync::Mutex;

use lara_hub::{HubConfig, HubHandle};
use serde::Serialize;
use tauri::{Manager, RunEvent};
use tokio::runtime::Runtime;
use tracing_subscriber::EnvFilter;

/// The backend runs on its own Tokio runtime so the window's event loop never waits on it.
struct Hub {
    runtime: Runtime,
    handle: Mutex<Option<HubHandle>>,
}

#[derive(Serialize)]
struct LanAddress {
    ip: Option<String>,
    running: bool,
}

#[tauri::command]
fn lan_address(hub: tauri::State<'_, Hub>) -> LanAddress {
    let running = hub.handle.lock().map(|h| h.is_some()).unwrap_or(false);
    LanAddress { ip: lara_hub::discovery::lan_ip().map(|ip| ip.to_string()), running }
}

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    let built = tauri::Builder::default()
        .setup(|app| {
            let data_dir = match std::env::var_os("LARA_DATA_DIR") {
                Some(dir) => PathBuf::from(dir),
                None => app.path().app_data_dir()?,
            };
            let runtime = Runtime::new()?;
            // A Hub that cannot start (port taken) still opens the window, which says so.
            let handle = match runtime.block_on(lara_hub::serve(HubConfig::new(data_dir))) {
                Ok(handle) => Some(handle),
                Err(e) => {
                    tracing::error!("Hub did not start: {e}");
                    None
                }
            };
            app.manage(Hub { runtime, handle: Mutex::new(handle) });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![lan_address])
        .build(tauri::generate_context!());

    let app = match built {
        Ok(app) => app,
        Err(e) => {
            tracing::error!("Hub window did not open: {e}");
            std::process::exit(1);
        }
    };
    app.run(|app, event| {
        if let RunEvent::Exit = event {
            let hub = app.state::<Hub>();
            let handle = hub.handle.lock().ok().and_then(|mut h| h.take());
            if let Some(handle) = handle {
                hub.runtime.block_on(handle.shutdown());
            }
        }
    });
}
