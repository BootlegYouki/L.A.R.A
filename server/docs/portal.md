# Captive Download Portal (step 1.3, #3)

## Purpose
The page a learner or teacher opens right after joining the classroom Wi-Fi, before they have the app. It serves a bilingual (English and Filipino) 3-step Android sideloading guide and the client installers, with no account and no token. Contract: `GET /download` in `contracts/openapi.yaml` ("HTML portal page or binary application package"). PRD: §3.1 (2) "Captive Distribution Portal with Visual Sideloading Guide".

## Key files and entry points
| File | What it does |
|---|---|
| `backend/src/routes/portal.rs` | `router()`: `GET /` and `GET /download` (the page), `GET /download/assets/{*path}` (bundled CSS and fonts), `GET /download/{file}` (client installers from disk) |
| `backend/static/portal/index.html` | The bilingual guide. Compiled into the binary with `include_bytes!`, so it renders with no files on disk |
| `backend/static/portal/assets/fonts.css` | `@font-face` for Nunito (400/700/800/900) and the Phosphor icon glyphs the page uses, all pointing at `/download/assets/web/*`. Bundled |
| `backend/static/portal/assets/tokens.css` | A copy of `design-system/tokens.css` (design tokens as CSS variables). Bundled |
| `backend/static/portal/assets/web/*.woff2` | Nunito and Phosphor web fonts, copied from `design-system/assets`. Bundled |
| `AppState.releases_dir` (`backend/src/lib.rs`) | `<data_dir>/releases`: where the installer binaries are served from |

## Data flow and state
* **The page and its assets are bundled**, never read from disk at runtime. `routes/portal.rs` holds them with `include_bytes!`, so the portal works on a fresh machine and cannot 404 its own CSS or fonts. No database, no token.
* **Installers are served from disk:** `GET /download/{file}` reads `<data_dir>/releases/<file>` (the `releases/` directory named in issue #3). The APK and desktop packages are large and are not committed (`.gitignore` ignores `*.apk`, `*.msi`, `*.deb`, `*.AppImage`); the build or the installer step (1.4) places them in `releases/`. The page links `lara-student.apk` and `lara-desktop-setup.exe`.
* **No events, no writes.** The portal is read-only and emits nothing.

## Offline behavior
This *is* the offline path: it runs on the classroom LAN with no internet. Every asset (HTML, CSS, Nunito, Phosphor) comes from the Hub, never a CDN or Google Fonts (invariant 3.1). If an installer has not been placed in `releases/` yet, the route answers `404` so the browser shows a plain "not found" and the page itself still loads (contract note: "404 missing installer").

## Gotchas and edge cases
* **Path traversal:** `safe_file_name` rejects any installer name containing `/`, `\`, a NUL, or that is `.`/`..`/empty, before touching the filesystem. Axum decodes `%2F`/`%5C` into those separators first, so an encoded `../` is caught too. A `tests/portal.rs` case plants a file one level above `releases/` and proves it is never served.
* **No byte-range or bandwidth cap yet.** Installer downloads are a plain read for now; HTTP Range resume and the 2.0 MB/s per-client token bucket are added across all download routes (and the portal) in step 3.2. Until then a dropped download restarts from the beginning.
* **`404` is plain text, not the API error envelope.** The portal is unauthenticated and consumed by a browser, not a client parsing JSON, so a miss returns `404 Not found`, not `{"error":{...}}`.
* **Bilingual by construction:** every learner-facing string in `index.html` appears in English and Filipino. The invariant scanner's forbidden-pattern check runs over this file in CI.
* **Fonts shipped vs. used:** the page declares Nunito 400/700/800/900 only; a `600` weight falls back, so the HTML avoids `600`.

## Related
[`../AGENTS.md`](../AGENTS.md), [`./scaffold.md`](./scaffold.md), [`../../contracts/openapi.yaml`](../../contracts/openapi.yaml) (`GET /download`), `design-system/tokens.css` and `design-system/assets/` (the bundled tokens and fonts).
