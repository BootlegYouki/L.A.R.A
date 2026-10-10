---
name: lara-desktop-web
description: Desktop client patterns for L.A.R.A (Tauri 2, React 19, strict TypeScript, Tailwind 4, @tauri-apps/plugin-sql) working offline against the LAN Hub: local SQLite and atomic sync, Tailwind design tokens and bundled fonts, bilingual dictionaries, reaching the Hub from the webview, and the quiz timer. Use when working in desktop/.
---

# L.A.R.A Desktop: Tauri 2 + React 19, offline first

Read `AGENTS.md` and `desktop/AGENTS.md` first. Where this skill and a contract or rule disagree, the contract or rule wins. The app is a Vite single-page app inside Tauri. It is not Next.js.

## 1. TypeScript: strict, no `any`

* `strict: true`. Never write `any`. JSON from the Hub is `unknown` until a type guard has checked it; write small guards (`isSyncPullResponse(x: unknown): x is SyncPullResponse`) instead of casting.
* Types mirror `contracts/openapi.yaml` and keep the **snake_case** field names on the wire. Convert only at the edge if you must.
* Student types have no `correct_answer` field.

## 2. Local SQLite with `@tauri-apps/plugin-sql`

* ```ts
  import Database from '@tauri-apps/plugin-sql'
  const db = await Database.load('sqlite:lara_client.db')   // path is under the app config dir
  await db.execute('UPDATE materials SET local_file_path = $1 WHERE id = $2', [path, id])
  const rows = await db.select<Row[]>('SELECT * FROM announcements WHERE classroom_id = $1', [cid])
  ```
  Parameters are `$1`, `$2`. Migrations run when the connection loads.
* Tables mirror `contracts/schema/client_offline.sql`. Queued work has `sync_status = 'QUEUED_FOR_SYNC'`.
* **Atomic sync is the hard part.** The issue says to apply a pull and its `next_cursor` in ONE transaction. The plugin's JavaScript API documents only `execute` and `select`; it has no transaction handle, and a pooled connection means separate `BEGIN` and `COMMIT` calls are not guaranteed to run on the same connection. **Do not assume they do.** Prove an approach in a short spike and write the result in `desktop/docs/`. The safe default is one Tauri command written in Rust that opens a real transaction, applies the records, tombstones and cursor, and commits. Test that killing the app mid-pull leaves the database unchanged.
* The cursor is an integer. On `reset: true`, wipe the mirrored tables but keep rows still `QUEUED_FOR_SYNC`.

## 3. Reaching the Hub from the webview

* The Hub is plain HTTP on the LAN. Decide once how the webview talks to it and write it in `desktop/docs/`: either `fetch` from the webview (then the Hub must send CORS headers, which the mock hub does and the real Hub must match, and the Tauri CSP must allow the Hub's origin), or Tauri's HTTP plugin, which sends the request from Rust. Test both the REST calls and the WebSocket against `scripts/mock_hub.py`.
* HTML5 `<video>` cannot set headers: use `GET /api/materials/{id}/stream?token=` and make sure the CSP allows media from the Hub.
* Keep the token in the OS-backed store the Tech Spec chooses. Never log tokens, PINs or LRNs.
* WebSocket: send `EVENT_HELLO` with the token, reconnect with backoff 1 s, 2 s, 4 s, max 10 s, and never show a blocking dialog when the Hub is gone. Show the offline banner.

## 4. Tailwind 4 and the design system

* Tailwind 4 is configured in CSS. Use `@import "tailwindcss";` and define tokens with `@theme { --color-...: ...; --font-sans: "Nunito", sans-serif; }`.
* The repo's `design-system/desktop/tailwind.theme.ts` is a JavaScript config. Tailwind 4 does **not** pick it up automatically; load it explicitly with `@config "../path/to/tailwind.theme.ts"` or port its values into `@theme`, and keep the two identical (the tests compare them).
* **Tokens only.** No invented hex values, no gradients. Purple is only for the AI tutor. Icons are Phosphor from `design-system/assets/`.
* **Fonts are bundled**, with `@font-face` pointing at files in the app, never a remote stylesheet. A remote font import fails the offline check.
* Buttons, inputs and list rows are at least 52 px tall (56 px for primary actions and quiz options); 40 px compact rows are allowed only in teacher tables. Visible focus rings, 4.5:1 contrast.

## 5. React 19 notes

* `ref` is an ordinary prop on function components; you do not need `forwardRef`.
* Prefer a small store (Zustand, as the issue says) over deep context. Screens read from SQLite through the store; the network only refreshes it.
* Every screen has a loading skeleton, an empty state and an offline state, each in English and Filipino.

## 6. Bilingual text

* All user text comes from `desktop/src/i18n/en.json` and `fil.json`. The two files have the same keys; the invariant scanner fails the PR if Filipino is missing one. There is a runtime language toggle in Settings.
* Never hardcode a visible string, including `aria-label`s.

## 7. Quiz screen

* Countdown from the server time offset in `EVENT_HELLO_ACK` plus a monotonic clock (`performance.now()`), never `Date.now()` (a wrong classroom clock must not change the timer).
* Colors: green above 5 minutes, yellow at 5, red and pulsing at 2. Auto-submit at 00:00 and on `EVENT_QUIZ_CLOSED`.
* On network loss keep counting, finish locally and save as `QUEUED_FOR_SYNC`.
* **Remove the AI drawer from the tree** (do not hide it) while a quiz attempt is `IN_PROGRESS`. No AI element may exist in the DOM.

## 8. Before you open a PR

```bash
cd desktop && npm ci && npm run lint && npm run build     # what CI runs
npm run tauri dev                                          # run it against scripts/mock_hub.py
python3 ../scripts/verify_invariants.py
```
Stop the mock hub while the app runs and confirm it shows the offline state with no blocking error.
