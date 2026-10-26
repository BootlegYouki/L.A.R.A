# MOBILE 1.1 scaffold (#23)

## Purpose and entry points

`app` is the single Android module, package/namespace `org.lara.app`, minSdk 26,
compileSdk/targetSdk 35, JVM 17. `LaraApp` exposes a manual `AppContainer` reserved
for services added by later issues. `MainActivity` enables edge-to-edge insets and
opens `LaraTheme` with a Navigation Compose 2.8 type-safe Home/About placeholder.
These are not authenticated Student/Teacher graphs (MOBILE 2.1).

The four `ui/theme/*.kt` files and all six Nunito fonts are copied from
`design-system/mobile/`. Phosphor Regular Info uses the upstream MIT vector path;
licenses are bundled in `assets/licenses/`. The shell uses neutral information
status with icon and text, and a 56dp minimum primary action. Its dark-green button
uses existing tokens for readable white text. Content scrolls for large fonts.
English and Filipino resources have identical keys.

## Build choices

Gradle 8.9, AGP 8.7.3 and Kotlin/Compose compiler 2.0.21 support JDK 17 and SDK 35.
The Compose BOM is 2024.12.01, Navigation is 2.8.5, and Room is 2.6.1.
Versions are pinned to a compatible SDK-35 baseline rather than adopting SDK-36+
dependencies. AGP's compatibility reference:
https://developer.android.com/build/releases/agp-8-7-0-release-notes

Room runtime/ktx dependencies are present, but there are no entities, database,
schema processor or migrations yet. MOBILE 1.3 adds those together with exported
schemas. No Hub client, discovery, workers, permissions, or API calls are added.
Build dependencies need a development-time download; the installed app needs no
network to start. Wrapper distribution verification uses the published SHA-256.

## Data flow and offline state

`MainActivity -> LaraTheme -> LaraNavigation -> Home/About`. Each destination reads
only bundled string resources. There is no connection probe, persistent state,
fake cached data, or asynchronous load. The disconnected placeholder is unconditional
because this scaffold has no connection capability. The Home empty state says no
classes are present; it never claims downloaded content exists. No skeleton is
displayed because no data load occurs; loading states belong to later data screens.

The lead explicitly approved this interpretation for MOBILE 1.1: launch without a
Hub, including airplane mode, with a calm bilingual placeholder and no blocking
error. Room/local data belongs to MOBILE 1.3; cached home to MOBILE 2.2. Settings
and a persistent language toggle belong to MOBILE 2.6; this scaffold follows device
locale and supports `values-tl` resources.

## URL policy and security boundary

The lead explicitly approved replacing the unsupported XML CIDR policy in Tech
Spec sections 3 and 9. `res/xml/network_security_config.xml` permits base cleartext
and the manifest references it without `android:usesCleartextTraffic`.

`data/remote/HubUrlPolicy.kt` supplies `isAllowedHubUrl(String)`: `java.net.URI`
parses syntax without DNS or a new HTTP dependency. HTTP/WS requires exactly four
canonical decimal octets, in 10/8, 172.16/12, 192.168/16 or 127/8. Leading-zero,
abbreviated, numeric-integer, hexadecimal and hostname lookalikes fail closed.
HTTPS/WSS accepts syntactically valid destinations, including IPv6. Userinfo,
invalid ports, malformed URLs and unsupported schemes are rejected. This helper
checks transport policy, not whether a secure host is an authorized school Hub.

**The helper is not installed as a runtime enforcement layer.** MOBILE 1.2 must
call it when constructing `HubEndpoint` for discovery/manual entry; MOBILE 2.2
must enforce it in `HubClient`, covering WebSocket and redirect paths as well as
initial HTTP requests. Do not assume an initial URL check protects redirected
requests. MOBILE 1.1 has no network code or INTERNET permission, so it cannot
make requests before those integrations exist. Remote address format and UI
remain owned by SERVER 6.2 / MOBILE 6.3.

Unit tests in `app/src/test/.../HubUrlPolicyTest.kt` cover both cleartext schemes,
CIDR boundaries, hostnames, lookalikes, invalid octets, secure transports and bad
authorities; none needs a live Hub.

## Local checks and device evidence

From `mobile/` on Windows with JDK 17 and `ANDROID_HOME` configured:

```powershell
.\gradlew.bat assembleDebug test lint --console=plain
```

From the repository root:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONDONTWRITEBYTECODE = '1'
python scripts/verify_invariants.py
python -m unittest discover tests
```

UTF-8 is needed for the guardrail's checkmark output on Windows cp1252 terminals.
The build cache used by this session is `mobile/.gradle-user-home/` (ignored).
Build/test reports are in `app/build/reports/`; review evidence and device steps
are recorded in `docs/evidence/23/README.md`. API/mock-Hub UI integration is not
applicable yet: there are no routes or sockets in this issue. A physical phone
reaches a later mock Hub via the PC's LAN IPv4 address, never emulator 10.0.2.2.
