# Video streaming and the 2.0 MB/s cap (step 3.2, #8)

Learners play lesson videos straight from the Hub over the classroom Wi-Fi. `GET /api/materials/{id}/stream` serves a video in byte ranges, so a player starts at once and seeks anywhere without fetching the whole file (PRD FR-3.3). Every file the Hub sends counts against a cap of 2.0 MB/s per signed-in user, so 20 learners use at most 40 MB/s of router bandwidth (PRD FR-3.4, `rules/networking-and-lan.md` section 3).

## Key files

| File | Purpose |
|---|---|
| `backend/src/routes/materials.rs` | `stream`: class members only, VIDEO only, sends the video's own type |
| `backend/src/routes/files.rs` | `serve_stored`: Range, 206 and 416 (`ServeFile`), `If-Range` against a strong `ETag`, `nosniff`, and the cap on every file it sends |
| `backend/src/services/bandwidth.rs` | `BandwidthLimiter`: one allowance per signed-in user, or per device address on the portal; `throttle` wraps a response body |
| `backend/src/routes/portal.rs` | The installers, capped per device address |
| `backend/tests/streaming.rs` | Range behaviour on the real lesson clip, and the cap measured over loopback |
| `backend/tests/portal.rs` | `installer_downloads_are_capped_at_2_mb_a_second_per_device` |

## Data flow

1. **Who.** The bearer header, or `?token=` for a desktop `<video>` tag, which cannot send headers (`MediaUser`). No token is 401.
2. **What.** The material must exist (404 `Material not found`), the caller must teach the class or be an `ACTIVE` learner in it, also while the class is archived (403 `Not your class`), and it must be a VIDEO (404 `Not a video`, contract #259).
3. **Which bytes.** `ServeFile` reads the file off the disk, never into memory:
   - `Range: bytes=a-b` gets 206 with `Content-Range`.
   - No `Range` gets the whole video as 200.
   - A range past the end gets 416 with `Content-Range: bytes */<size>`.
   - An `If-Range` that no longer matches the strong `ETag` gets the whole file, so a player never mixes two versions.
   - Every answer carries `Accept-Ranges: bytes`, the `ETag`, `X-Content-Type-Options: nosniff`, and the video's own type (`video/mp4` or `video/webm`), so `<video>` plays it.
4. **How fast.** The response body goes out in the pieces `ServeFile` reads, 64 KiB each. Before each piece is sent, it books its bytes from the user's allowance and waits until they are paid for. A player that closes the connection (to seek, say) drops the body, and its spending stops at once.

## The cap

- **2,000,000 bytes a second per signed-in user**, across everything they fetch at once: the stream, material downloads (Save for Home, handouts) and homework files, on every device they use.
  - A player that opens a second connection to seek shares the one allowance.
  - So does the same learner on a phone and a desktop.
- **Per user, not per address.** If a classroom router translates addresses, every phone looks the same to the Hub. Per address, the whole class would share 2.0 MB/s; per user, each learner keeps their own.
- **The portal's installers are capped per device address.** No one is signed in there (server TECH_SPEC, Bandwidth).
- **After a pause, a user can get 64 KiB at once.** That's one piece, so a seek starts without waiting; the allowance refills it in about 33 ms. Over any second, a user gets at most 2.0 MB plus that 64 KiB.
- **Sends queue fairly.** The allowance is a token bucket that can go into debt, so one user's downloads are paid for in the order they asked.

## Limits

| Limit | Value | Past it |
|---|---|---|
| Rate | 2,000,000 bytes/s per signed-in user (`BYTES_PER_SECOND`) | Pieces wait their turn |
| Sent at once after a pause | 64 KiB (`BURST_BYTES`) | The rest waits for the allowance |
| Clients remembered | 256 (`MAX_CLIENTS`) | Those with no download running are forgotten (they lose only their saved 64 KiB) |
| Video size | 250 MB (step 3.3, `materials_and_submissions.md`) | 413 at upload |

A 720p H.264 lesson runs at about 0.2 to 0.4 MB/s, so 2.0 MB/s is 5 to 10 times real time. A player that buffers a few seconds after a seek waits well under a second.

## Gotchas

- **Open-ended ranges get the rest of the file at 2.0 MB/s.** The mock answers `bytes=X-` 64 KiB at a time; the Hub sends the rest of the file, as `/download` does and as players expect. A player that seeks closes the old connection, so it never pays for bytes it skipped.
- **`/stream` sends the video's type, `/download` sends `application/octet-stream`.** The contract and the mock send octet-stream for downloads. Save for Home uses `/download`.
- **The Hub PC's own clients are capped too.** A teacher previewing a video on the Hub PC gets 2.0 MB/s, which is plenty for playback.
- **Uploads are not rate-limited.** They're bounded by the 250 MB cap (`materials_and_submissions.md`).
- **Tests use 127.0.0.x addresses.** Every 127.0.0.x is loopback on Windows and Linux, and the Hub sees each as a different address, so the tests can act as many devices.

## Decisions for the Lead

1. **Seek start:** 64 KiB goes at once after a pause, so over any second a user can get 2.0 MB plus 64 KiB.
2. **Units:** 2.0 MB means 2,000,000 bytes, not 2 MiB (2,097,152).
3. **Open-ended ranges:** the rest of the file at the capped rate, instead of the mock's 64 KiB pieces.
4. **Hub PC:** its own clients (loopback) are capped like everyone else.
5. **Type:** `/stream` sends `video/mp4` or `video/webm` (the mock sends `video/mp4`); `/download` stays `application/octet-stream`.

## Acceptance evidence

| Criterion | Evidence |
|---|---|
| Students can instantly scrub to any point in a 15-minute video lesson | `a_range_gets_206_with_just_those_bytes`, `a_seek_gets_the_video_from_that_point_on`, `if_range_resumes_only_while_the_video_is_unchanged`; the live check times the first byte after seeks in a 200 MB video (in the PR) |
| 20 simultaneous video streams do not exceed 40 MB/s total router bandwidth | `twenty_learners_streaming_at_once_stay_within_40_mb_a_second`: 20 MB in 0.51 s, 39.1 MB/s in total, each learner at least the 2.0 MB/s time; the live check measures 20 and 40 learners (in the PR) |
| Range seeking without buffering the whole file | `a_seek_gets_the_video_from_that_point_on` |
| The cap is per user, not per connection or address | `two_streams_of_one_learner_share_2_mb_a_second`, `a_download_shares_the_allowance_with_a_stream`, `one_learner_on_two_devices_shares_2_mb_a_second`, `two_learners_on_one_address_each_get_2_mb_a_second` |
| `cargo check --all-targets && cargo test` pass | In the PR |

## Manual check (WAN unplugged)

1. Start the Hub with the test accounts (`LARA_TEST_SEED=1 cargo run --release --bin lara-hub` in `backend/`). Sign in teacher `T-0001` and learner `123456789012` (PIN `1234`), create a class, and approve the learner.
2. As the teacher, upload a real 720p MP4 as `VIDEO`:
   ```
   curl -H "Authorization: Bearer $T" -F classroom_id=$C -F title=Lesson -F file_type=VIDEO -F file=@lesson.mp4 http://<hub>:8080/api/materials
   ```
3. As the learner, fetch the first 100 bytes and then the whole file:
   ```
   curl -i -r 0-99 -o part "http://<hub>:8080/api/materials/$V/stream?token=$L"
   curl -o whole -w "%{speed_download}\n" "http://<hub>:8080/api/materials/$V/stream?token=$L"
   ```
   **Expect** 206, 100 bytes and `Content-Type: video/mp4` for the first, and about 2,000,000 bytes a second for the second.
4. Run two of the full downloads at once with the same token. **Expect** about 1,000,000 bytes a second each.
5. On a phone on the classroom Wi-Fi, open `http://<hub>:8080/api/materials/$V/stream?token=$L` in the browser. **Expect** the video to play and to keep playing when you seek forward and back.
6. On the phone, download an installer from `http://<hub>:8080/download`. **Expect** about 2 MB/s.
