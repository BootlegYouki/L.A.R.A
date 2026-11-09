//! Step 3.2 (#8): `GET /api/materials/{id}/stream` serves a lesson video in byte ranges, so a player
//! can seek anywhere, and every file download is capped at 2.0 MB/s per device (PRD FR-3.3, FR-3.4).

mod common;

use std::path::PathBuf;

use common::uploads::{material_form, Class};

/// The mock Hub's lesson clip: 30 seconds of real H.264 in an MP4.
fn lesson_video() -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../scripts/fixtures/lesson-video.mp4");
    std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// The class's video, uploaded by its teacher; returns the material id.
async fn upload_video(class: &Class, bytes: &[u8]) -> String {
    let (status, body) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "VIDEO", bytes)).await;
    assert_eq!(status, 200, "{body}");
    body["id"].as_str().unwrap().to_owned()
}

fn stream_path(id: &str) -> String {
    format!("/api/materials/{id}/stream")
}

#[tokio::test]
async fn a_range_gets_206_with_just_those_bytes() {
    let class = Class::start().await;
    let video = lesson_video();
    let id = upload_video(&class, &video).await;
    let (status, headers, body) = class.hub.get_file(&stream_path(&id), Some(&class.learner_token), Some("bytes=0-99")).await;
    assert_eq!(status, 206);
    assert_eq!(body, video[..100]);
    assert_eq!(&body[4..8], b"ftyp", "a decodable MP4, not filler bytes");
    assert_eq!(headers["content-range"], format!("bytes 0-99/{}", video.len()));
    assert_eq!(headers["accept-ranges"], "bytes");
    assert_eq!(headers["content-type"], "video/mp4", "a desktop <video> tag plays it");
    assert!(headers.contains_key("etag"));
    class.hub.stop().await;
}

#[tokio::test]
async fn a_seek_gets_the_video_from_that_point_on() {
    let class = Class::start().await;
    let video = lesson_video();
    let id = upload_video(&class, &video).await;
    let from = video.len() * 2 / 3;
    let (status, headers, body) =
        class.hub.get_file(&stream_path(&id), Some(&class.learner_token), Some(&format!("bytes={from}-"))).await;
    assert_eq!(status, 206);
    assert_eq!(body, video[from..]);
    assert_eq!(headers["content-range"], format!("bytes {from}-{}/{}", video.len() - 1, video.len()));
    class.hub.stop().await;
}

#[tokio::test]
async fn without_a_range_the_whole_video_comes_with_200() {
    let class = Class::start().await;
    let video = lesson_video();
    let id = upload_video(&class, &video).await;
    let (status, headers, body) = class.hub.get_file(&stream_path(&id), Some(&class.learner_token), None).await;
    assert_eq!(status, 200);
    assert_eq!(body, video);
    assert_eq!(headers["accept-ranges"], "bytes");
    class.hub.stop().await;
}

/// RFC 9110 `If-Range`, as on the VIDEO download (contract #259): a stale validator means the file
/// changed, so the player gets the whole new file instead of mixing two versions.
#[tokio::test]
async fn if_range_resumes_only_while_the_video_is_unchanged() {
    let class = Class::start().await;
    let video = lesson_video();
    let id = upload_video(&class, &video).await;
    let path = stream_path(&id);
    let (_, headers, _) = class.hub.get_file(&path, Some(&class.learner_token), Some("bytes=0-9")).await;
    let etag = headers["etag"].to_str().unwrap().to_owned();
    let resume = [("Range", "bytes=1000-"), ("If-Range", etag.as_str())];
    let (status, _, body) = class.hub.get_file_with(&path, Some(&class.learner_token), &resume).await;
    assert_eq!((status, body.as_slice()), (206, &video[1000..]));
    let stale = [("Range", "bytes=1000-"), ("If-Range", "\"stale\"")];
    let (status, _, body) = class.hub.get_file_with(&path, Some(&class.learner_token), &stale).await;
    assert_eq!((status, body), (200, video), "a changed file restarts from byte 0");
    class.hub.stop().await;
}

#[tokio::test]
async fn a_range_past_the_end_gets_416() {
    let class = Class::start().await;
    let video = lesson_video();
    let id = upload_video(&class, &video).await;
    let past = format!("bytes={}-", video.len());
    let (status, headers, _) = class.hub.get_file(&stream_path(&id), Some(&class.learner_token), Some(&past)).await;
    assert_eq!(status, 416);
    assert_eq!(headers["content-range"], format!("bytes */{}", video.len()));
    class.hub.stop().await;
}

/// HTML5 `<video>` cannot send headers, so the desktop puts the token in the URL (contract `TokenQuery`).
#[tokio::test]
async fn a_video_tag_can_stream_with_the_token_in_the_url() {
    let class = Class::start().await;
    let id = upload_video(&class, &lesson_video()).await;
    let path = format!("{}?token={}", stream_path(&id), class.learner_token);
    let (status, _, body) = class.hub.get_file(&path, None, Some("bytes=0-9")).await;
    assert_eq!((status, body.len()), (206, 10));
    class.hub.stop().await;
}
