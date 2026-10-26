use std::path::PathBuf;

/// Fixed by `contracts/openapi.yaml` (servers) and `contracts/events/README.md`.
pub const HTTP_PORT: u16 = 8080;
pub const WS_PORT: u16 = 8081;

#[derive(Clone, Debug)]
pub struct HubConfig {
    /// Holds `lara.db`; later steps add `storage/` and `hub_config.json` here.
    pub data_dir: PathBuf,
    /// Only tests change the ports (to 0, so parallel test hubs never collide).
    pub http_port: u16,
    pub ws_port: u16,
}

impl HubConfig {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        Self { data_dir: data_dir.into(), http_port: HTTP_PORT, ws_port: WS_PORT }
    }
}
