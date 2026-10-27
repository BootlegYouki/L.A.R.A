//! `TestHub`: the real Hub on a temp data dir and free ports, one per test.
#![allow(dead_code)]

use lara_hub::auth::{AuthUser, Role};
use lara_hub::{AppState, HubConfig, HubHandle};
use reqwest::Method;
use serde_json::Value;
use tempfile::TempDir;

/// Seeded session for the mock's teacher. Until step 2.0 this is the only way a token is valid.
pub const TEACHER_TOKEN: &str = "test-token-T-0001";

pub struct TestHub {
    hub: HubHandle,
    client: reqwest::Client,
    _data_dir: TempDir,
}

impl TestHub {
    pub async fn start() -> Self {
        let data_dir = tempfile::tempdir().unwrap();
        let config = HubConfig { http_port: 0, ws_port: 0, ..HubConfig::new(data_dir.path()) };
        let hub = lara_hub::serve(config).await.unwrap();
        hub.state().sessions.insert(TEACHER_TOKEN, AuthUser { id: "T-0001".into(), role: Role::Teacher });
        Self { hub, client: reqwest::Client::new(), _data_dir: data_dir }
    }

    pub fn state(&self) -> &AppState {
        self.hub.state()
    }

    pub fn ws_url(&self) -> String {
        format!("ws://127.0.0.1:{}", self.hub.ws_addr().port())
    }

    pub async fn get(&self, path: &str, token: Option<&str>) -> (u16, Value) {
        self.request(Method::GET, path, token).await
    }

    /// For non-JSON responses (the portal page, CSS, binaries): status, `Content-Type`, body bytes.
    pub async fn get_raw(&self, path: &str) -> (u16, String, Vec<u8>) {
        let url = format!("http://127.0.0.1:{}{path}", self.hub.http_addr().port());
        let res = self.client.get(url).send().await.unwrap();
        let status = res.status().as_u16();
        let content_type =
            res.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        let body = res.bytes().await.unwrap().to_vec();
        (status, content_type, body)
    }

    /// A ranged GET (for the resumable installer download): status, the `Content-Range` header, body.
    pub async fn get_range(&self, path: &str, range: &str) -> (u16, Option<String>, Vec<u8>) {
        let url = format!("http://127.0.0.1:{}{path}", self.hub.http_addr().port());
        let res = self.client.get(url).header("Range", range).send().await.unwrap();
        let status = res.status().as_u16();
        let content_range =
            res.headers().get("content-range").and_then(|v| v.to_str().ok()).map(String::from);
        let body = res.bytes().await.unwrap().to_vec();
        (status, content_range, body)
    }

    /// The Hub's `<data_dir>/releases`, where the portal serves client installers from.
    pub fn releases_dir(&self) -> &std::path::Path {
        &self.hub.state().releases_dir
    }

    pub async fn request(&self, method: Method, path: &str, token: Option<&str>) -> (u16, Value) {
        let url = format!("http://127.0.0.1:{}{path}", self.hub.http_addr().port());
        let mut req = self.client.request(method, url);
        if let Some(token) = token {
            req = req.bearer_auth(token);
        }
        let res = req.send().await.unwrap();
        let status = res.status().as_u16();
        (status, res.json().await.unwrap_or(Value::Null))
    }

    pub async fn stop(self) {
        self.hub.shutdown().await;
    }
}
