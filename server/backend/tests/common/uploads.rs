//! For the upload tests (`materials.rs`, `submissions.rs`): a class with an enrolled learner, sample
//! files with real leading bytes, multipart uploads, and a raw-socket upload for bodies too large
//! to build in memory.

use std::io::ErrorKind;
use std::time::Duration;

use lara_hub::auth::Role;
use lara_hub::db::{
    self,
    sync_revisions::{record_revision, Action, Scope},
};
use reqwest::header::HeaderMap;
use reqwest::multipart::{Form, Part};
use serde_json::{json, Value};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::TestHub;

pub const PDF: &[u8] = b"%PDF-1.4\n% L.A.R.A test handout\n";
pub const MP4: &[u8] = b"\0\0\0\x18ftypmp42\0\0\0\0mp42isom";
pub const HTML: &[u8] = b"<html><script>alert(1)</script></html>";

/// A JPEG header followed by a tag, so tests can tell two photos apart.
pub fn jpeg(tag: &str) -> Vec<u8> {
    let mut bytes = vec![0xFF, 0xD8, 0xFF, 0xE0];
    bytes.extend_from_slice(tag.as_bytes());
    bytes
}

pub fn file_part(name: &str, bytes: &[u8]) -> Part {
    Part::bytes(bytes.to_vec()).file_name(name.to_owned())
}

pub fn material_form(classroom_id: &str, file_type: &str, bytes: &[u8]) -> Form {
    Form::new()
        .text("classroom_id", classroom_id.to_owned())
        .text("title", "Lesson 1")
        .text("file_type", file_type.to_owned())
        .part("file", file_part("lesson.pdf", bytes))
}

pub fn submission_form(submission_id: &str, bytes: &[u8]) -> Form {
    Form::new().text("submission_id", submission_id.to_owned()).part("file", file_part("homework.jpg", bytes))
}

/// With the learner device's Turn in time (contract `turned_in_at`, epoch ms), sent as the phone sends it.
pub fn submission_form_at(submission_id: &str, bytes: &[u8], turned_in_at: &str) -> Form {
    Form::new()
        .text("submission_id", submission_id.to_owned())
        .text("turned_in_at", turned_in_at.to_owned())
        .part("file", file_part("homework.jpg", bytes))
}

/// The real Hub with the mock's teacher `T-0001`, one class, and learner `123456789012` enrolled.
pub struct Class {
    pub hub: TestHub,
    pub id: String,
    code: String,
    pub learner: String,
    pub learner_token: String,
}

impl Class {
    pub async fn start() -> Self {
        let hub = TestHub::start().await;
        let (id, code) = create_class(&hub, hub.teacher_token(), "Science 4").await;
        let mut class = Self { hub, id, code, learner: String::new(), learner_token: String::new() };
        let (learner, token) = class.add_learner("123456789012", true).await;
        class.learner = learner;
        class.learner_token = token;
        class
    }

    pub fn teacher(&self) -> &str {
        self.hub.teacher_token()
    }

    /// A learner who asked to join; `approve` makes them `ACTIVE`, otherwise they stay `PENDING`.
    pub async fn add_learner(&self, lrn: &str, approve: bool) -> (String, String) {
        let user = self.hub.seed_user(lrn, "Learner", Role::Student, "1234").await;
        let id = user.id.clone();
        let token = self.hub.open_session(user).await;
        let (status, body) = self.hub.post_json("/api/classrooms/join", &json!({"class_code": self.code}), Some(&token)).await;
        assert_eq!(status, 200, "{body}");
        if approve {
            let path = format!("/api/classrooms/{}/approve", self.id);
            let (status, body) = self.hub.post_json(&path, &json!({"student_id": id}), Some(self.teacher())).await;
            assert_eq!(status, 200, "{body}");
        }
        (id, token)
    }

    pub async fn teacher_token(&self, lrn: &str) -> (String, String) {
        let user = self.hub.seed_user(lrn, "Other Teacher", Role::Teacher, "1234").await;
        let id = user.id.clone();
        (id, self.hub.open_session(user).await)
    }

    /// Written straight to `classroom_teachers`; the co-teacher routes are step 2.5.
    pub async fn co_teacher(&self, lrn: &str) -> String {
        let (id, token) = self.teacher_token(lrn).await;
        self.exec("INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at, created_at, updated_at) VALUES (?, ?, 1, 1, 1)", &[self.id.as_str(), id.as_str()]).await;
        token
    }

    /// Another teacher's class, for cross-class checks.
    pub async fn other_class(&self) -> (String, String) {
        let (_, token) = self.teacher_token("T-0009").await;
        (create_class(&self.hub, &token, "Math 5").await.0, token)
    }

    /// Written straight to the row; the archive route is step 2.5.
    pub async fn archive(&self) {
        self.exec("UPDATE classrooms SET archived_at = 1 WHERE id = ?", &[self.id.as_str()]).await;
    }

    /// Due in 2100, late work not allowed: on time for every test that does not set a deadline.
    pub async fn assignment(&self, id: &str) {
        self.post_row(
            "assignments",
            id,
            "INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES (?, ?, 'Homework', 4102444800000, 1, 1)",
        )
        .await;
    }

    pub async fn set_due(&self, assignment_id: &str, due_date: i64, allow_late: bool) {
        let due_date = due_date.to_string();
        let allow_late = if allow_late { "1" } else { "0" };
        self.exec("UPDATE assignments SET due_date = ?, allow_late = ? WHERE id = ?", &[due_date.as_str(), allow_late, assignment_id])
            .await;
    }

    pub async fn announcement(&self, id: &str) {
        self.post_row(
            "announcements",
            id,
            "INSERT INTO announcements (id, classroom_id, title, content, created_at, updated_at) VALUES (?, ?, 'Lesson', 'Plants', 1, 1)",
        )
        .await;
    }

    pub async fn topic(&self, id: &str) {
        self.post_row("topics", id, "INSERT INTO topics (id, classroom_id, name, created_at, updated_at) VALUES (?, ?, 'Plants', 1, 1)")
            .await;
    }

    // sqlx 0.9 takes query text as `SqlSafeStr`, which a plain `&str` is only when it is 'static.
    async fn post_row(&self, table: &'static str, id: &str, sql: &'static str) {
        let mut tx = db::begin_immediate(&self.hub.state().db).await.unwrap();
        sqlx::query(sql).bind(id).bind(&self.id).execute(&mut *tx).await.unwrap();
        record_revision(&mut tx, Scope::class(&self.id), table, id, Action::Upsert, 1).await.unwrap();
        tx.commit().await.unwrap();
    }

    pub async fn exec(&self, sql: &'static str, binds: &[&str]) {
        let mut query = sqlx::query(sql);
        for value in binds {
            query = query.bind(*value);
        }
        query.execute(&self.hub.state().db).await.unwrap();
    }

    pub async fn count(&self, sql: &'static str) -> i64 {
        sqlx::query_scalar::<_, i64>(sql).fetch_one(&self.hub.state().db).await.unwrap()
    }

    pub async fn upload(&self, path: &str, token: Option<&str>, form: Form) -> (u16, Value) {
        self.hub.upload(path, token, form).await
    }

    pub async fn pull(&self, token: &str) -> Value {
        let (status, body) = self.hub.post_json("/api/sync/pull", &json!({"cursor": 0}), Some(token)).await;
        assert_eq!(status, 200, "{body}");
        body
    }

    /// Files in `storage/<dir>` (`materials`, `submissions` or `tmp`).
    pub fn files_in(&self, dir: &str) -> Vec<String> {
        std::fs::read_dir(self.hub.state().storage.root().join(dir))
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect()
    }
}

/// The new class's id and code.
async fn create_class(hub: &TestHub, token: &str, name: &str) -> (String, String) {
    let (status, room) = hub.post_json("/api/classrooms", &json!({"name": name, "section": "A"}), Some(token)).await;
    assert_eq!(status, 200, "{room}");
    (room["id"].as_str().unwrap().to_owned(), room["class_code"].as_str().unwrap().to_owned())
}

impl TestHub {
    pub async fn upload(&self, path: &str, token: Option<&str>, form: Form) -> (u16, Value) {
        let url = format!("http://127.0.0.1:{}{path}", self.http_port());
        let mut req = reqwest::Client::new().post(url).multipart(form);
        if let Some(token) = token {
            req = req.bearer_auth(token);
        }
        let res = req.send().await.unwrap();
        let status = res.status().as_u16();
        (status, res.json().await.unwrap_or(Value::Null))
    }

    /// A file GET with a bearer token, or none (for `?token=` in the path): status, headers, bytes.
    pub async fn get_file(&self, path: &str, token: Option<&str>, range: Option<&str>) -> (u16, HeaderMap, Vec<u8>) {
        let headers: Vec<(&str, &str)> = range.map(|r| ("Range", r)).into_iter().collect();
        self.get_file_with(path, token, &headers).await
    }

    /// With any request headers, such as `Range` and `If-Range` together.
    pub async fn get_file_with(&self, path: &str, token: Option<&str>, headers: &[(&str, &str)]) -> (u16, HeaderMap, Vec<u8>) {
        let url = format!("http://127.0.0.1:{}{path}", self.http_port());
        let mut req = reqwest::Client::new().get(url);
        if let Some(token) = token {
            req = req.bearer_auth(token);
        }
        for (name, value) in headers {
            req = req.header(*name, *value);
        }
        let res = req.send().await.unwrap();
        let status = res.status().as_u16();
        let headers = res.headers().clone();
        (status, headers, res.bytes().await.unwrap().to_vec())
    }
}

/// How a raw upload frames its body.
#[derive(Clone, Copy)]
pub enum RawBody {
    /// `Content-Length` announces this many file bytes.
    Declared(u64),
    /// `Transfer-Encoding: chunked`, so only counting can stop it.
    Chunked(u64),
}

/// Streams a multipart upload of `n` zero bytes over a plain socket, without building it in memory,
/// and returns the Hub's answer. The Hub answers 413 before the body ends and closes the
/// connection, so later writes fail; that is expected and ignored.
///
/// `None`: the connection was reset before a byte of the answer could be read. Only Windows does
/// this: when the Hub closes with part of the upload unread, Winsock aborts the socket of a client
/// that is still sending and throws away the answer it had already received (WSAECONNABORTED).
/// Linux keeps the answer readable, so CI checks the 413 itself. See [`assert_file_too_large`].
pub async fn raw_upload(hub: &TestHub, path: &str, token: &str, fields: &[(&str, &str)], body: RawBody) -> Option<(u16, Value)> {
    let stream = tokio::net::TcpStream::connect(("127.0.0.1", hub.http_port())).await.unwrap();
    let (mut read, mut write) = stream.into_split();
    let boundary = "lara-raw-upload";
    let mut preamble = String::new();
    for (name, value) in fields {
        preamble += &format!("--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n");
    }
    preamble += &format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.mp4\"\r\n\r\n");
    let closing = format!("\r\n--{boundary}--\r\n");
    let (framing, file_bytes, chunked) = match body {
        RawBody::Declared(n) => {
            (format!("Content-Length: {}\r\n", preamble.len() as u64 + n + closing.len() as u64), n, false)
        }
        RawBody::Chunked(n) => ("Transfer-Encoding: chunked\r\n".to_owned(), n, true),
    };
    let head = format!(
        "POST {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {token}\r\n\
         Content-Type: multipart/form-data; boundary={boundary}\r\n{framing}\r\n"
    );
    let frame = move |data: &[u8]| -> Vec<u8> {
        if !chunked {
            return data.to_vec();
        }
        let mut out = format!("{:x}\r\n", data.len()).into_bytes();
        out.extend_from_slice(data);
        out.extend_from_slice(b"\r\n");
        out
    };
    let writer = tokio::spawn(async move {
        if write.write_all(head.as_bytes()).await.is_err() || write.write_all(&frame(preamble.as_bytes())).await.is_err() {
            return;
        }
        let zeros = vec![0u8; 64 * 1024];
        let mut sent = 0u64;
        while sent < file_bytes {
            let n = (file_bytes - sent).min(zeros.len() as u64) as usize;
            if write.write_all(&frame(&zeros[..n])).await.is_err() {
                return;
            }
            sent += n as u64;
        }
        let _ = write.write_all(&frame(closing.as_bytes())).await;
        if chunked {
            let _ = write.write_all(b"0\r\n\r\n").await;
        }
    });
    let response = tokio::time::timeout(Duration::from_secs(120), async {
        let mut response = Vec::new();
        let mut buf = [0u8; 4096];
        let mut reset = false;
        loop {
            let n = match read.read(&mut buf).await {
                Ok(0) => break,
                Err(error) => {
                    reset = matches!(error.kind(), ErrorKind::ConnectionAborted | ErrorKind::ConnectionReset);
                    break;
                }
                Ok(n) => n,
            };
            response.extend_from_slice(&buf[..n]);
            if response_complete(&response) {
                break;
            }
        }
        (response, reset)
    })
    .await
    .expect("the Hub answers a too-large upload");
    writer.abort();
    let (response, reset) = response;
    if response.is_empty() && reset {
        return None;
    }
    let text = String::from_utf8_lossy(&response);
    let status = text.split_whitespace().nth(1).and_then(|s| s.parse().ok()).expect("an HTTP status line");
    let body = text.split_once("\r\n\r\n").map(|(_, b)| b).unwrap_or("");
    Some((status, serde_json::from_str(body).unwrap_or(Value::Null)))
}

/// The Hub's 413 `FILE_TOO_LARGE`, or on Windows only a reset before the answer arrived
/// ([`raw_upload`]). The callers' checks that nothing was kept run either way.
pub fn assert_file_too_large(answer: Option<(u16, Value)>) {
    match answer {
        Some((status, body)) => {
            assert_eq!((status, body["error"]["code"].as_str()), (413, Some("FILE_TOO_LARGE")), "{body}")
        }
        None if cfg!(windows) => {}
        None => panic!("the connection was reset without an answer"),
    }
}

fn response_complete(response: &[u8]) -> bool {
    let text = String::from_utf8_lossy(response);
    let Some((head, body)) = text.split_once("\r\n\r\n") else { return false };
    let length = head
        .lines()
        .find_map(|line| line.to_ascii_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse::<usize>()))
        .and_then(Result::ok);
    length.is_some_and(|n| body.len() >= n)
}
