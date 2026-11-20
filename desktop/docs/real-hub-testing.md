# Real-Hub Desktop testing

Since the Lead decision on 20 November 2026 (#344, Desktop #346), build and test Desktop against the real Rust Hub. Frontend unit tests may stub transport and SQL; they are not integration evidence.

Use a disposable synthetic dataset. Never seed school data or clear an installed app database. From the repository root in a separate PowerShell terminal:

```powershell
# Choose a NEW task-specific directory on G: for each fresh fixture.
$env:LARA_TEST_SEED = '1'
$env:LARA_DATA_DIR = 'G:\Codex\LARA\work\real-hub-desktop-test-data'
Set-Location server/backend
cargo run --bin lara-hub
```

Read `server/backend/src/db/seed.rs` and `server/backend/tests/seed.rs` at the same revision for current synthetic accounts and PIN. At staging `54cfbdc`, the server still uses four digits while the contract requires six; server #339 tracks that mismatch. Desktop alignment is tracked in #340. Record actual revisions and the mismatch without logging credentials or tokens.

In `desktop/`, use Node 22.13+ and native Tauri prerequisites:

```powershell
npm ci
npm run lint
npm test
npm run build
npm run tauri dev
```

Also run the invariant scanner and shared contract suite described in `rules/developer-tooling-and-testing.md` section 4. PR #344 remains draft: until its cleanup lands, some shared tests still launch the retired simulator. Disclose that limitation; those results are not real-Hub proof.

1. Discover the Hub on the classroom LAN and separately connect manually. Check UDP and mDNS independently; an HTTP connection proves neither discovery mechanism.
2. Sign in with synthetic teacher and learner accounts. Exercise classes, join/approve, roster and sync, including authorization failures.
3. Open a Hub-uploaded handout and video. Save for Home; interrupt/retry downloading and check HTTP byte ranges rather than relying on historical simulator limitations.
4. Stop only the task Hub. Check the banner, cached classes/handouts, saved playback and disabled writes without a blocking modal. Close and reopen the full app to test persistence, then restart the same Hub and check reconnection.
5. Record Hub/Desktop revisions, commands, outcomes and screenshots. Disclose physical LAN/WAN, Linux/macOS, installer and AI checks not performed.

Historical feature reports retain their original simulator names and observations for provenance. This cleanup does not turn them into real-Hub results. The unused Home video-test prop and its three dictionary strings were removed: App supplied no caller. Normal Classwork video navigation remains the verification path.

## Cleanup verification (20 November 2026)

On the issue #346 branch based on staging `54cfbdc`: `npm ci`, `npm run lint`, `npm test` (99 tests in 21 files) and `npm run build` passed on Windows. `git diff --check` passed. The unchanged frontend suite contains no simulator process dependency. Existing unit stubs remain unit tests. No native Rust code changed.

The npm install reported 10 dependency vulnerabilities (8 moderate, 2 critical); dependency remediation is outside this issue. SQLite experimental and React test renderer deprecation warnings did not fail the tests. Native real-Hub smoke results are recorded separately by the coordinating test run; this automated result alone proves no network or offline behavior.


## Native real-Hub walkthrough (20 November 2026)

The coordinating Windows run used this cleanup branch and the real Hub source unchanged from staging 54cfbdc. Fresh isolated Hub data was stored on G:. Setup created a synthetic class, approved its learner and uploaded an announcement, PDF and video through the actual Hub APIs; this is not a claim of a Desktop authoring/upload screen.

Native learner sign-in, class/announcement reading, handout Save for Home, video playback and video download passed through the real Hub at 127.0.0.64:8080 (WebSocket 8081). The unused developer test panel was absent. After stopping the Hub, the cached class, handout and saved video opened. Restarting the same durable Hub reconnected the app. A full native-process exit/relaunch with the Hub stopped again reopened the cached class and handout and played the saved video past one second.

The actual current server seed uses four digits; the cleanup branch leaves authentication unchanged. This successful legacy-compatible run does not verify the six-digit client in draft #348. Server #339 is still required for that contract alignment. The task runtime reported secure-credential persistence unavailable, so this run makes no additional OS credential-store claim.

All 72 shared Python checks and the offline invariant scanner passed with the project runtime, PYTHONUTF8=1 and the project dependency path. The shared suite still includes the historical simulator until #344 lands; native real-Hub evidence above is separate.

Evidence: [real-Hub handout](screenshots/346-real-handout.png), [real-Hub saved video](screenshots/346-real-video.png), [handout after full offline restart](screenshots/346-cold-offline-handout.png), [Hub reconnected](screenshots/346-real-reconnected.png).

This run found an existing global navigation bug while a video is open (#350); the video's own Back control works, and that bug is tracked separately. No physical second-PC/WAN-unplugged, Linux/macOS, new installer, quiz or AI test is claimed by this cleanup.
