use std::path::PathBuf;

/// Fixed by `contracts/openapi.yaml` (servers) and `contracts/events/README.md`.
pub const HTTP_PORT: u16 = 8080;
pub const WS_PORT: u16 = 8081;

/// Shown in the discovery beacon's `name` field when the admin has not set one yet. A later
/// step persists the admin's choice in `<data_dir>/hub_config.json`; until then this is the label
/// a learner sees while picking a Hub.
pub const DEFAULT_HUB_NAME: &str = "L.A.R.A Hub";

#[derive(Clone, Debug)]
pub struct HubConfig {
    /// Holds `lara.db`; later steps add `storage/` and `hub_config.json` here.
    pub data_dir: PathBuf,
    /// Only tests change the ports (to 0, so parallel test hubs never collide).
    pub http_port: u16,
    pub ws_port: u16,
    /// The friendly room or school name broadcast in the UDP beacon (`name`). The window sets this;
    /// headless runs may override it with `LARA_HUB_NAME`.
    pub hub_name: String,
}

impl HubConfig {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        Self {
            data_dir: data_dir.into(),
            http_port: HTTP_PORT,
            ws_port: WS_PORT,
            hub_name: DEFAULT_HUB_NAME.to_string(),
        }
    }
}
