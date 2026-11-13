//! Issue #274 (contract #273): who uploaded a material, and when enrollments, co-teacher rows,
//! lesson chunks and homework were first made. These columns stay on the Hub; no response carries them.

mod common;

use common::uploads::{jpeg, material_form, submission_form, Class};
use serde_json::{json, Value};

async fn text(class: &Class, sql: &'static str) -> String {
    sqlx::query_scalar(sql).fetch_one(&class.hub.state().db).await.unwrap()
}

async fn times(class: &Class, sql: &'static str) -> Vec<(i64, i64)> {
    sqlx::query_as(sql).fetch_all(&class.hub.state().db).await.unwrap()
}

fn lesson_pdf() -> Vec<u8> {
    std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/lesson.pdf")).unwrap()
}

#[tokio::test]
async fn the_uploading_teacher_is_recorded_and_never_sent() {
    let class = Class::start().await;
    let co_token = class.co_teacher("T-0002").await;
    let co_id = text(&class, "SELECT teacher_id FROM classroom_teachers").await;
    let (status, material) = class.upload("/api/materials", Some(&co_token), material_form(&class.id, "DOCUMENT", &lesson_pdf())).await;
    assert_eq!(status, 200, "{material}");
    assert!(material.get("uploaded_by").is_none(), "{material}");
    assert_eq!(text(&class, "SELECT uploaded_by FROM materials").await, co_id, "the co-teacher who uploaded, not the owner");

    let pulled = class.pull(&class.learner_token).await;
    assert!(pulled["materials"].as_array().unwrap().iter().all(|m| m.get("uploaded_by").is_none()), "{pulled}");
}

#[tokio::test]
async fn chunks_record_when_they_were_made() {
    let class = Class::start().await;
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", &lesson_pdf())).await;
    assert_eq!(status, 200, "{material}");
    let created: i64 = sqlx::query_scalar("SELECT created_at FROM materials").fetch_one(&class.hub.state().db).await.unwrap();
    let chunks = times(&class, "SELECT created_at, updated_at FROM material_chunks").await;
    assert!(chunks.len() >= 3, "{chunks:?}");
    assert!(chunks.iter().all(|&row| row == (created, created)), "{chunks:?} vs {created}");
}

#[tokio::test]
async fn an_enrollment_keeps_when_it_was_first_made() {
    let class = Class::start().await;
    let first = times(&class, "SELECT created_at, joined_at FROM enrollments").await;
    assert_eq!(first.len(), 1);
    assert_eq!(first[0].0, first[0].1, "a new enrollment is created when the learner asks to join");

    let path = format!("/api/classrooms/{}/remove", class.id);
    let (status, body) = class.hub.post_json(&path, &json!({"student_id": class.learner}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    let (status, body) = class.hub.post_json("/api/classrooms/join", &json!({"class_code": code(&class).await}), Some(&class.learner_token)).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(times(&class, "SELECT created_at, joined_at FROM enrollments").await, first, "asking again reuses the row");
}

#[tokio::test]
async fn a_co_teacher_row_records_when_it_was_added() {
    let class = Class::start().await;
    class.teacher_token("T-0002").await;
    let path = format!("/api/classrooms/{}/teachers", class.id);
    let (status, body) = class.hub.post_json(&path, &json!({"lrn_or_id": "T-0002"}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    let row: (i64, i64, i64) = sqlx::query_as("SELECT added_at, created_at, updated_at FROM classroom_teachers")
        .fetch_one(&class.hub.state().db)
        .await
        .unwrap();
    assert!(row.0 > 0 && row == (row.0, row.0, row.0), "{row:?}");
}

#[tokio::test]
async fn a_resubmission_keeps_when_the_work_was_first_handed_in() {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    let submit = |id: &'static str, tag: &'static str| {
        class.upload("/api/assignments/asg-1/submit", Some(&class.learner_token), submission_form(id, &jpeg(tag)))
    };
    let (status, first) = submit("c1000000-0000-4000-8000-000000000001", "a").await;
    assert_eq!(status, 200, "{first}");
    assert!(first.get("created_at").is_none(), "{first}");
    let received = first["received_at"].as_i64().unwrap();
    assert_eq!(times(&class, "SELECT created_at, received_at FROM assignment_submissions").await, [(received, received)]);

    tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    let (status, second): (u16, Value) = submit("c1000000-0000-4000-8000-000000000002", "b").await;
    assert_eq!(status, 200, "{second}");
    let rows = times(&class, "SELECT created_at, received_at FROM assignment_submissions").await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].0, received, "created_at stays the first hand-in");
    assert!(rows[0].1 > received, "received_at is the new file's arrival");
}

async fn code(class: &Class) -> String {
    sqlx::query_scalar("SELECT class_code FROM classrooms WHERE id = ?").bind(&class.id).fetch_one(&class.hub.state().db).await.unwrap()
}
