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
* When student endpoints serve active quiz payloads (`/api/quizzes/active` or `GET /api/quizzes/:id`), `correct_answer` must be completely omitted from the JSON payload.
* Student clients must never contain parsing fields or Room columns for unsubmitted quiz answer keys.

---

## 3. Canonical SQLite Schemas (`contracts/schema/`)

The database DDL is standardized in `contracts/schema/` to guarantee 1:1 delta-sync parity across all clients and the Local Hub:

* **`contracts/schema/server_master.sql`:** The master authoritative schema for the Local Hub. Contains all master entity tables, indexes, the monotonic `sync_revisions` ledger, and the grading `correct_answer` column.
* **`contracts/schema/client_offline.sql`:** The offline-first client schema for Android Room and Desktop SQLite. Adds offline tracking columns (`sync_status`, `local_file_path`) and strictly redacts `correct_answer`.

