//! Isolated UI verification Hub. Uses ephemeral ports and synthetic accounts, never school data.
use lara_hub::{
    services::activity::{Code, Event, Reason, Service},
    HubConfig,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let directory = std::env::args().nth(1).ok_or("Pass an empty temporary data directory")?;
    let directory = std::path::PathBuf::from(directory);
    if directory.exists() {
        return Err("Preview directory must not already exist".into());
    }
    let config = HubConfig { http_port: 0, ws_port: 0, test_seed: true, ..HubConfig::new(&directory) };
    let hub = lara_hub::serve(config).await?;
    for _ in 0..120 {
        hub.state().activity.record(Event::new(Code::AccountCreated, Service::Accounts));
    }
    hub.state()
        .activity
        .record(Event::new(Code::DocumentProcessingFailed, Service::Documents).reason(Reason::NoTextLayer));
    std::fs::write(
        directory.join("preview.json"),
        serde_json::to_vec(&serde_json::json!({"http_port":hub.http_addr().port()}))?,
    )?;
    while !directory.join("stop").exists() {
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    hub.shutdown().await;
    Ok(())
}
