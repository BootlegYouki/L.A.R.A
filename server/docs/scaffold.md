# Hub Scaffold (step 1.0, #39)

## Purpose
The skeleton every later server step adds to: one process that opens the database, applies migrations, serves REST on `:8080` and WebSocket on `:8081`, and shows the Hub address in a window. Routes exist for the eight areas in #39 but answer `501` until their step lands.

## Key files and entry points
| File | What it does |
|---|---|
| `backend/src/lib.rs` | `serve(HubConfig) -> HubHandle`: data dir, `db::connect`, `db::migrate`, both listeners. `AppState`, `StartError` |
| `backend/src/main.rs` | Headless `lara-hub`: `LARA_DATA_DIR` (default `./lara-data`), Ctrl+C to stop |
| `backend/src/config.rs` | `HubConfig`: `data_dir`, ports (8080/8081; tests set 0) |
| `backend/src/db/mod.rs` | `connect` (WAL, `synchronous = NORMAL`, `foreign_keys`, `busy_timeout = 5000` on the connect options), `migrate` (`sqlx::migrate!` over `db/migrations/`) |
| `backend/src/error.rs` | `ApiError`, `ErrorCode`: the `{"error":{"code","message"}}` envelope |
| `backend/src/auth/mod.rs` | `AuthUser` bearer extractor, `Role`, `Sessions` (token lookup shared with the WebSocket) |
| `backend/src/routes/mod.rs` | Router: one module per area, `ROUTE_NOT_FOUND` fallback, path-only request log |
| `backend/src/websocket/` | `mod.rs` listener and handshake, `broker.rs` connection registry, `events.rs` `ServerEvent` / `ClientEvent` |
| `backend/src/discovery/mod.rs` | `lan_ip()`: the IPv4 of the adapter with the default route |
| `backend/tests/common/mod.rs` | `TestHub`: real Hub on a temp dir and free ports, with a seeded teacher session |
| `src-tauri/src/main.rs` | Window: starts the backend on its own Tokio runtime, `lan_address` command, shuts the Hub down on exit |
| `ui/` | React + Vite + Tailwind 4 page for the window; tokens from `design-system/desktop/theme.css`, Nunito and Phosphor from `design-system/assets/`, strings in `ui/src/i18n/{en,fil}.json` |

## Data flow and state
* **Start:** create `data_dir`, open `<data_dir>/lara.db`, apply pending migrations, bind `0.0.0.0:8080` and `0.0.0.0:8081`. Any failure returns `StartError`; the window then shows "The Hub did not start" instead of closing.
* **Migrations:** step 1.2 added `001_initial_schema.sql` (see [`database_sync.md`](./database_sync.md)). `backend/build.rs` makes Cargo rebuild when a file is added there, otherwise `sqlx::migrate!` would keep the old embedded list.
* **REST stubs:** register, login and admin setup answer `501 NOT_IMPLEMENTED`. Every other stub runs `AuthUser` first, so without a valid token it answers `401 UNAUTHORIZED` like the mock, and `501` after. Unknown paths and wrong methods answer `404 ROUTE_NOT_FOUND` (the mock matches method and path together). `/api/assignments/{id}/private-comments`, topics, announcements, model, backup and `/download` are not registered yet (their own steps).
* **Tokens:** `Sessions` is an in-memory map that only `TestHub` fills. Nothing issues tokens before step 2.0 (login), so the real Hub rejects every token: REST `401`, WebSocket close `4401`. Step 2.0 replaces the map with the `sessions` table (SHA-256 only).
* **WebSocket:** the first frame must be `EVENT_HELLO` within 10 s. Earlier frames get `EVENT_ERROR` `UNAUTHORIZED` (not JSON: `BAD_REQUEST`); a missing or unknown token closes with `4401`. On success the Hub queues `EVENT_HELLO_ACK` (`server_time`, `hub_id`, `sync_epoch`) and registers the connection in `Broker` under the user id. Each connection has one writer draining a bounded queue of 64 frames; a full queue drops that connection only. Other events are ignored until their steps (AI 5.1, quiz submit 4.1).
* **Hub identity:** `hub_id` and `sync_epoch` come from `hub_meta`, created on first run (step 1.2, [`database_sync.md`](./database_sync.md)).
* **Window:** `ui` calls `lan_address` every 5 s (DHCP can change the address) and shows the IP in large text with a beacon pill: green "Hub is running", muted "No classroom network" or "Hub stopped". While stopped it keeps the address (useful for troubleshooting) but hides "learners can type this address". The window never opens the database.

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
