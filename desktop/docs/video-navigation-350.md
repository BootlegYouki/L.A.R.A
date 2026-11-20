# Video global navigation — issue #350

## Behavior and scope

[Issue #350](https://github.com/BootlegYouki/L.A.R.A/issues/350): opening a lesson video and selecting Home, Archived classes, another class or Settings left the video on screen. `App.tsx` rendered the active `videoMaterialId` before those destinations, and the Sidebar callbacks did not clear it.

Each of the four global navigation callbacks now clears the active video selection before selecting its destination. The video page unmounts; Home and archive navigation retain their existing destination state, and choosing another class opens that class. The video's own Back callback continues to return to the originating class. No persistence, account scoping, transport, strings, contracts or dependencies change.

Key files: `src/App.tsx` owns the current view; `tests/video-navigation.test.ts` covers navigation through the existing Sidebar/App wiring. The test uses a synthetic cached learner and two classes, with native calls and video rendering stubbed; it does not claim media playback coverage.

## Verification — 2026-11-20

Windows, Node 22.19.0, npm 10.9.3, isolated `node_modules` installed by `npm ci --no-audit`; process TEMP/TMP and npm cache on G:.

- Before the fix, the new parameterized regression failed all four global destinations because one `VideoLessonPage` remained mounted; the Back check passed.
- After the four-handler fix, all five regression cases passed. Each global case also checks the destination page/class or archive visit value.
- Full `npm test`: 22 files, 104 tests passed.
- `npm run lint`: passed with zero warnings.
- `npm run build`: passed (TypeScript and Vite 6.4.5).
- `python scripts/verify_invariants.py`: passed with the project Python environment and UTF-8 output.
- `python -m unittest discover tests`: all 72 shared tests passed with the bundled project Python runtime, project dependency path and UTF-8 mode.
- `git diff --check`: passed.

## Native real-Hub verification

The coordinating Windows run served this branch's frontend in the native Tauri window using the unchanged native binary (native source/config matches this branch). It used the real staging Hub and two approved synthetic learner classes. The current server's legacy seed PIN was used; this does not claim six-digit server acceptance (#339).

Started an actual saved lesson video before each navigation action. Home, Archived classes, another class, Settings and the existing video Back action each unmounted the video, paused the previous HTML media element and displayed the expected destination. Archived navigation focused its heading. All five checks passed again with the real Hub stopped, using cached classes and the downloaded video.

[Real-Hub navigation](screenshots/350-real-navigation.png), [navigation with Hub stopped](screenshots/350-offline-navigation.png).

## Not verified

Linux/macOS, physical second-PC/WAN-disconnected operation and a new installer were not tested. The installed app files were not changed; native checks used synthetic task accounts and the existing task cache on this PC.
