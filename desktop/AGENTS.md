# Desktop Agent Guide

Read the root `AGENTS.md` first. This file adds what is specific to `desktop/`.

## What you are building
A Tauri 2.x client (React 19, TypeScript strict, Tailwind) for student laptops, lab PCs and teachers. Pupils get the offline classroom; teachers get the **full authoring surface** (create class, roster approval, announcements, materials, assignments, grading, quiz builder, live monitor).

## Commands (run in `desktop/`)
`npm run build` (`tsc && vite build`) must pass with zero type errors. Run the Hub simulator from the repo root: `python3 scripts/mock_hub.py`.

## Build against the mock hub
* Develop every network feature against `scripts/mock_hub.py` and `contracts/`. Seed accounts: `T-0001`, `123456789012`, `123456789013`, all PIN `1234`; class code `K7M4QX`.
* Use interfaces matching `contracts/openapi.yaml` (snake_case keys, no `any`). Never invent a field; ask for a contract change.
* Switch to the real Hub only on integration day.

## Rules that are easy to get wrong
* **Offline first.** Every screen reads from local SQLite. No blocking "no connection" dialogs; show the standard offline banner and cached data.
* **Sync:** apply a pull response and its `next_cursor` inside one transaction; on `reset: true` wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows. The cursor is an integer, never a time. See `rules/database-and-sync.md`.
* **Never store** a PIN hash, another person's LRN, or any answer key. Keep the auth token in the Tauri secure store.
* **Quiz screen:** the AI drawer and buttons are removed from the React tree (not hidden with CSS) while a quiz is open. Countdown uses the server offset from `EVENT_HELLO_ACK` plus `performance.now()`; auto-submit at 00:00 and on `EVENT_QUIZ_CLOSED`.
* **Video:** `<video>` cannot set headers, so stream with `?token=`. Downloaded videos set `materials.local_file_path` and play from disk.
* **AI:** local `llama.cpp` sidecar only with at least 4 GB RAM and the model present, otherwise stream from the Hub. Prompt building and lockout apply on both paths.
* **UI:** tokens from `design-system/` only, Nunito and Phosphor from `design-system/assets/`, 52px targets (40px compact allowed only on teacher tables), English and Filipino dictionaries with a runtime toggle, Radix Dialog for dialogs.
* **Tauri:** least-privilege capabilities; only the commands and sidecars you need.

## Layout
`src/{pages,components,services,i18n}`, `src-tauri/` (discovery, sidecar). Document components, state flow and sidecar behavior in `desktop/docs/`.

## Never
Load fonts or scripts from a CDN, edit `contracts/`, `rules/`, `design-system/` or other teams' folders, or call the Hub without the bearer token.
