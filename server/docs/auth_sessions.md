# Auth and Sessions (step 2.0, #42)

## Purpose
Gives every client an identity on the Hub: learners register themselves, everyone signs in with an LRN or ID and a 4-digit PIN, and every later route and the WebSocket know who is calling and in which role. Behavior follows `scripts/mock_hub.py` (PRD FR-1.0, FR-1.1), because the mobile and desktop apps were built against it. The single Hub admin is created once, from the Hub PC itself.

## Key files and entry points
| File | What it does |
|---|---|
| `backend/src/routes/auth.rs` | `POST /api/auth/register` (learners only), `/login`, `/logout` |
| `backend/src/routes/admin.rs` | `POST /api/admin/setup` (loopback only); `/api/admin/users` stays an admin-only stub until 2.3 |
| `backend/src/auth/mod.rs` | `AuthUser` extractor (bearer token → `{ id, role }`), `Role`, `require_role` (the mock's 403 messages) |
| `backend/src/auth/sessions.rs` | `open` (in the caller's transaction), `user_for_token` (REST and `EVENT_HELLO`), `close` |
| `backend/src/auth/token.rs` | `new_token` (32 random bytes, hex), `token_hash` (SHA-256 hex), `SESSION_TTL_MS` (12 h) |
| `backend/src/auth/pin.rs` | `is_valid_pin` (`^[0-9]{4}$`), `PinHasher` (argon2id on the blocking pool, one hash per core at a time) |
| `backend/src/auth/limiter.rs` | `LoginLimiter`: 5 attempts per minute per `lrn_or_id` |
| `backend/src/db/users.rs` | `find_by_lrn`, `insert` (records the `users` sync revision), `admin_exists` |
| `backend/src/models/user.rs` | Wire types `User` and `AuthSession` (no `pin_hash` field exists) |
| `backend/src/routes/body.rs` | `JsonBody`: request bodies read like the mock (`Invalid JSON`, `Missing: a, b`) |
| `backend/src/routes/mod.rs` | Area stubs with the mock's role guards (`not_implemented_teacher` / `_student` / `_admin`) |

## Data flow and state
* **Register:** check fields, then the PIN (`400 PIN must be 4 digits`), hash it (outside any transaction), then one `begin_immediate` transaction: insert the `STUDENT` row, `record_revision(Scope::global(), "users", ...)`, open the session. A taken `lrn_or_id` (a learner's or a teacher's) is `409 ALREADY_EXISTS`; two registrations of one LRN at once give one 200 and one 409. `ADMIN-0001` is always taken, even before setup, so no learner can block the admin account.
* **Login:** `LoginLimiter::try_attempt` first (429 `TOO_MANY_ATTEMPTS`, `Retry-After` 1 to 60 s), then the account. Unknown LRN, deactivated account and wrong PIN all answer `401 Wrong LRN or PIN`. Success resets the LRN's count and opens a session.
* **Session:** `sessions` row `(token_hash, user_id, created_at, expires_at = now + 12 h)`. The raw token goes to the client only. Opening a session also deletes expired rows.
* **Every signed-in request and `EVENT_HELLO`:** `user_for_token` joins `sessions` and `users`; a token works while `expires_at >= now` and `users.is_active = 1`. So logout, expiry and deactivation take effect on the next REST request and the next `EVENT_HELLO`. A WebSocket that is already open stays registered in `Broker` until it reconnects: 2.3 (deactivation) and 2.4 (broker) should close a user's sockets when the account is deactivated, and the token's socket on logout. Sessions are in SQLite, so a Hub restart keeps everyone signed in.
* **Logout:** deletes only the caller's session; the user's other devices stay signed in.
* **Admin setup:** 403 `Only on the Hub PC` unless the peer is loopback; `need(full_name, pin)`; 409 `ADMIN_EXISTS` if an admin exists (before the PIN check, as the mock); hash; then in the write transaction check again and insert `ADMIN-0001` with role `ADMIN`. No sync revision for the admin: it is never sent to a client.
* **Role guards on stubs:** each stub gets exactly the role its route has in the mock (`@route(..., role=...)`), so an admin creating a class or a teacher on admin routes already gets the mock's 403. Backup and restore are admin-only stubs until 4.4.

## Gotchas and edge cases
* **The limiter counts attempts when they start.** Counting failures after the PIN check would let 40 parallel guesses all pass before the first failure lands (argon2 takes about 20 ms). Recording each attempt up front and clearing on success gives the same "failed attempts only" result (TECH_SPEC Q7) without that hole. A refused attempt is not recorded, so retrying while locked never extends the wait.
* **429 is not in the contract yet.** TECH_SPEC Q7's `contract-change` (`429 TOO_MANY_ATTEMPTS` + `Retry-After`) was requested on 2026-11-01. Until it merges, clients see an unknown error code on the 6th wrong PIN in a minute.
* **A clock set back does not lock anyone out for hours.** The Hub PC has no internet to correct its clock; attempts dated after "now" are dropped, so a lockout stays under a minute.
* **`lrn_or_id` has no maximum length in the contract.** The limiter keys on its first 64 characters, so a huge value cannot grow the map without bound.
* **Hashing memory:** each argon2id hash holds 19 MiB. `PinHasher` allows one hash per logical CPU at a time (`available_parallelism`): 4 × 19 MiB on a 4-core, 4-thread PC, 8 × 19 MiB on 4 cores with 8 threads. Without the cap, 40 learners signing in at once would need about 760 MB on a 4 GB PC that also runs the AI model.
* **The permit lives inside the blocking task.** A client that disconnects drops its request, but a blocking hash cannot be cancelled. If the permit were released with the request, a loop of register-and-disconnect could pile up hashes past the cap; holding it until the hash really ends keeps the bound (`a_cancelled_hash_keeps_its_permit_until_the_work_ends`).
* **Debug builds:** `[profile.dev.package.argon2] opt-level = 3` in `Cargo.toml`. Unoptimized, one hash takes about 430 ms instead of about 20 ms, which makes every auth test slow.
* **Remote/Suspension Mode and loopback:** `cloudflared` reaches the Hub from 127.0.0.1, so a tunnelled request to `/api/admin/setup` would look local. It is safe only because an existing admin turns the tunnel on, after which setup answers 409. Nothing may start the tunnel before setup.
* **Logs:** the request span records method and path only, SQLx logs SQL text without bound values, and no handler logs a PIN, token or LRN. Checked at `RUST_LOG=debug` (see Measured below).
* **A failed session lookup on `EVENT_HELLO` closes with `1011`, not `4401`.** 4401 tells the client its token is bad, so a database hiccup would sign everyone out.
* **The root `.gitignore` drops every `models/` folder** (meant for AI weights). `backend/.gitignore` re-includes `src/models/`; keep it when adding `models/classroom.rs` and the rest.
* **The PIN is accepted as a JSON number too** (`1234`), as the mock does (`str(b["pin"])`). Non-ASCII digits such as `١٢٣٤` are refused.

## Measured (2026-11-01, dev laptop, release build, upper bound for a school PC)
Ryzen 5 7535HS (12 logical cores, so 12 hashes at once), 16 GB. `lara-hub` on a fresh data dir; Python clients on the same machine.

| Load | Result | Wall time | p50 | p95 |
|---|---|---|---|---|
| 40 registrations at once | 40/40 ok | 1235 ms | 1066 ms | 1211 ms |
| 40 logins at once | 40/40 ok | 414 ms | 239 ms | 402 ms |

Process working set: 16 MB idle, **peak 245 MB** during the load (12 × 19 MiB of hashing plus the idle Hub), back to 18 MB after. On the 4-core reference Hub the cap is 4 hashes (about 76 MiB of hashing) if it has 4 threads, 8 if it has 8, and logins queue longer; measure there on integration day (TECH_SPEC section 12).

## Tests
| Test | Proves |
|---|---|
| `tests/auth.rs` (17) | Register opens a learner session with a 64-hex token and 12 h expiry; users revision recorded; 409 for a taken LRN, also for two at once; bad PINs 400, numeric PIN accepted; `Missing: lrn_or_id, pin`; `Invalid JSON`; wrong PIN and unknown LRN both 401; deactivated cannot log in; logout kills REST and WebSocket for that token only; 6th failure in a minute is 429 with `Retry-After` while another learner still logs in; a success resets the count; no PIN in any `users` or `sessions` column; teacher on admin routes 403; admin is not a teacher; backup and restore admin-only |
| `tests/sessions.rs` (6) | Only the SHA-256 is stored; expired and deactivated tokens 401; deactivated `EVENT_HELLO` closes 4401; sessions survive a Hub restart |
| `tests/admin_setup.rs` (8) | Setup once then 409; a learner cannot register `ADMIN-0001` before setup; 409 wins over a bad PIN; field checks; admin reaches admin routes and has no sync revision; admin logs in after setup; 403 when called through the LAN address; two setups at once make one admin |
| `tests/scaffold.rs` `stub_role_guards_match_the_mock` | Reads every `role=` route in `scripts/mock_hub.py`; for each one this Hub registers (31), the right role gets 501 and every other role 403 |
| `auth::limiter::tests` (8), `auth::pin::tests` (6), `auth::token::tests` (3), `auth::tests`, `error::tests`, `routes::body::tests` | Limiter window, reset, clock going back, key bound; argon2id format, salting, garbage hashes, 16 concurrent hashes, a cancelled hash keeps its permit; token and SHA-256 format; role strings and 403 messages; `Retry-After`; body parsing |
