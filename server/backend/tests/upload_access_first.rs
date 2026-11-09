//! Review of #267: whether a teacher teaches the class is checked before their handout is read, so a
//! teacher of another class cannot make the Hub parse a PDF for up to 20 s before the 403.
//!
//! This is the only test in this file on purpose. It reads the Hub's log through a capture scoped to
//! the test's thread (`common::logs`), and that capture misses events once other tests in the same
//! process log at the same time. Cargo runs test files one after another, so here nothing else does.

mod common;

use std::path::PathBuf;

use common::logs;
use common::uploads::{material_form, Class};

fn lesson_pdf() -> Vec<u8> {
    std::fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/lesson.pdf")).unwrap()
}

#[tokio::test]
async fn a_teacher_of_another_class_is_refused_before_the_handout_is_read() {
    let (logs, _guard) = logs::capture();
    let class = Class::start().await;
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    let (status, body) =
        class.upload("/api/materials", Some(&other_teacher), material_form(&class.id, "DOCUMENT", &lesson_pdf())).await;
    assert_eq!(status, 403, "{body}");
    assert_eq!(logs.count("lesson text extracted"), 0, "the PDF was read: {:?}", logs.messages());
    let (status, body) =
        class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", &lesson_pdf())).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(logs.count("lesson text extracted"), 1, "the class's own teacher still gets it read: {:?}", logs.messages());
    class.hub.stop().await;
}
