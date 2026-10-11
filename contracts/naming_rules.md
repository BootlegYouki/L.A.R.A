# L.A.R.A API Serialization & Naming Invariants

This document outlines the strict cross-platform naming rules enforced across the Rust backend, Kotlin Android client, and React TypeScript desktop client.

---

## 1. Network Boundary Case: `snake_case`

All JSON keys serialized across HTTP REST responses and WebSocket event envelopes must strictly use **`snake_case`**.

### Platform Bindings

* **Kotlin (`kotlinx.serialization`):**
  ```kotlin
  @Serializable
  data class ClassroomDto(
      @SerialName("id") val id: String,
      @SerialName("class_code") val classCode: String,
      @SerialName("teacher_id") val teacherId: String
  )
  ```

* **Rust (`serde`):**
  ```rust
  #[derive(Serialize, Deserialize)]
  #[serde(rename_all = "snake_case")]
  pub struct ClassroomDto {
      pub id: String,
      pub class_code: String,
      pub teacher_id: String,
  }
  ```

* **TypeScript:**
  ```typescript
  export interface ClassroomDto {
      id: string;
      class_code: string;
      teacher_id: string;
  }
  ```

---

## 2. Strict Answer Key Redaction: `correct_answer`

* **Rule:** The field `correct_answer` is strictly restricted to teacher authorized sessions and server-side evaluation.
* When student endpoints serve active quiz payloads (`GET /api/quizzes/active` or `GET /api/quizzes/{id}`), `correct_answer` must be completely omitted from the JSON payload.
* Student clients must never contain parsing fields or Room columns for unsubmitted quiz answer keys.

---

## 3. Value Conventions

* **Timestamps:** epoch **milliseconds** as integers (`created_at`, `started_at`, `submitted_at`, `server_time`).
* **IDs:** UUID v4 strings. Rows a client can create offline (homework `submission_id`, comments, private comments, quiz attempts) get their id on the client so retries are idempotent.
* **Paths:** written as `/api/things/{id}` everywhere, as in `openapi.yaml`.
* **Enums:** `UPPER_SNAKE` strings (`PENDING`, `ACTIVE`, `REMOVED`, `QUEUED_FOR_SYNC`, `IN_PROGRESS`).
* **Class codes:** 6 uppercase characters from `A-HJ-NP-Z2-9` (no `0 O 1 I`), stored without a hyphen (`K7M4QX`) and displayed grouped (`K7M-4QX`). Clients strip the hyphen before sending.
* **Errors:** always `{"error": {"code": "...", "message": "..."}}`. WebSocket failures use `EVENT_ERROR` with the same codes.

---

## 4. Privacy Redaction (children's data)

The same discipline as the answer key applies to personal data. A learner can read their own device's database, so clients must never receive or store:

* `pin_hash` or any PIN data.
* Another person's `lrn_or_id`. Clients receive other people's `id`, `full_name` and `role` only (`PublicUser`). A learner sees their own LRN; a teacher sees their roster's.
* Server-side `file_path` values (clients use `download_url`).
* Another learner's submissions, quiz attempts or private comments.
* The Hub admin account (role `ADMIN`). It exists only on the Hub.

---

## 5. Canonical SQLite Schemas (`contracts/schema/`)

The database DDL is standardized in `contracts/schema/` to guarantee parity between the Hub and the clients:

* **`server_master.sql`**: the authoritative Hub schema, including `correct_answer`, the `sync_revisions` change ledger (integer `seq` cursor), `hub_meta`, `sessions` and `material_chunks`.
* **`client_offline.sql`**: the offline slice for Android Room and Desktop SQLite. Adds `sync_status`, `local_file_path` and `sync_state`; strips `correct_answer`, `pin_hash` and classmates' LRN.

How to change any contract: see [`README.md`](./README.md).
