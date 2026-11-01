//! `TestHub`: the real Hub on a temp data dir and free ports, one per test.
#![allow(dead_code)]

use lara_hub::auth::{sessions, Role};
use lara_hub::models::user::{self, User};
use lara_hub::{db, AppState, HubConfig, HubHandle};
use reqwest::header::HeaderMap;
use reqwest::Method;
use serde_json::Value;
use tempfile::TempDir;

pub struct TestHub {
    hub: HubHandle,
    client: reqwest::Client,
    /// A session for the mock's teacher `T-0001` (PIN `1234`), seeded in the database.
    teacher_token: String,
    _data_dir: TempDir,
}

impl TestHub {
    pub async fn start() -> Self {
        let data_dir = tempfile::tempdir().unwrap();
        let config = HubConfig { http_port: 0, ws_port: 0, ..HubConfig::new(data_dir.path()) };
        let hub = lara_hub::serve(config).await.unwrap();
        let mut test_hub = Self { hub, client: reqwest::Client::new(), teacher_token: String::new(), _data_dir: data_dir };
        let teacher = test_hub.seed_user("T-0001", "Teacher", Role::Teacher, "1234").await;
        test_hub.teacher_token = test_hub.open_session(teacher).await;
        test_hub
    }

    pub fn state(&self) -> &AppState {
        self.hub.state()
    }

    pub fn teacher_token(&self) -> &str {
        &self.teacher_token
    }

    /// Writes the account straight to the database, as the admin console (2.3) will.
    pub async fn seed_user(&self, lrn: &str, name: &str, role: Role, pin: &str) -> User {
        let state = self.state();
        let pin_hash = state.pins.hash(pin).await.unwrap();
        let mut tx = db::begin_immediate(&state.db).await.unwrap();
        let user = user::insert(&mut tx, lrn, name, role, &pin_hash, state.clock.now_ms()).await.unwrap();
        tx.commit().await.unwrap();
        user
    }

    pub async fn open_session(&self, user: User) -> String {
        let state = self.state();
        let mut tx = db::begin_immediate(&state.db).await.unwrap();
        let session = sessions::open(&mut tx, user, state.clock.now_ms()).await.unwrap();
        tx.commit().await.unwrap();
        session.token
    }

    pub async fn post_json(&self, path: &str, body: &Value, token: Option<&str>) -> (u16, Value) {
        let (status, _, body) = self.post_json_with_headers(path, body, token).await;
        (status, body)
    }

    pub async fn post_json_with_headers(&self, path: &str, body: &Value, token: Option<&str>) -> (u16, HeaderMap, Value) {
        let url = format!("http://127.0.0.1:{}{path}", self.hub.http_addr().port());
        let mut req = self.client.post(url).json(body);
        if let Some(token) = token {
            req = req.bearer_auth(token);
        }
        let res = req.send().await.unwrap();
        let status = res.status().as_u16();
        let headers = res.headers().clone();
        (status, headers, res.json().await.unwrap_or(Value::Null))
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
