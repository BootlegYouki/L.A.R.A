# Desktop Agent Guide

Read the root `AGENTS.md` first. This file adds what is specific to `desktop/`.

Before starting/resuming a task and before submitting a PR, check the repository's [Announcements](https://github.com/BootlegYouki/L.A.R.A/discussions/categories/announcements), including updated threads and Lead follow-up comments. Follow the announcement-check workflow in [the Desktop skill](../.agents/skills/lara-desktop/SKILL.md#check-announcements-and-current-status).

## What you are building
A Tauri 2.x client (React 19, TypeScript strict, Tailwind) for student laptops, lab PCs and teachers. Learners get the offline classroom; teachers get **every teacher feature, the same set as the mobile app** (create class, roster approval, announcements, materials, assignments, grading, quiz builder, live monitor).

## Commands (run in `desktop/`)
`npm run build` (`tsc && vite build`) must pass with zero type errors. Start the real Hub using [real-Hub testing](docs/real-hub-testing.md).

## Build against the real Hub
* Develop and test every network feature against the real Hub and `contracts/`. Follow [real-Hub testing](docs/real-hub-testing.md), using isolated synthetic data.
* Use interfaces matching `contracts/openapi.yaml` (snake_case keys, no `any`). Never invent a field; ask for a contract change.
* Before opening a PR, exercise the main flow in the native app against the real Hub, stop it and verify cached/offline behavior. Record failures and unimplemented routes.

## Rules that are easy to get wrong
* **Offline first.** Every screen reads from local SQLite. No blocking "no connection" dialogs; show the standard offline banner and cached data.
* **Sync:** apply a pull response and its `next_cursor` inside one transaction; on `reset: true` wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows. The cursor is an integer, never a time. See `rules/database-and-sync.md`.
* **Never store** a PIN hash, another person's LRN, or any answer key. Keep the auth token in the Tauri secure store.
* **Quiz screen:** the AI drawer and buttons are removed from the React tree (not hidden with CSS) while a quiz is open. Countdown uses the server offset from `EVENT_HELLO_ACK` plus `performance.now()`; auto-submit at 00:00 and on `EVENT_QUIZ_CLOSED`.
* **Video:** `<video>` cannot set headers, so stream with `?token=`. Downloaded videos set `materials.local_file_path` and play from disk.
* **AI:** download the model file from the Hub, then run inference on the device with no network. The app builds the prompt from `contracts/ai/socratic_system_prompt.txt`. Without the model show "Download the AI model from the Hub"; if it cannot run, "The AI tutor is not available on this device". Quiz lockout still applies on the device.
* **UI:** tokens from `design-system/` only, Nunito and Phosphor from `design-system/assets/`, 40px buttons and icon buttons and 48px tabs and drawer items, as in Google Classroom, English text in `src/i18n/en.json` with no language toggle, Radix Dialog for dialogs.
* **Tauri:** least-privilege capabilities; only the commands you need.

## Layout
`src/{pages,components,services,i18n}`, `src-tauri/` (discovery). Document components, state flow and Hub chat behavior in `desktop/docs/`.

## Never
Load fonts or scripts from a CDN, edit `contracts/`, `rules/`, `design-system/` or other teams' folders, or call the Hub without the bearer token.
