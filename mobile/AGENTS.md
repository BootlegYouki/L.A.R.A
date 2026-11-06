# Mobile Agent Guide

Read the root `AGENTS.md` first. This file adds what is specific to `mobile/`.

## What you are building
A native Android app (package `org.lara.app`) with a **Student** graph and a **Teacher** graph. The Teacher graph has every teacher feature the desktop app has, including building quizzes (root `AGENTS.md` section 1.4). Target hardware is 3 to 4 GB RAM budget phones (Infinix, TECNO, itel, realme): the app **heap must stay under 250 MB**.

## Commands (run in `mobile/`)
`./gradlew test lint`. Run the Hub simulator from the repo root: `python3 scripts/mock_hub.py` (use the emulator host address or the PC's LAN IP).

## Build against the mock hub
* Develop every network feature against `scripts/mock_hub.py` and `contracts/`. Seed accounts: `T-0001`, `123456789012`, `123456789013`, all PIN `1234`; class code `K7M4QX`.
* DTOs use `kotlinx.serialization` with `@SerialName` snake_case matching the contract. Never invent a field.
* Before opening a PR, also run its main flow against the real Hub (`rules/developer-tooling-and-testing.md` section 4, "Checking client PRs against the real Hub"). Routes the Hub answers with `501` stay mock-only; say so in the PR.

## Rules that are easy to get wrong
* **Memory:** never decode full-size bitmaps, never load whole files into memory, page lists, stream downloads. The tutor always uses the Hub stream; clients never build prompts.
* **Offline first.** Screens read from Room. No blocking dialogs when the Hub is unreachable.
* **Sync:** apply a pull response and `next_cursor` in one `@Transaction`; the cursor is an integer, never a time; on `reset: true` wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows. See `rules/database-and-sync.md`.
* **Never store** a PIN hash, another person's LRN or any answer key. Store the token in encrypted storage.
* **Discovery:** acquire a `WifiManager.MulticastLock` for UDP beacons; keep manual IP entry always available; re-connect on `ConnectivityManager.NetworkCallback`.
* **Quiz:** use `SystemClock.elapsedRealtime()` for the countdown (server start time plus clock offset from `EVENT_HELLO_ACK`), save answers to Room on every change, auto-submit at 00:00 and on `EVENT_QUIZ_CLOSED`. Never compose the AI FAB, sheet or tab content while an attempt is `IN_PROGRESS`.
* **Camera:** CameraX with a document guide, compress to JPEG under 800 KB, status `QUEUED_FOR_SYNC` with a client-generated `submission_id` reused on every retry.
* **Transsion and realme battery killers:** use WorkManager for sync and uploads; test with the vendor battery optimization on.
* **UI:** `LaraTheme` and tokens from `design-system/mobile/` only (no `Color(0xFF...)` in screens), Phosphor icons, 52dp targets (56dp primary), English strings in `values/strings.xml`; no translations or language selector (#223/#224).

## Layout
`ui/{screens,navigation,components,theme}`, `data/{local,remote,sync,repository}`, `ai/` for the Hub chat client and quiz lockout. Document navigation, Room and CameraX gotchas in `mobile/docs/`.

## Never
Add Firebase, Google Play Services or a CDN font, edit `contracts/`, `rules/`, `design-system/` or other teams' folders, or ship `!!` and unhandled nulls in production paths.
