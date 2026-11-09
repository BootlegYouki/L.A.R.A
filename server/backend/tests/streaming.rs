//! Step 3.2 (#8): `GET /api/materials/{id}/stream` serves a lesson video in byte ranges, so a player
//! can seek anywhere, and every file download is capped at 2.0 MB/s per device (PRD FR-3.3, FR-3.4).

mod common;

use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::uploads::{material_form, Class, MP4};

/// The contract's cap per device, and what a device may send at once after a pause.
const RATE: f64 = 2_000_000.0;
const BURST: f64 = 64.0 * 1024.0;

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

/// A video of `len` bytes: a real MP4 header (the Hub sniffs it), then zeros.
fn video_of(len: usize) -> Vec<u8> {
    let mut video = MP4.to_vec();
    video.resize(len, 0);
    video
}

/// The least time a device can take to get `bytes`: its first 64 KiB at once, the rest at 2 MB/s.
fn least_time(bytes: usize) -> Duration {
    Duration::from_secs_f64((bytes as f64 - BURST).max(0.0) / RATE)
}

/// A device on the LAN, by its address. Every 127.0.0.x is the Hub PC's own loopback, but the Hub
/// sees each as a different device, as it would see each phone's address on the router.
fn device(last: u8) -> IpAddr {
    IpAddr::V4(Ipv4Addr::new(127, 0, 0, last))
}

/// A whole-file GET from `from`: how long it took and how many bytes arrived.
async fn fetch(class: &Class, from: IpAddr, path: &str) -> (Duration, usize) {
    let client = reqwest::Client::builder().local_address(from).build().unwrap();
    let url = format!("http://127.0.0.1:{}{path}", class.hub.http_port());
    let started = Instant::now();
    let res = client.get(url).bearer_auth(&class.learner_token).send().await.unwrap();
    assert!(res.status().is_success(), "{}", res.status());
    let bytes = res.bytes().await.unwrap().len();
    (started.elapsed(), bytes)
}

/// Generous, for a slow CI machine: the cap must not make a device much slower than 2 MB/s.
fn at_most_double(time: Duration) -> Duration {
    time * 2 + Duration::from_secs(1)
}

#[tokio::test]
async fn one_device_gets_at_most_2_mb_a_second() {
    let class = Class::start().await;
    let len = 1_000_000;
    let id = upload_video(&class, &video_of(len)).await;
    let (took, got) = fetch(&class, device(1), &stream_path(&id)).await;
    assert_eq!(got, len);
    assert!(took >= least_time(len), "1 MB in {took:?}, faster than 2 MB/s");
    assert!(took < at_most_double(least_time(len)), "1 MB in {took:?}, far slower than 2 MB/s");
    class.hub.stop().await;
}

/// A player often opens a second connection to seek; it shares the device's 2 MB/s, not doubles it.
#[tokio::test]
async fn two_streams_on_one_device_share_its_2_mb_a_second() {
    let class = Class::start().await;
    let len = 600_000;
    let path = stream_path(&upload_video(&class, &video_of(len)).await);
    let started = Instant::now();
    let (a, b) = tokio::join!(fetch(&class, device(1), &path), fetch(&class, device(1), &path));
    let took = started.elapsed();
    assert_eq!((a.1, b.1), (len, len));
    assert!(took >= least_time(2 * len), "2 x 600 KB in {took:?}, faster than 2 MB/s for the device");
    class.hub.stop().await;
}

/// Save for Home and handouts count against the same allowance as the stream.
#[tokio::test]
async fn a_download_shares_the_allowance_with_a_stream() {
    let class = Class::start().await;
    let len = 600_000;
    let id = upload_video(&class, &video_of(len)).await;
    let (stream, download) = (stream_path(&id), format!("/api/materials/{id}/download"));
    let started = Instant::now();
    let (a, b) = tokio::join!(fetch(&class, device(1), &stream), fetch(&class, device(1), &download));
    let took = started.elapsed();
    assert_eq!((a.1, b.1), (len, len));
    assert!(took >= least_time(2 * len), "a stream and a download of 600 KB each in {took:?}");
    class.hub.stop().await;
}

/// Issue #8: "20 simultaneous video streams do not exceed 40 MB/s total router bandwidth". Each
/// device keeps its own 2 MB/s, so one device does not slow the others.
#[tokio::test]
async fn twenty_devices_streaming_at_once_stay_within_40_mb_a_second() {
    let class = Class::start().await;
    let len = 1_000_000;
    let path = stream_path(&upload_video(&class, &video_of(len)).await);
    let started = Instant::now();
    let streams = (2..=21).map(|last| fetch(&class, device(last), &path));
    let results = futures_util::future::join_all(streams).await;
    let took = started.elapsed();
    let total: usize = results.iter().map(|(_, bytes)| bytes).sum();
    assert_eq!(total, 20 * len);
    for (each, _) in &results {
        assert!(*each >= least_time(len), "a device got 1 MB in {each:?}, faster than 2 MB/s");
    }
    let mb_per_second = total as f64 / took.as_secs_f64() / 1e6;
    println!("20 devices: {total} bytes in {took:?}, {mb_per_second:.1} MB/s in total");
    assert!(total as f64 <= 20.0 * (RATE * took.as_secs_f64() + BURST), "{mb_per_second:.1} MB/s for 20 devices");
    assert!(took < at_most_double(least_time(len)), "20 devices took {took:?}: they slowed each other down");
    class.hub.stop().await;
}
