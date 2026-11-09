# Physical phone verification — 8 November 2026

Device: **Infinix X6731**, Android **14**, SDK **34**, ARM64. Physical memory reports **11,746,152 kB** (about 11.2 GiB), so this is not a 3–4 GB target-device measurement. Android user 0 was active and authorized.

Installed only the separate `org.lara.app.sprint33video` app and its instrumentation runner. The production app was not replaced, cleared or uninstalled. Installed APK hashes and retained local copies are in [installed-artifacts.json](./installed-artifacts.json).

## Results

| Test | Result | Evidence |
| --- | --- | --- |
| Native H.264 frame rendering, 15s forward seek and 3s backward seek | Passed | [stream-save.txt](./stream-save.txt), [stream screenshot](./video-stream-seeking.png) |
| Bounded authenticated HTTP ranges | Passed on synthetic loopback server | [native report](./video-device-online.txt) |
| Cancel at 32 KiB, resume with If-Range, exact SHA-256 and Room completed path | Passed | [native report](./video-device-online.txt) |
| Cold saved-file playback and forward/backward seek in airplane mode | Passed with Wi-Fi disabled, stopped fixture server and expired token | [airplane-playback.txt](./airplane-playback.txt), [mode report](./video-device-offline.txt), [screenshot](./video-offline-seeking.png) |
| English controls, actual 56dp clickable Play target, normal and 200% text | Passed | [ui-controls.txt](./ui-controls.txt), [normal](./video-ui-en-1.png), [200%](./video-ui-en-2.png) |
| Tap Save for Home in the actual player UI; progress, completion, valid local source and playing saved video | Passed | [ui-save.txt](./ui-save.txt), [report](./video-ui-save-report.txt), [progress](./video-download-progress.png), [saved playing](./video-ui-saved-playing.png) |

Each of the four final instrumentation invocations reports `OK (1 test)`. The test class is `org.lara.app.reading.VideoDeviceTest`; methods are `aStreamSeekInterruptResumeAndSave`, `bSavedVideoRendersAndSeeksWithoutHub`, `cEnglishAndLargeFontControls`, and `dSaveForHomeUiShowsProgressAndPlaysLocal`.

The test media is the permitted generated 20-second H.264 pattern. Streaming/download tests use an in-process loopback HTTP fixture on the phone; the UI download fixture is throttled so progress is visible. These results prove native decoding, file playback, client range handling and UI behavior. They do **not** prove classroom-LAN/WAN-unplugged traffic or real Rust-Hub integration. The unchanged Python mock cannot supply valid video bytes, as documented in the parent report.

## Findings and corrections

The first UI assertion measured the 32dp icon instead of its 56dp clickable parent. The accessibility diagnostic showed a 168×168 pixel clickable button at density 3. The test now measures and clicks that parent; the 56dp requirement is retained. [Initial failure](./ui-controls-initial.txt) and [diagnostic](./video-ui-failure.txt) remain available.

Visual inspection found that Material3's default inactive seek track inherited the theme's AI lavender. The player now explicitly uses green and neutral tokens for seek/progress tracks. All four final phone checks passed after the correction. Full Android build/test/lint was rerun; the additional UI-save instrumentation also compiled and passed lint ([output](./test-build-lint.txt)).

Initial screenshots had a floating personal chat overlay. Those captures were kept out of versioned review evidence. The user dismissed the overlay, and the final screenshots were recaptured and visually checked. No screenshot was generated, composited or edited to simulate a passing result.

## Memory and device restoration

[Four in-playback allocation samples](./video-memory-samples.txt) recorded a maximum combined Java/native allocation of **55,919,760 bytes (53.33 MiB)**. These are allocation samples in the instrumentation process, not peak heap or PSS. They do not replace the required pilot-budget-phone profiling run.

Original settings were airplane mode off, Wi-Fi on, mobile data on, and system font scale 0.85. Airplane/Wi-Fi changes were restored in `finally` blocks. The 100%/200% UI checks override Compose density only; they do not change the system font setting. [Final readings](./restored-settings.json) exactly match the original settings. No router/WAN configuration was changed.

The isolated test app remains installed with synthetic saved videos for review. This report records local verification before the user requested PR publication.
