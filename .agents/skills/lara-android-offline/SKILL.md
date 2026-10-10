---
name: lara-android-offline
description: Android patterns for the L.A.R.A mobile app on 3 to 4 GB budget phones with no internet: Room offline mirror and delta-sync transactions, talking to the LAN Hub over cleartext HTTP, WorkManager sync, CameraX homework photos under 800 KB, Media3 video, and keeping the heap under 250 MB. Use when working in mobile/.
---

# L.A.R.A Android: offline, low RAM, LAN only

Read `AGENTS.md` and `mobile/AGENTS.md` first. This skill is the how-to for the hard parts. Where it and a contract or rule disagree, the contract or rule wins.

Target: Infinix, TECNO, itel, realme phones with 3 to 4 GB RAM. **Heap stays under 250 MB.** No Firebase, Play Services, CDN or analytics, and the app must work with mobile data off and the router's internet unplugged.

## 1. Talking to the Hub

* The Hub speaks plain HTTP (`:8080`) and WebSocket (`:8081`) on the LAN. Android blocks cleartext HTTP by default for modern target SDKs, so the app must allow it. The Hub's IP changes between schools and Android's network security config cannot match an IP range, so the simplest correct setting is `android:usesCleartextTraffic="true"` in the manifest. The app only ever talks to the LAN Hub, so this is acceptable. **Test it on the emulator first**; an unexplained connection failure is usually this.
* In the emulator the PC is `10.0.2.2`. The emulator cannot receive UDP broadcast or mDNS, so auto-discovery only works on a real phone. Always keep the manual IP entry working.
* Acquire a `WifiManager.MulticastLock` while scanning for the UDP beacon, or budget phones drop the packets. Release it when done.
* Media routes accept `?token=`; every other route needs `Authorization: Bearer <token>`.
* Never describe the connection as "internet". Say "Hub" or "classroom network".

## 2. Room is the app's only source of truth

* Entities match `contracts/schema/client_offline.sql` exactly (names, nullability, `sync_status`, `local_file_path`). Add a test that compares Room's exported schema to it. `exportSchema = true`.
* **Never use `fallbackToDestructiveMigration`.** Rows with `sync_status = 'QUEUED_FOR_SYNC'` are a pupil's finished quiz or homework photo that has not reached the Hub yet. Wiping them loses a child's work. Write real migrations.
* Screens read from Room `Flow`s, never straight from the network. No queries on the main thread.
* **Applying a sync pull is one transaction, including the cursor.** If the cursor is saved separately the app can skip or repeat changes after a crash:
  ```kotlin
  suspend fun applyPull(r: SyncPullResponse) = db.withTransaction {
      if (r.reset) dao.wipeMirroredTablesKeepingQueued()
      dao.upsertAll(r.records)
      dao.applyTombstones(r.deleted)
      syncState.setCursor(r.nextCursor)      // an integer seq, never a timestamp
  }
  ```
* Mark a row `SYNCED` only from a server receipt. Keep `REJECTED` rows with the reason and show it kindly.
* The student models have no `correct_answer` field at all.

## 3. Sync with WorkManager

* Use `WorkManager` for retry and backoff, and run a sync on connectivity regained, on `EVENT_ANNOUNCEMENT_PUSH`, and periodically.
* **Risk to test before relying on it:** WorkManager's `NetworkType.CONNECTED` is built around internet access. A Wi-Fi network that has no internet may not satisfy it, which is exactly the classroom. Test with the emulator set to a network without internet and on a real phone. If the work never starts, use `NetworkType.NOT_REQUIRED`, drive the trigger from a `ConnectivityManager.NetworkCallback`, and probe the Hub yourself before syncing.
* A dropped socket mid-sync must roll back cleanly. Never crash on timeout.

## 4. Camera: homework photos under 800 KB

* Capture **to a file** (`ImageCapture.OutputFileOptions`), not to memory. A 12 MP `ImageProxy` held in memory is a large part of the heap budget on its own.
* Compress off the main thread: decode with `inSampleSize` toward at most 1920 x 1080, correct the EXIF rotation, write JPEG at quality about 75, and lower the quality in steps until the file is under 800 KB.
* Delete the full-resolution original once the compressed copy is saved. These are photos of children's work.
* Show a document framing guide (see `design-system/design-system.md` section 5.16) and allow attaching an existing photo.
* Queue as `QUEUED_FOR_SYNC` with a client-generated `submission_id`; the upload is idempotent.

## 5. Video: Media3

* One `ExoPlayer`. Create it when the screen is shown and **release it** in `onStop` (or the Compose `DisposableEffect`). A forgotten player holds decoders and buffers.
* Send the bearer header with `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))`.
* The Hub caps each client at 2.0 MB/s, so expect buffering on high bitrates. Keep buffers small with a custom `DefaultLoadControl` on 3 to 4 GB phones.
* "Save for Home": download to app storage with HTTP Range so it resumes after Wi-Fi loss, record `materials.local_file_path`, then play `MediaItem.fromUri(file)`. Never parallel-download several videos.

## 6. Staying under 250 MB

* Never hold whole files or full-size bitmaps. Stream to disk; use `inSampleSize`; `LazyColumn` with stable keys.
* Do not set `largeHeap`; it hides the problem on budget phones.
* Release on `onTrimMemory`. Page long lists instead of loading them.
* Measure with the Android Studio Profiler and `adb shell dumpsys meminfo org.lara.app`. An emulator's numbers are not a 3 GB phone's; say so in your PR.

## 7. Before you open a PR

```bash
cd mobile && ./gradlew lintDebug testDebugUnitTest   # what CI runs
python3 ../scripts/verify_invariants.py              # no cloud deps, values-tl matches values
```
Every user-facing string is in `values/strings.xml` and `values-tl/strings.xml`. Touch targets are at least 52dp (56dp for primary actions and quiz options). The AI chat UI is never composed during an `IN_PROGRESS` quiz attempt.
