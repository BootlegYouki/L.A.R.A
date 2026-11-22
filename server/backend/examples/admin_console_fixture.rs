//! Isolated real Hub for the account import browser checks. Closing stdin shuts it down.
use lara_hub::{serve, HubConfig};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let dir = tempfile::tempdir()?;
    let hub = serve(HubConfig {
        http_port: 0,
        ws_port: 0,
        ..HubConfig::new(dir.path())
    })
    .await?;
    println!("IMPORT_FIXTURE_PORT={}", hub.http_addr().port());
    tokio::task::spawn_blocking(|| {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line)
    })
    .await??;
    hub.shutdown().await;
    Ok(())
}
