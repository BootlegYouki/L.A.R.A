//! The captive download portal (`GET /download`): a bilingual 3-step sideloading guide served
//! with no token, plus the bundled page assets and the client installers. Matches the mock's
//! Sprint 1 flow (200 HTML on `/download`).

mod common;

use common::TestHub;

#[tokio::test]
async fn download_returns_the_portal_page_as_html_with_no_token() {
    let hub = TestHub::start().await;
    let (status, content_type, body) = hub.get_raw("/download").await;
    assert_eq!(status, 200);
    assert!(content_type.starts_with("text/html"), "content-type was {content_type:?}");
    let html = String::from_utf8(body).unwrap();
    assert!(html.contains("<!DOCTYPE html>"));
    hub.stop().await;
}

#[tokio::test]
async fn root_shows_the_same_portal_page() {
    let hub = TestHub::start().await;
    let (status, content_type, _) = hub.get_raw("/").await;
    assert_eq!(status, 200);
    assert!(content_type.starts_with("text/html"), "content-type was {content_type:?}");
    hub.stop().await;
}

#[tokio::test]
async fn portal_guide_is_bilingual_and_shows_the_three_steps() {
    let hub = TestHub::start().await;
    let (_, _, body) = hub.get_raw("/download").await;
    let html = String::from_utf8(body).unwrap();

    // English guide copy and its Filipino counterpart (bilingual rule, AGENTS §4).
    assert!(html.contains("Download L.A.R.A. (Android)"));
    assert!(html.contains("I-download ang L.A.R.A. (Android)"));
    // The three sideload steps, both languages.
    assert!(html.contains("Allow from this source"));
    assert!(html.contains("Install"));
    assert!(html.contains("Pindutin"));
    hub.stop().await;
}

#[tokio::test]
async fn portal_assets_are_served_locally_and_name_no_external_host() {
    let hub = TestHub::start().await;

    let (status, content_type, css) = hub.get_raw("/download/assets/fonts.css").await;
    assert_eq!(status, 200);
    assert!(content_type.starts_with("text/css"), "content-type was {content_type:?}");
    let css = String::from_utf8(css).unwrap();
    // Fonts come from the Hub, never a CDN or Google Fonts (invariant 3.1).
    assert!(!css.contains("http://") && !css.contains("https://"), "fonts.css references a remote URL");

    let (status, content_type, font) = hub.get_raw("/download/assets/web/Phosphor.woff2").await;
    assert_eq!(status, 200);
    assert_eq!(content_type, "font/woff2");
    assert!(!font.is_empty());

    let (status, _, _) = hub.get_raw("/download/assets/does-not-exist.css").await;
    assert_eq!(status, 404);
    hub.stop().await;
}

#[tokio::test]
async fn missing_installer_is_404_and_a_present_one_downloads() {
    let hub = TestHub::start().await;

    // Nothing has placed the installer yet (the build / installer step does): a plain 404.
    let (status, _, _) = hub.get_raw("/download/lara-student.apk").await;
    assert_eq!(status, 404);

    // Once a file is in <data_dir>/releases it is served, with the Android package mime so the
    // browser offers to install the APK rather than save an unknown file.
    tokio::fs::create_dir_all(hub.releases_dir()).await.unwrap();
    tokio::fs::write(hub.releases_dir().join("lara-student.apk"), b"APK-BYTES").await.unwrap();
    let (status, content_type, body) = hub.get_raw("/download/lara-student.apk").await;
    assert_eq!(status, 200);
    assert_eq!(content_type, "application/vnd.android.package-archive");
    assert_eq!(body, b"APK-BYTES");
    hub.stop().await;
}

#[tokio::test]
async fn installer_download_resumes_with_a_range_request() {
    let hub = TestHub::start().await;
    tokio::fs::create_dir_all(hub.releases_dir()).await.unwrap();
    // A body long enough to split; the content is irrelevant.
    let bytes: Vec<u8> = (0..1000u32).map(|i| i as u8).collect();
    tokio::fs::write(hub.releases_dir().join("lara-student.apk"), &bytes).await.unwrap();

    // Second half of a dropped download: ServeFile answers 206 with the right Content-Range and the
    // tail bytes, so a client can resume instead of restarting from zero.
    let (status, content_range, tail) = hub.get_range("/download/lara-student.apk", "bytes=500-").await;
    assert_eq!(status, 206);
    assert_eq!(content_range.as_deref(), Some("bytes 500-999/1000"));
    assert_eq!(tail, bytes[500..]);

    // A range past the end is a 416, not a panic or a wrong body.
    let (status, _, _) = hub.get_range("/download/lara-student.apk", "bytes=99999-").await;
    assert_eq!(status, 416);
    hub.stop().await;
}

#[tokio::test]
async fn installer_path_cannot_escape_the_releases_folder() {
    let hub = TestHub::start().await;

    // Put a file one level above releases_dir; the portal must never serve it.
    let secret = hub.releases_dir().parent().unwrap().join("secret.txt");
    tokio::fs::write(&secret, b"SECRET").await.unwrap();

    // Encoded separators decode to `/` and `\`, which the name guard rejects before any filesystem
    // access, so a traversal attempt is a 404, never the secret file's bytes.
    for encoded in ["..%2F..%2Fsecret.txt", "..%2Fsecret.txt", "..%5Csecret.txt"] {
        let (status, _, body) = hub.get_raw(&format!("/download/{encoded}")).await;
        assert_eq!(status, 404, "name {encoded}");
        assert_ne!(body, b"SECRET", "portal leaked a file outside releases for {encoded}");
    }
    hub.stop().await;
}
