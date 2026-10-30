# MOBILE 1.2 implementation review — Issue #4

Date: 2026-10-26, Asia/Manila. Validation was recorded before publishing; the user
subsequently authorized committing, pushing, and opening the Issue #4 PR.

## Branch and scope

- Repository verified: `BootlegYouki/L.A.R.A`.
- Fetch completed through normal sandbox escalation (no administrator privileges).
- MOBILE 1.1 commit `7c63b80` is an ancestor of fetched staging, merged by PR #160
  (`728eebb`). Scaffold, theme, network XML, and helper verified on that base.
- New separate branch: `feat/4-mobile-discovery`.
- Base: `origin/staging`, `efa4dddca88309a325a5813a9f13e6cb2d7c7828`.
- Working tree was clean before branching and immediately after switching.
- Only `mobile/` files changed. During validation no commit, push, or PR was made.
  No merge, rebase, reset, stash, device-security change, or firewall change was
  performed. Publishing was separately authorized after implementation review.

## Implementation

See [discovery.md](../../discovery.md) for flow, lifecycle, networking, security,
Android compatibility, offline limitations, and test procedure.

New source files:

- `app/src/main/java/org/lara/app/domain/discovery/HubDiscovery.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/AndroidHubDiscovery.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/HubEndpoint.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/HubEndpointStore.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/HubProbe.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/BeaconParser.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/MulticastLockManager.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/NsdScanner.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/UdpScanner.kt`
- `app/src/main/java/org/lara/app/data/remote/discovery/WifiMonitor.kt`
- `app/src/main/java/org/lara/app/ui/screens/discovery/DiscoveryScreen.kt`
- `app/src/main/java/org/lara/app/ui/screens/discovery/DiscoveryViewModel.kt`
- `app/src/test/java/org/lara/app/data/remote/discovery/DiscoveryLogicTest.kt`
- `app/src/test/java/org/lara/app/data/remote/discovery/HubProbeTest.kt`
- `app/src/test/java/org/lara/app/ui/screens/discovery/DiscoveryViewModelTest.kt`

Modified: app Gradle dependencies (explicit existing Lifecycle 2.8.3 modules plus
test-only coroutines-test 1.9.0), manifest normal networking permissions, LaraApp
container, MainActivity wiring, existing navigation (Discovery startup route,
Home/About retained), both string dictionaries, mobile README, docs index. New
documentation: discovery.md and this evidence directory.

NSD resolves the IPv4/advertised HTTP port with Android's `_lara._tcp.` service
type. UDP receives the existing JSON contract on wildcard port 8888. Both paths
validate endpoint creation through the existing URL helper. IP:HTTP-port identity
deduplicates the sources, with independent ten-second leases. Manual input validates
canonical IPv4, permitted private/loopback ranges, and optional ports 1..65535.
Wi-Fi callbacks restart discovery after connectivity changes without requiring
WAN; START/STOP and cancellation release listeners, sockets, and multicast locks.
The GET `/download` check disables redirects and persists only checked selections.
There is no user authentication or WebSocket session in this sprint issue.

## Automated results

| Check | Result | Evidence |
|---|---|---|
| `gradlew.bat assembleDebug test lint --offline --console=plain` | PASS | [Gradle output](gradle-checks.txt) |
| Unit tests | 13 debug + 13 release executions; zero failures/errors | [Per-suite summary](unit-results.txt): existing four URL-policy tests plus nine discovery/probe/ViewModel tests |
| Android lint | 0 errors, 12 warnings | `app/build/reports/lint-results-debug.html` |
| `python scripts/verify_invariants.py` | PASS | [Output](invariants.txt) |
| `python -m unittest discover tests` | PASS, 66 tests | [Output](repository-tests.txt) |
| `git diff --check` | PASS | Final scope check |

Gradle uses `mobile/.gradle-user-home`. Test reports are ignored generated outputs
at `app/build/reports/tests/testDebugUnitTest/index.html` and the corresponding
release directory. No tests were deleted or lint warnings suppressed. Early runs
exposed an uncached requested Lifecycle version (aligned to the scaffold's existing
2.8.3), an API-30-only request builder (replaced), and quoted numeric JSON ports
(now rejected). The [initial lint failure](initial-lint-failure.txt) is retained;
final checks above passed after corrections. Lint warnings are dependency-update
notices, existing manifest/backups/launcher recommendations, and the approved
cleartext base configuration; no baseline was added.

## Hardware and mock Hub evidence

Phone: model `2511FPC34G`, Android 16/API 36, USB ADB. PC uses router Ethernet
`192.168.100.12`; phone later joined same-router Wi-Fi as `192.168.100.11/24`.

- APK install succeeded; cold launch returned `Status: ok`, `LaunchState: COLD`,
  TotalTime 837ms, WaitTime 841ms before later small presentation/logging changes.
- Initial airplane mode on, Wi-Fi off: no crash or blocking dialog, manual entry
  available. [Screenshot](discovery-offline.png), [UI tree](discovery-offline.xml).
- User manually enabled Wi-Fi. A Hub row appeared automatically, without app
  restart: [LAN screenshot](discovery-lan.png), [UI tree](discovery-lan.xml).
- User tapped the discovered row. Captured banner says "Hub responding: The
  download page is reachable": [Screenshot](selected-hub.png),
  [UI tree](selected-hub.xml). Initial success feedback was too visually subtle;
  success now uses the existing light-green surface and dark-green icon.
- User entered `192.168.100.12:18080` manually and checked it successfully:
  [Manual screenshot](manual-success.png), [UI tree](manual-success.xml).
- Final build cold launch returned `Status: ok`, TotalTime 693ms, WaitTime 697ms:
  [Launch output](final-launch.txt). The [timing log](device-log-final.txt) records
  the first Hub after **2202ms of scanning**. Earlier observed scans were 422ms
  and 617ms (617ms is also in the [lifecycle log](lifecycle-log.txt)). These are
  observed scans, not a
  guarantee across router/device conditions or an app-process-start benchmark.
  [Final discovery screenshot](final-discovery.png), [UI tree](final-discovery.xml).
- User checked unused port `192.168.100.12:18082`. The app showed "Hub unavailable"
  while keeping discovery and manual entry usable:
  [Failure screenshot](manual-unreachable.png), [UI tree](manual-unreachable.xml).
- [Lifecycle log](lifecycle-log.txt) includes multicast lock release/reacquisition
  around lifecycle changes, and a [return UI tree](return-discovery.xml) confirms
  the Hub row remained discoverable after returning to the app.
- [Process-filtered log](device-log.txt) records multicast-lock acquisition.
- The PC's port 8080 was already held by Java PID 9932; it was left untouched.
  Default mock startup failed WinError 10013 even outside sandbox. Existing
  `MockHubServer(http_port=18080, ws_port=18081)` was then run without editing
  scripts, with UDP still 8888. [Host check](mock-host-check.txt) captured the
  actual beacon and HTTP 200 HTML portal. Host UDP timing is explicitly not phone
  discovery timing. The mock has no mDNS advertisement or public health route.
  The temporary mock process was stopped after device testing. A stopped mock's
  advertisement expires after ten seconds; a later blank list is expected.
- After stopping the mock, the row disappeared and "No Hub found yet" appeared:
  [Expiry UI tree](expired-results.xml). No fatal error or blocking dialog appeared.
- Phone blocks ADB input injection (`INJECT_EVENTS`); taps/manual typing require
  the user. No attempt was made to change device security settings.

## Still requiring integration evidence

- Physical invalid-input feedback (pure logic and response handling are unit-tested).
- mDNS discovery against a real advertising Hub; mock supports UDP only.
- Repeated app-open-to-discovery threshold, Wi-Fi loss/return and scanner
  cleanup on real hardware; existing evidence proves one offline-to-Wi-Fi transition
  and lifecycle lock release/reacquisition.
- Budget Transsion/realme reliability, API-26 device behavior, Filipino rendering
  on the phone, and full LAN operation with WAN physically unplugged.
- Actual local-data offline state remains MOBILE 1.3. The calm scaffold state is
  preserved; no Room or cached lesson data is fabricated.
- "Connected to Local Hub" with a real Hub and authenticated session is not claimed
  from a public portal response. Sprint 1 integration sign-off remains outstanding.

## Suggested review metadata

Commit: `feat(mobile): add LAN Hub discovery and manual fallback (#4)`

PR title: `[MOBILE 1.2] Discover classroom Hubs and validate manual connections`

Suggested PR body:

> Closes #4
>
> Adds Android NSD and UDP Hub discovery under a lifecycle-managed multicast lock,
> plus a bilingual startup connection screen with manual IPv4/port fallback.
> All endpoints reuse the approved URL policy. Wi-Fi callbacks restart scanning,
> stale advertisements expire, and a bounded public-portal check distinguishes
> reachability from a future authenticated account connection.
>
> Validation: assembleDebug/test/lint pass (13 unit tests per variant, zero lint
> errors), offline/localization guardrail and 66 repository tests pass. Physical
> phone captures show offline startup, automatic same-router UDP discovery, and
> successful portal response after user selection and manual entry, plus graceful
> handling of an unreachable port. See mobile/docs/evidence/4 for
> exact evidence and remaining mDNS, hardware, timing, and WAN-unplugged checks.
>
> Design deviation: dedicated Discovery is the startup destination; scaffold
> Home/About remain available. Room/authentication/sync are deferred to their
> owning issues. No server, shared-contract, desktop, or root-owned files changed.

The metadata above was prepared for the subsequently authorized commit and PR.

To reproduce this session's alternate-port mock from the repository root:

```powershell
python -u -c "import sys,time; sys.path.insert(0,'scripts'); from mock_hub import MockHubServer; hub=MockHubServer(http_port=18080,ws_port=18081); hub.start(); print('Mock Hub ready',flush=True); time.sleep(3600)"
```

Ctrl+C stops the temporary process. This does not edit the shared simulator.
The final [scope listing](final-scope.txt) records all uncommitted paths.
