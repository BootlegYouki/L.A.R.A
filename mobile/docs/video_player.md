# Lesson videos — MOBILE 3.3 / #59

Learners and teachers open videos from the existing Classwork topics or Stream attachments. The detail view keeps the lesson title, video, accessible playback controls, and Save for Home together. There is no separate video library, background service, or picture-in-picture mode.

## Files and boundaries

| File (under `app/src/main/java/org/lara/app/`) | Responsibility |
| --- | --- |
| `domain/VideoRepository.kt` | Pure Kotlin source, download state, and repository contract |
| `ui/screens/classwork/VideoPlayerScreen.kt` | Custom Compose controls around a controller-free Media3 rendering view |
| `ui/screens/classwork/VideoViewModel.kt` | Resolve Room content, saving state, cancel, foreground retries and confirmed removal |
| `ui/screens/classwork/VideoPlaybackSession.kt` | Retained player ownership and Media3 rendering-view handoff |
| `data/repository/VideoRepository.kt` | Room lookup, account-private files, stale paths, completion transaction, pruning |
| `data/media/DownloadManager.kt` | Sequential disk transfer, partial files, validators, finalization |
| `data/media/VideoPlayers.kt` | One bounded-buffer ExoPlayer; network or file-only factory |
| `data/remote/VideoDataSource.kt` | Continuous Media3 reads using bounded HTTP ranges |
| `data/remote/MediaHttp.kt` | OkHttp, session checks, fixed Hub routes, redirects refused |

`AppContainer` supplies the account-scoped repository and player factory. UI code imports the domain interface and Media3, with no DAO, token, HTTP client, or remote DTO imports. `ClassworkDao` adds queries only. Room remains version 3; there are no new columns, schema files, routes or events.

## Playback and authentication

The repository resolves the material from Room. A readable account-private file with the expected byte size and MP4/WebM signature wins before any session or Hub lookup. Missing/invalid files clear the stale path. Otherwise the source names the fixed `/api/materials/{id}/stream` route and expected metadata size.

Media3 uses `OkHttpDataSource`. A wrapping `DataSource` splits reads into at most 256 KiB ranges and reopens at the next byte as needed. A seek closes the old range and starts at Media3's requested byte offset. It does not fetch a whole video to serve a small seek. `206`, `Content-Range` start/end/total and declared length must agree. EOF is returned at the known resource end; malformed or truncated responses fail calmly. Seeking still depends on a valid seekable container and Hub range responses.

The OkHttp interceptor loads the saved session for every request, including seek requests, checks account, expiry and selected endpoint, and sets `Authorization: Bearer …`. It reuses `EnrollmentApi` and `isAllowedHubUrl`. Only the fixed material stream/download paths are accepted; query credentials and redirects are not used. `401`/`403` show the sign-in message without clearing readable offline content. Credentials and response bodies are never logged.

ExoPlayer targets 8 MiB of buffer, with 2–10 seconds of media buffered. Controls are Compose components using Nunito, existing colors, spacing and shapes, and Phosphor glyphs exported from the bundled font. Play/pause is 56dp; buttons and seek bar are at least 52dp. Progress uses the 12dp pill plus text. State includes loading skeleton, buffering, unavailable, playback error and completion. The bottom-nav label fix requested in PR #252 uses one line with ellipsis at large font sizes.

All feature text is in `values/strings.xml`. The initial master prompt requested Filipino, but current root/Mobile instructions require English only (#223/#224). After this conflict was reported, the user explicitly chose the current English-only repository policy. No translations, language selector, or lint exception is added.

The player releases synchronously on a real `ON_STOP` or screen disposal. Configuration recreation keeps the same player in `VideoPlaybackSession`; the old rendering view and listener detach, and the new view uses Media3 `PlayerView.switchTargetView`. Both video ViewModels use the Activity store with account/material keys because the account shell's composition-owned store is cleared during rotation. Position and play intent survive a real stop/restart. Position polling only runs while STARTED. Retry resolves Room/local files again.

## Fullscreen

Fullscreen is saved presentation state (`rememberSaveable`), with no separate media session or orientation lock. A full-window Android ComponentDialog hosting Compose covers the classroom shell. Its own window uses black, fit-center video, immersive system bars with transient edge-swipe reveal, and safe drawing insets for the controls. Compose 1.7 Dialog measures against screen dp excluding system bars and left a visible border on the phone; ComponentDialog avoids that limit without changing dependencies. The underlying Activity window is unchanged. The dialog restores its previous bar visibility/appearance/behavior on stop/disposal and reapplies immersive mode on start; dismissal restores the normal page.

The 52dp fullscreen entry overlays the normal video independently of the transport row, preserving its target at large text sizes. The same play/pause, seek and elapsed/total-time controls render over the video in fullscreen. Playing controls hide after three seconds without interaction, extended by Android's recommended accessibility timeout. Scrubbing suspends hiding; paused controls remain visible. Tapping the video reveals controls and restarts the timeout. CornersOut/CornersIn use the bundled Phosphor glyphs, resource labels and 52dp targets; play/pause remains 56dp. Exit and Android Back dismiss only fullscreen. Black is the explicit lead-requested media background, rather than an invented palette color.

## Save for Home

Videos live under `files/videos/<SHA-256(account-id)>/`. A transfer key hashes material ID, declared byte size, and `updated_at`; filenames never use teacher-supplied titles. The downloader serializes transfers and reuses an already completed valid target rather than duplicating it.

Each request uses `/api/materials/{id}/download` with a bounded Range. Writes use a 32 KiB buffer into `<key>.part`. The exact file length is the resume offset, including interruption inside a response. A strong ETag, otherwise Last-Modified, is kept in `<key>.validator` and sent as If-Range. An incompatible validator never appends to the partial representation. Material IDs refer to immutable file content; metadata/version and expected size are the fallback when the Hub provides no validator.

A `200` during resumption truncates the partial before accepting the full response. It never appends full content to an existing prefix. A `416` for an incomplete prefix discards the incompatible partial and asks the learner to retry. A complete partial after process death can be validated and finalized directly. The downloader checks expected total size, response range/length, excess/truncated bodies, video signature, and available storage. There is no server checksum in the current contract; it cannot promise cryptographic verification against an authoritative checksum. Tests compare final fixture bytes/SHA-256 separately.

Successful bytes move from the partial to a final `.video` file using a same-directory atomic move when available. Only then does the repository recheck account/session/version and update `materials.local_file_path` and `mime_type` in a Room transaction. A failed receipt/version check removes the uncommitted final file. A later metadata sync preserves the completed path through the existing `CacheDao` behavior. Sync pruning removes final and partial files whose materials have disappeared.

Cancel stops the request/job and retains progress; it does not delete saved bytes. While this screen is foreground, transport failures show a paused/offline message and retry after three seconds. User cancellation stops retries. Leaving/backgrounding pauses the transfer; returning resumes a requested save. Process death preserves partial bytes but requires reopening and tapping Resume. There is no new background worker or service. Network validation never depends on an internet-validated Wi-Fi connection.

Download actions follow the displayed phase: DOWNLOADING shows the primary cancel/keep-progress action wired to `pause`; PAUSED shows exactly one primary Resume action wired to `save`, including before the first byte arrives. Other states retain their existing actions. The [PR #258 review-fix evidence](evidence/59/review-fix/README.md) records the on-device Compose regression tests.

Cancellation can close a blocking transport with an exception other than `CancellationException`. The ViewModel checks the transfer's coroutine context before classifying an error or publishing completion, and ignores progress callbacks from cancelled transfers. An old transfer cannot replace PAUSED or a resumed transfer's progress. Active network and file failures still use the existing error handling. In landscape the video viewport has a bounded height; the existing scrolling column keeps transport and download controls reachable. Both Classwork and the player use the design system's **Save for home** label.

## Remove from device

A valid local source displays Saved on this device and a 52dp Phosphor Trash action. Remove opens the lead's confirmation wording in a scrollable body for large-text landscape layouts; Cancel does nothing. Confirm releases local playback before starting removal. The ViewModel cancels and joins this material's transfer, blocks another save during removal, then calls the existing video repository.

`RoomVideoRepository.remove` uses the existing transfer lock. It checks the current account/material, validates a completed hashed `.video` filename directly under that account's private video root, rejects paths shared by another video, and delegates to `DownloadManager.remove`. The downloader validates every target before deleting only this version's and the saved version's `.video`, `.part` and `.validator` files. It never traverses directories. Other videos, handouts and queued files are untouched. A deletion error retains remaining bytes and reports the storage problem; `clearFile(id, originalPath)` runs if the saved file is gone, so Room cannot retain a removed-file receipt. Once the lock is acquired, the short disk/Room cleanup is non-cancellable to keep those two operations consistent.

Removal requires neither a Hub request nor a valid bearer token. Completion resets download progress and resolves the ordinary source again. Online, Save for home can download afresh; offline, the existing unavailable/playback-error state is used. There is no schema, endpoint, dependency, background worker or server change.

## Verification and integration limitations

See [evidence/59/README.md](./evidence/59/README.md) for actual results, commands and limitations. Pure unit tests use deterministic streams or loopback MockWebServer. `VideoMockIntegrationTest` is separately gated by the existing `LARA_SYNC_MOCK=1` convention. `VideoDeviceTest` uses a separate APK ID and a synthetic H.264 fixture; it is not evidence of Python-mock or Rust-Hub video decoding.

The original branch's Python mock serves zero-filled stream bytes and a PDF placeholder for downloads. The existing JVM integration test still verifies that legacy behavior on this branch. PR #259, merged into staging, replaces it with a playable 30-second video and adds Range/If-Range downloads. The [follow-up evidence](evidence/59/follow-up/README.md) records physical-phone streaming, saving and cold-start airplane-mode playback against an unmodified extracted staging snapshot. No shared files or staging changes are merged into this feature branch for testing.

At the original base `86204fe`, Rust material routes were stubs. Current staging has since advanced; the follow-up tests use its Python mock only. A real Rust Hub run and WAN-unplugged integration remain unverified here (the local environment has no `cargo`). Source inspection alone does not prove an HTTP `501` was observed.

The required `.agents/skills/lara-mobile/SKILL.md` was missing locally and on fetched staging. The user explicitly authorized `lara-android-offline` and `android-jetpack-compose` instead, with `lara-architect` first. Restore/maintain the missing team skill in a separate process PR; no skill file is created here.

Media3 references: [DataSource read/open contract](https://developer.android.com/reference/androidx/media3/datasource/DataSource), [ExoPlayer lifecycle](https://developer.android.com/media/media3/exoplayer/hello-world).

The fullscreen/removal continuation report and current evidence are in [evidence/59/fullscreen-removal/README.md](evidence/59/fullscreen-removal/README.md). Earlier evidence above describes historical runs, not the current working tree.
