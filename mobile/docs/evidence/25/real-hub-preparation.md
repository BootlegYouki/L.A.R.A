# Real Hub preparation and smoke checks — 6 November 2026

Hub: unmodified staging ceac7b4, built with Rust 1.99.1 and Visual Studio Build Tools 2022. Separate temporary checkout and synthetic test data; no server source changes.

Passed:
- Download portal HTTP 200 on loopback and PC Wi-Fi address.
- Synthetic teacher login and class listing over REST.
- Authenticated WebSocket EVENT_HELLO_ACK and delta-sync pull.
- Private-Wi-Fi firewall permissions enabled for test Hub only, restricted to LocalSubnet.
- Physical Xiaomi 2311DRK48G on the same Wi-Fi discovered LARA Mobile Test Hub automatically at 192.168.100.2:8080; see real-hub-discovery.png.
- Selecting the discovered Hub and signing in as synthetic teacher opened the empty teacher classroom; see real-hub-teacher-login.png.
- Separate app org.lara.app.sprint31reading; original org.lara.app untouched. No ADB reverse network forwarding. Previous mock queued work preserved on the phone.

Not verified in these preparation checks: class creation/enrollment/approval through mobile against the real Hub, offline/reconnect against the real Hub, physical WAN-unplugged router. Fresh Hub has no classes or posts. Announcement routes are not registered and material downloads/chunks are stubs in staging; complete real-Hub Stream/reader integration remains unavailable. Native reader rendering and mock integration evidence are documented separately.

Hub remains running for user testing. Local test data and logs: %TEMP%/lara-real-hub-mobile31. Build checkout: %TEMP%/lara-real-hub-staging.

## Completed follow-up checks — 6 November

- Created Mobile31Test / TestSection through the physical phone teacher UI against the unmodified real Hub.
- Teacher Stream and Classwork read the synced class and show empty states (real-hub-empty-stream.png, real-hub-empty-classwork.png).
- Stopped the test Hub and cold-launched the isolated app: cached class and Stream open with a nonblocking offline banner (real-hub-cold-offline.png).
- Restarted the same Hub: offline banner clears and class remains (real-hub-reconnected.png).
- Prepared seed learner enrollment and approval through real REST routes, then signed into the learner on the phone and opened its approved class in Stream and Classwork (real-hub-learner-stream.png, real-hub-learner-classwork.png). Enrollment preparation used the API, not the mobile join/approval UI.
- Authenticated probes: /api/announcements returns 404; material download and chunk routes return 501. No server source or database content was patched to bypass those routes.
- USB stay-awake restored to 0; no forwarding routes. Original app untouched. Test Hub remains running.
- Full mobile build/test/lint passed after merging staging; 146 tests each variant, zero failures/errors/skips. Shared suite 67 passed; invariants passed.

Content-rendering, queued-comment and large-text evidence remains the earlier mock/native-fixture run: mobile production source did not change during the staging refresh. Physical WAN-unplugged, budget-phone certification and full TalkBack review remain unverified.
