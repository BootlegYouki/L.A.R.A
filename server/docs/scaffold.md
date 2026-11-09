# Hub Scaffold (step 1.0, #39)

## Purpose
The skeleton every later server step adds to: one process that opens the database, applies migrations, serves REST on `:8080` and WebSocket on `:8081`, and shows the Hub address in a window. Routes exist for the eight areas in #39 but answer `501` until their step lands.

## Key files and entry points
| File | What it does |
|---|---|
| `backend/src/lib.rs` | `serve(HubConfig) -> HubHandle`: data dir, `db::connect`, `db::migrate`, both listeners. `AppState`, `StartError` |
| `backend/src/main.rs` | Headless `lara-hub`: `LARA_DATA_DIR` (default `./lara-data`), `LARA_TEST_SEED=1` (test only, see [`auth_sessions.md`](./auth_sessions.md)), Ctrl+C to stop |
| `backend/src/config.rs` | `HubConfig`: `data_dir`, ports (8080/8081; tests set 0) |
| `backend/src/db/mod.rs` | `connect` (WAL, `synchronous = NORMAL`, `foreign_keys`, `busy_timeout = 5000` on the connect options), `migrate` (`sqlx::migrate!` over `db/migrations/`) |
| `backend/src/error.rs` | `ApiError`, `ErrorCode`: the `{"error":{"code","message"}}` envelope |
| `backend/src/auth/mod.rs` | `AuthUser` bearer extractor, `Role`, `require_role`; sessions, PINs and the login limit since step 2.0 ([`auth_sessions.md`](./auth_sessions.md)) |
| `backend/src/routes/mod.rs` | Router: one module per area, `ROUTE_NOT_FOUND` fallback, path-only request log |
| `backend/src/websocket/` | `mod.rs` listener and handshake, `broker.rs` connection registry, `events.rs` `ServerEvent` / `ClientEvent` |
| `backend/src/discovery/mod.rs` | `lan_ip()`: the IPv4 of the adapter with the default route |
| `backend/tests/common/mod.rs` | `TestHub`: real Hub on a temp dir and free ports, with teacher `T-0001` (PIN `1234`) and a session for it in the database; `seed_user`, `open_session`, `login`, `post_json` |
| `src-tauri/src/main.rs` | Window: starts the backend on its own Tokio runtime, `lan_address` command, shuts the Hub down on exit |
| `ui/` | React + Vite + Tailwind 4 page for the window; tokens from `design-system/desktop/theme.css`, Nunito and Phosphor from `design-system/assets/`, English strings in `ui/src/i18n/en.json`, accessed through `useStrings()` |

## Data flow and state
* **Start:** create `data_dir`, open `<data_dir>/lara.db`, apply pending migrations, bind `0.0.0.0:8080` and `0.0.0.0:8081`. Any failure returns `StartError`; the window then shows "The Hub did not start" instead of closing.
* **Migrations:** step 1.2 added `001_initial_schema.sql` (see [`database_sync.md`](./database_sync.md)). `backend/build.rs` makes Cargo rebuild when a file is added there, otherwise `sqlx::migrate!` would keep the old embedded list.
* **REST stubs:** every stub runs `AuthUser` first, so without a valid token it answers `401 UNAUTHORIZED` like the mock. A stub whose route has a role in `scripts/mock_hub.py` then answers the mock's `403 FORBIDDEN` to other roles (step 2.0; `tests/scaffold.rs` reads the mock to check), and `501 NOT_IMPLEMENTED` after. Unknown paths and wrong methods answer `404 ROUTE_NOT_FOUND` (the mock matches method and path together). `/api/assignments/{id}/private-comments`, topics, announcements and model are not registered yet (their own steps); backup and restore are admin-only stubs until step 4.4. `/download` is served by the captive portal added in step 1.3 ([`portal.md`](./portal.md)). Register, login, logout and admin setup are real since step 2.0.
* **Tokens:** since step 2.0 a token is checked against the `sessions` table by its SHA-256, for REST and WebSocket alike ([`auth_sessions.md`](./auth_sessions.md)).
* **WebSocket:** the first frame must be `EVENT_HELLO` within 10 s. Earlier frames get `EVENT_ERROR` `UNAUTHORIZED` (not JSON: `BAD_REQUEST`); a missing, unknown, expired or logged-out token, or a deactivated account, closes with `4401` (a database error during the lookup closes with `1011` instead, so the client reconnects rather than signing out). On success the Hub queues `EVENT_HELLO_ACK` (`server_time`, `hub_id`, `sync_epoch`) and registers the connection in `Broker` under the user id. Each connection has one writer draining a bounded queue of 64 frames; a full queue drops that connection only. Other events are ignored until their steps (AI 5.1, quiz submit 4.1).
* **Hub identity:** `hub_id` and `sync_epoch` come from `hub_meta`, created on first run (step 1.2, [`database_sync.md`](./database_sync.md)).
* **Window:** `ui` calls `lan_address` every 5 s (DHCP can change the address) and shows the IP in large text with a beacon pill: green "Hub is running", muted "No classroom network" or "Hub stopped". While stopped it keeps the address (useful for troubleshooting) but hides "learners can type this address". The window never opens the database.
* **English only (#226):** `useStrings()` returns the English dictionary without language state, a toggle or local-storage access. `ui/index.html` sets `lang="en"`. A previous `lara-hub-lang=fil` preference is ignored; storage being unavailable does not affect the window. No route, event or database schema changes.

## Gotchas and edge cases
* **Two Cargo projects:** `backend/` (what CI builds) and `src-tauri/` (the window, depends on `../backend`). CI does not build the window.
* **Release window build** embeds `ui/dist`, so run `npm run build` in `ui/` first, then `cargo build --release --features tauri/custom-protocol` in `src-tauri/`. A plain `cargo run` there is a dev build that loads `http://localhost:5173` (`npm run dev`).
* **`lan_ip()` can pick a virtual adapter** (VirtualBox, Hyper-V, VPN). Step 1.1 adds the choice of adapter.
* **Port in use:** `serve` fails as a whole; per-port health and "other listeners keep running" come with `PortHealth` (step 5.3).
* **501 logs as ERROR** through `TraceLayer`'s default failure classifier. Expected until the stubs are replaced.
* **No WebSocket frame size limit yet** (tungstenite's default is large). Set one with the broker in step 2.4.
* **Root `.gitignore` ignores `**/storage/`,** which will also hide `backend/src/storage/` (step 3.3). Raise it with the Lead before that step.
* **Window icons** in `src-tauri/icons/` are plain Primary Green placeholders; the installer step (1.4) replaces them.
* **Hub window text is 40/48 Black for the IP** (the Display style, which the design system reserves for welcome screens) so it can be read from across a classroom or projected; reported in the PR.

## How to test #226 (no programming required)

Ask a developer to build and open this branch's Hub window. No account is needed for this address screen.

1. Connect the Hub PC to the classroom router with the router's WAN cable unplugged. Open L.A.R.A Hub. Expect English labels, the Hub address and "Hub is running", with no language button in the header.
2. Close and reopen the window. Expect English again. If this PC previously used Filipino, its saved preference must have no effect.
3. Disconnect the PC from the classroom network and wait at least 5 seconds. Expect "No classroom network" and the English instruction to connect this PC to the router. Reconnect and wait for the address to return.
4. Ask the developer to simulate occupied Hub ports using a separate temporary data folder. Open the window and expect "Hub stopped" and "The Hub did not start" with English recovery instructions, without a crash. Close the temporary instance, free the ports and reopen normally.
5. Save screenshots of the running, no-network and stopped states for the PR. Do not include accounts, tokens or learner data.

## Verification for #226 (2026-11-04)

| Acceptance criterion | Evidence |
|---|---|
| No Filipino dictionary or language toggle in the Hub window | `ui/src/i18n/` contains only `en.json` and `index.ts`; `App.tsx` has no language button; the English dictionary no longer contains language-button labels |
| Keep the English lookup | `useStrings()` still returns `{ t }`, typed by `Dictionary`; all remaining window labels use that dictionary |
| Hub UI builds | `npm run build`: TypeScript passed; Vite transformed 22 modules and built the production UI |
| Server checks pass | `cargo check --all-targets` passed; `cargo test`: 139 passed, 0 failed |
| Offline invariant guardrail passes | `scripts/verify_invariants.py`: `All L.A.R.A offline invariants PASSED` |
| Shared contract/mock checks pass | `python -m unittest discover tests`: 66 passed, 0 failed, 19.912 s |
| Existing Filipino preference cannot change the UI | Browser check saved `lara-hub-lang=fil`, loaded and reloaded running, no-network, stopped and loading states: English with no toggle in every case |
| Screenshot of the native Hub window | [Running](./evidence/issue-226-hub-running.png) and [stopped](./evidence/issue-226-hub-stopped.png), captured from the built Tauri window |

Native verification: `cargo build --features tauri/custom-protocol` passed. Two temporary Hub instances used separate data directories and WebView profiles: the first served normally; the second could not bind the occupied ports and showed the English recovery banner. Both remained English after saving `lara-hub-lang=fil` and reloading, had no language button, and fit their window width. Both instances were closed afterward. The native build needed to refresh its pre-existing stale lockfile; the original lockfile was restored byte for byte after the build, so no lockfile changes are included.

Browser checks used the built UI with controlled `lan_address` responses, not a native Hub or the mock's REST routes. Each state loaded only local assets, raised no page errors, and fit both 960 x 640 and 720 x 480 without horizontal overflow. With no Tauri bridge and unavailable local storage, the English stopped-state banner still rendered. The address scaffold has no remaining buttons or other clickable controls after this change.

Browser screenshots: [no classroom network](./evidence/issue-226-browser-no-network.png), [loading](./evidence/issue-226-browser-loading.png). These use simulated status responses; they do not prove a physical network disconnect.

**Build environment:** this PC's parent `C:/Users/QCU/postcss.config.js` loads an older Tailwind PostCSS plugin, conflicting with this project's Tailwind 4 Vite plugin. The successful UI build used a temporary local empty PostCSS configuration to isolate that parent setting; the temporary file was removed. No dependency, Vite configuration or parent configuration changes are included in #226.

**Antislop review of this change:** Hard Gate PASS: the built UI renders English in all four existing states, with no added controls, assets or external requests. Purpose-Gate PASS: the deletion implements the English-only decision without new visual techniques. Liveliness PASS: the existing Nunito, Hub status, address focus and spacing remain (energy 1, rhythm 1, motion 1). Craftsmanship PASS: dictionary lookups compile, the UI survives missing storage/bridge, and both window sizes fit.

Not verified: a physical router with its WAN cable unplugged, a physical adapter disconnect, and Windows/Linux installer behavior. This cleanup changes no backend behavior, installer, portal, tutor or client code.
