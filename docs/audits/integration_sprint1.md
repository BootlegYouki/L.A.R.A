# Sprint 1 integration checkpoint ([QA 1.1], #132)

Run on 2026-11-06 against `staging` at `ca4be6c`, with the real Hub window, the desktop app and the Android app.

**Setup:** the Hub ran on a Linux PC (CachyOS, wired, LAN IP `192.168.100.11`; a Tailscale adapter was also present) with a fresh data folder. The desktop app (Tauri debug build) ran on the same PC. The Android app (debug APK) ran on a Pixel 7 emulator. Screenshots are in [`evidence/sprint1/`](./evidence/sprint1/).

## Results

| Step | Result | Evidence | Notes |
|---|---|---|---|
| Hub starts and its window shows the LAN IP | PASS | `hub-window.png` | Shows `192.168.100.11`, not the Tailscale address. The first-run admin PIN form appears below it. |
| `/download` opens from a laptop | PASS on the Hub PC's browser; real laptop NOT VERIFIED | `download-page.png` | The test laptop was on a different Wi-Fi network (`192.168.221.x`), so it could not reach the Hub. The page has Filipino lines: bug #236. |
| `/download` opens in the Android emulator's browser | PASS | `android-download-page.png` | `http://10.0.2.2:8080/download` loads. The APK link answers 404 because no installer is bundled yet; that is [SERVER 6.3] #169. |
| Desktop app finds the Hub by itself | PASS | `desktop-discovered.png` | "LARA Hub 192.168.100.11:8080" listed and connected within about 20 s. |
| Desktop app connects by manual IP, rejects a bad address | PASS (from the #227 review) | PR #227 test run | Not repeated here: the test tool could not type into the Tauri window on this Wayland session. Both were checked when [DESKTOP 1.3] was reviewed on 2026-11-05. |
| Android app connects by manual IP | PASS | `android-find-hub.png` | "Find classroom Hub" is at the bottom of the sign-in screen (scroll down). `10.0.2.2:8080` → Check Hub → back to sign-in. The emulator also listed the Hub on its own, but auto-discovery on a real phone is still not verified. |
| Android app rejects a bad address | PASS | `android-bad-address.png` | "abc" → "Check the address: Copy the numbers and optional port from your teacher…" |
| Hub stopped: both clients show the offline state, no blocking error | PASS | `desktop-hub-down.png`, `android-hub-down.png` | Desktop: banner "Could not reach the Hub" after about 16 s, Home stays usable. Android: signing in shows "Hub unreachable: Check the classroom Wi-Fi or use Find classroom Hub…". No crash, no dialog. |
| Hub started again: both reconnect | PASS | `desktop-hub-back.png`, `android-hub-back.png` | Desktop reconnected on its own about 28 s after the restart. Android reached the Hub on the next sign-in (fresh Hub, so "ID or PIN did not match"). |
| Router's internet cable unplugged for the run | NOT VERIFIED | — | Could not unplug the router. The Hub, desktop and emulator logs show no request to any address outside the LAN, emulator and localhost. |

## Bugs filed

- #236 [SERVER] The `/download` page still shows Filipino lines (English-only since 2026-11-03).

Already tracked, not new: the `/download` APK link answers 404 until [SERVER 6.3] #169 bundles the installers; the UDP beacon may leave through the wrong adapter on PCs with VirtualBox (#200; this PC has none).

## Not verified

- `/download` from a second laptop on the classroom network.
- Auto-discovery (mDNS and UDP beacon) on a real phone.
- Unplugging the router's internet cable.
- Sign-in with real accounts: accounts on the real Hub are [SERVER 2.x] and the client screens are Sprint 2, so this run covers connection only.
- Memory, battery and vendor background limits on a 3 to 4 GB phone.

## How to test (repeat this run)

1. Build the Hub window: in `server/ui` run `npm ci && npm run build`, then in `server/src-tauri` run `cargo build --release --features tauri/custom-protocol`. Start `server/src-tauri/target/release/lara-hub-window` with `LARA_DATA_DIR` set to an empty folder. The window shows the Hub address.
2. Open `http://<hub-address>:8080/download` on another computer on the same Wi-Fi. The page should load.
3. Build and start the desktop app (`npx tauri build --debug --no-bundle` in `desktop/`). The Hub should appear under "Nearby classroom Hubs" and connect. Also type the Hub address into "Hub IP address" and press Connect.
4. Install the Android debug APK (`./gradlew assembleDebug` in `mobile/`) on a phone or emulator. On the sign-in screen, scroll down and press "Find classroom Hub". Type the Hub address (`10.0.2.2:8080` on an emulator) and press Check Hub.
5. Close the Hub. The desktop app shows "Could not reach the Hub" and the phone shows "Hub unreachable" when you try to sign in. Neither app should crash. Start the Hub again: both connect again on their own or on the next try.
