# Server (Hub) Agent Guide

Read the root `AGENTS.md` first. This file adds what is specific to `server/`.

## What you are building
The Local Hub: Rust backend (`backend/`, Axum, Tokio, SQLx, tokio-tungstenite) plus a Tauri window (`src-tauri/`) used as an admin console and health dashboard. It runs on a dedicated always-on school PC and serves every teacher and learner on the classroom network.

## Commands (run in `server/backend/`)
`cargo check --all-targets` and `cargo test`. Whole-repo contract checks from the root: `python3 -m unittest discover tests`.

## Contract is the spec
* Implement exactly `contracts/openapi.yaml`, `contracts/events/*` and `contracts/schema/server_master.sql`. `scripts/mock_hub.py` and `tests/test_mock_hub.py` are the acceptance reference: your behavior must match the flows they exercise.
* Migrations reproduce `server_master.sql`. Set PRAGMAs on the connection (WAL, `synchronous = NORMAL`, `foreign_keys = ON` on every connection, `busy_timeout = 5000`), never in a migration (SQLx runs migrations in a transaction and WAL fails there).
* Need a new route, event or column? Stop and ask the Lead for a `contract-change` PR first.

## Rules that are easy to get wrong
* **Sync cursor is `sync_revisions.seq`.** Insert a revision row in the same transaction as every change a client must see, with `classroom_id` and `student_id` scope. Never use `updated_at` as a cursor. Bump `hub_meta.sync_epoch` on restore or tombstone pruning.
* **Authorisation on the server.** Role guard plus class ownership on every teacher route. Learners only see their own submissions and attempts.
* **Redaction by type.** Student response types must not have a `correct_answer` field at all. Add a test that scans student route JSON.
* **Quiz lockout.** Reject AI requests with HTTP 403 or `EVENT_ERROR` `QUIZ_IN_PROGRESS` while the learner has a `quiz_attempts` row with `status = 'IN_PROGRESS'`.
* **Time validation.** Accept a submission only if `submitted_at - started_at <= limit + 60 s`, else `TIME_LIMIT_EXCEEDED`. Never trust only the client clock.
* **Video:** HTTP Range with `206`, 2.0 MB/s token bucket per client, 250 MB upload cap. Media routes accept `?token=` because HTML5 video cannot send headers.
* **Secrets:** hash PINs with argon2id and rate-limit logins; store only a SHA-256 of session tokens. Never log PINs, tokens or LRNs.
* **Never delete** users, classrooms or graded rows. Deactivate or archive.
* **AI process:** `llama-server` runs as a managed child with 2 to 4 slots, a bounded FIFO queue, and a hard cap on concurrent generation so the Hub still serves video and quizzes.
* **Windows:** installer registers firewall rules for TCP 8080/8081 and UDP 8888; the dashboard shows a port health check.

## Layout
`backend/src/{discovery,routes,websocket,services,ai,db}`, `backend/bin/` (llama-server), `src-tauri/` (window, USB detection), `ui/` (the window's React pages: admin console and health dashboard). Document routes, migrations and queue behavior in `server/docs/`.

## Never
Add internet calls or telemetry, introduce Prisma or a Node backend, edit `contracts/`, `rules/` or other teams' folders, or hold the DB write lock across a network call.
