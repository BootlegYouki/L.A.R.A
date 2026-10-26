//! Headless Hub for development, CI and tests: `cargo run --bin lara-hub`.

use std::path::PathBuf;
use std::process::ExitCode;

use lara_hub::HubConfig;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    let data_dir = std::env::var_os("LARA_DATA_DIR").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("lara-data"));
    let mut config = HubConfig::new(data_dir);
    // The window sets the room name once step 2.3 lands; headless runs (dev, CI) may set it here.
    if let Ok(name) = std::env::var("LARA_HUB_NAME") {
        if !name.trim().is_empty() {
            config.hub_name = name;
        }
    }
    let hub = match lara_hub::serve(config).await {
        Ok(hub) => hub,
        Err(e) => {
            tracing::error!("Hub did not start: {e}");
            return ExitCode::FAILURE;
        }
    };
    match lara_hub::discovery::lan_ip() {
        Some(ip) => tracing::info!("Hub address: {ip}"),
        None => tracing::warn!("No classroom network found; devices on this PC only"),
    }

    if let Err(e) = tokio::signal::ctrl_c().await {
        tracing::error!("could not wait for Ctrl+C: {e}");
    }
    hub.shutdown().await;
    ExitCode::SUCCESS
}
