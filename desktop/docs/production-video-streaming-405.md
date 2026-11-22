# Production video streaming — #405

Installer verification at staging `1ddf7bb` exposed a production-only failure: the full video page constructed a direct Hub URL, while production `media-src` permits the asset protocol and loopback preview service. At `http://tauri.localhost/`, WebView2 rejected the unsaved video with `MEDIA_ELEMENT_ERROR: Media load rejected by URL safety check`. A regression test first reproduced the direct Hub URL with its query token.

The video page now leases the existing account/Hub-scoped native material proxy used by attachment previews. The source is an opaque `http://127.0.0.1:<port>/<id>` URL without credentials. Native code still checks account selection and authorization on each request, forwards ranges, and streams the body. Production CSP, native permissions, Server, and contracts are unchanged. The media cache accepts only the context it actually needs (Hub and user ID), so the full-page player does not manufacture a cached account. Unmount releases its lease through the existing bounded idle cache.

Saved-file playback and Save for Home are preserved. A failed proxy acquisition shows the existing video error message; it never falls back to a direct token-bearing Hub URL. Pending stream acquisition shows loading instead of an empty media source.

## Verification

- 198 frontend tests, lint and production frontend build passed. New tests cover the proxy source, lease release, credential-free rendered URL, saved offline playback, and proxy failure.
- A full `npm run tauri build -- --bundles nsis` produced the rebuilt packaged app and NSIS candidate.
- The rebuilt **production** app at `http://tauri.localhost/`, independent of Vite, streamed the real Hub video, played past one second, sought to 15 seconds, and saved it for home study. The actual media source host was `127.0.0.1` with an empty query.
- Closed the entire production process, stopped the Hub, reopened the same isolated profile, opened the saved class and video, and played past one second. The Hub-unreachable banner remained visible.
- The unchanged Hub was built from staging `1ddf7bb` and ran with isolated synthetic data. The previous QA Hub was restored after verification. No existing installed application was replaced: the first candidate's Windows consent prompt was cancelled, and the installed executable's SHA256 remained unchanged.
- Native Rust is unchanged by this fix; the exact staging native suite passed 52 tests (3 existing opt-in tests ignored). The staging shared suite passed 91 tests and the offline invariant scan passed before this frontend-only correction.

Evidence: [production streaming](screenshots/405-production-video.png), [saved video after full offline restart](screenshots/405-cold-offline-video.png).

No physical second-PC/WAN-unplugged or Linux/macOS result is claimed. Installer in-place upgrade verification and prerelease publication resume after this fix is reviewed; the failing candidate was not published.
