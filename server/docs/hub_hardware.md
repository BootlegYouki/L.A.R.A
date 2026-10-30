# Hub PC: Installer, Firewall and Hardware

The installer and firewall sections are step 1.4 (#20). The pilot machine's CPU, RAM and measured capacity are added by the AI spike (1.5, #73) and the load test (6.1, #17).

## Purpose
A school admin installs the Hub on a fresh Windows or Linux PC by double-clicking one file: no terminal, no internet, no developer tools, under 3 minutes (PRD section 10, "Day 0 Deployment"). The installer also opens the Hub's LAN ports in the OS firewall, so learners' phones can connect on the first try (`rules/networking-and-lan.md` section 4.1). The dashboard (5.3) uses the port check to confirm it.

## Key files and entry points
| File | What it does |
|---|---|
| `src-tauri/tauri.conf.json` (`bundle`) | Targets `nsis` (Windows `.exe`), `deb` and `appimage` (Linux). Per-machine install, offline WebView2, the hooks below, `Education` category |
| `src-tauri/windows/hooks.nsh` | NSIS hooks: after install, adds the three named inbound rules with `netsh`; after a real uninstall (not an update), removes them |
| `src-tauri/linux/postinst.sh` | `.deb` post-install: `ufw allow` or `firewall-cmd --add-port` for the three ports when that firewall is running; does nothing otherwise |
| `src-tauri/linux/lara-hub.desktop` | Desktop entry template (launcher name, icon, English and Filipino comment) |
| `src-tauri/.cargo/config.toml` | Links the Visual C++ runtime statically on Windows, so the `.exe` needs no redistributable |
| `backend/src/network/firewall_check.rs` | `HUB_PORTS`, `check()` → `Vec<PortHealth>`: per port, is the Hub listening, and does the firewall allow it (`Allowed`, `Blocked`, `Off`, `Unknown`)? `reachable()` is true only when both are confirmed |
| `backend/tests/firewall_check.rs` | Fails if the installer scripts and `HUB_PORTS` disagree on a rule name, port or protocol |
| `ui/package.json` (`tauri` script) | `npm run tauri build` from `server/ui` |

What is already inside the one executable (no files to install beside it): the captive portal page, CSS and fonts (`include_bytes!`, `routes/portal.rs`), the SQLite engine (`sqlx` `sqlite` feature = `sqlite-bundled`), the migrations (`sqlx::migrate!`), and the Hub window's pages (`ui/dist`, embedded by Tauri).

### Firewall rules
| Port | Windows rule name (`netsh`) | Linux |
|---|---|---|
| TCP 8080 (REST, portal, files) | `LARA Local Hub (HTTP)` | `ufw allow 8080/tcp` / `--add-port=8080/tcp` |
| TCP 8081 (WebSocket) | `LARA Local Hub (WebSocket)` | `ufw allow 8081/tcp` / `--add-port=8081/tcp` |
| UDP 8888 (discovery beacon) | `LARA Discovery Beacon (UDP)` | `ufw allow 8888/udp` / `--add-port=8888/udp` |

These are the three rules named in `rules/networking-and-lan.md` section 4.1. Issue #20's text shows one combined TCP rule called "LARA Local Hub". The rules file outranks the issue (TECH_SPEC section 3, "Firewall").

## Data flow
1. **Build** (a developer, with internet, once per release): `npm ci && npm run tauri build` in `server/ui`. Tauri runs `npm run build` for the window UI, compiles the release binary, downloads the NSIS tools and the WebView2 offline installer, and writes the bundles to `src-tauri/target/release/bundle/{nsis,deb,appimage}/`. Windows builds the `.exe`; the `.deb` and AppImage must be built on Linux (`npm run tauri build -- --bundles deb,appimage`).
2. **Install** (the admin, no internet): the `.exe` asks for administrator rights once (UAC, needed for `netsh`), installs WebView2 silently from its own copy if the PC lacks it, copies the app to `Program Files`, creates the Start menu and desktop shortcuts, then runs `NSIS_HOOK_POSTINSTALL` (delete any old LARA rules, add the three). The `.deb` installs, then `postinst.sh` opens the ports as root.
3. **Run:** the Hub creates `lara.db` in the app data folder on first start. `firewall_check::check()` reports the ports for the dashboard (5.3). It is blocking (it runs `netsh` or `firewall-cmd`), so async callers wrap it in `spawn_blocking`.

No database tables, events or contract routes are touched by this step.

## Gotchas and edge cases
* **The Hub cannot test its own reachability.** A connection from the Hub to its own LAN address never passes the inbound firewall, so `firewall_check` asks the firewall about the rule instead of probing the port.
* **netsh is translated, its exit code is not.** `show rule name="..." dir=in` exits 0 when the rule exists and 1 when it does not, on any Windows language. Only the profile `State ON/OFF` line is parsed; if it is translated, the result is `Unknown`, and the dashboard warns rather than shows a false green.
* **Rule names have spaces and parentheses.** Rust's `Command::arg` would escape the quotes netsh needs, so `firewall_check` passes `name="..."` with `raw_arg`. Checked against an existing Windows rule (`Network Discovery (UPnP-In)` → `Allowed`).
* **No console flashes.** The Hub is a windowed app; each `netsh` call sets `CREATE_NO_WINDOW`.
* **ufw rules are root-only.** A Hub running as a normal user can read `/etc/ufw/ufw.conf` (on or off) but not `/etc/ufw/user.rules`, so a running ufw usually reports `Unknown`. firewalld answers non-root queries.
* **Linux rules stay after removal.** ufw and firewalld port rules have no name, so removing them could close a port the admin opened for something else. Windows rules are named, so the uninstaller removes exactly its own.
* **Reinstalling keeps the rules.** Installing over an older version runs the old uninstaller first (with `/UPDATE` when updating, which skips the rule removal). `NSIS_HOOK_POSTINSTALL` runs after it either way and re-adds the rules.
* **Uninstall can delete the school's data.** Tauri's uninstaller offers a "Delete the application data" checkbox (off by default) that removes the app data folder, which holds `lara.db`. Tell admins to leave it unchecked and to back up first (4.4).
* **WebView2:** the default Tauri mode downloads WebView2 during install, which fails offline. `offlineInstaller` embeds it (about 127 MB larger) and runs it only when the PC lacks WebView2 (Windows 10 before 2021 updates). Windows 11 already has it.
* **Visual C++ runtime:** Rust links it dynamically by default, and a fresh Windows PC may lack `VCRUNTIME140.dll`. `.cargo/config.toml` links it statically. The file applies when cargo runs inside `src-tauri/`, which is what `tauri build` does.
* **`.deb` dependencies:** the `.deb` declares `libwebkit2gtk-4.1-0` and `libgtk-3-0`. Ubuntu 22.04+ desktop has both. On a PC without them, `apt` would need the internet, so use the AppImage there: it carries its own libraries. The AppImage has no post-install step, so open the ports with the dashboard's fix (5.3) or by hand.
* **`postinst.sh` must stay LF.** `src-tauri/.gitattributes` pins `linux/*` to LF; the test checks for `\r`.
* **Installer wizard language:** NSIS has no Filipino language file, so the wizard's own pages are in English. Everything the Hub itself shows stays bilingual.

## Pilot Hub hardware (1.5, #73)
**No pilot school PC is available yet.** The AI spike runs on a developer laptop set up as a simulated school PC. Replace this section with the real machine's details when the pilot school is known, and re-run the evaluation there (the commands are in `docs/benchmarks/ai_model_evaluation.md`).

**Reference school PC (set by the Lead, 2026-10-30):** the weakest likely machine: Linux, a 4-core CPU, **4 GB RAM in total**, no graphics card.

**RAM budget on the reference PC:**
| | MB | Source |
|---|---|---|
| Installed | 4096 | Reference PC |
| Firmware and integrated graphics | −250 | Estimate. A 4 GB PC usually shows Linux 3.7 to 3.9 GB (`MemTotal`) |
| Linux with a light desktop (Xubuntu or Lubuntu), idle | −600 | Estimate. Ubuntu's default GNOME desktop uses about 1.2 GB |
| Hub: backend and window | −350 | Measured on Windows (`lara-hub-window` release build, idle, 2026-10-30): backend 31 MB; the window's WebView2 processes 183 MB private, 358 MB working set. Linux draws the window with WebKitGTK instead. The backend grows as features land; the load test (6.1, #17) measures it under 40 learners |
| Reserve | −450 | Judgment. Files and videos the Hub serves fill the page cache, which competes with the model for RAM |
| **Left for the model (`llama-server`)** | **≈ 2450** | At the chosen slot count |

With Ubuntu's default GNOME desktop the budget drops to about **1.8 GB**, and none of the four measured models fits. A light desktop on the Hub PC matters as much as the model choice. Measure the real figures on the pilot PC with `free -m` while the Hub runs with no `llama-server`.

**Machine the evaluation ran on:**
| | |
|---|---|
| CPU | AMD Ryzen 5 7535HS (6 cores, 12 threads, Zen 3+) |
| RAM | 16 GB DDR5-4800, one module (single channel, about 38 GB/s) |
| GPU | Radeon 660M integrated and RTX 2050, **both unused** (`-ngl 0`) |
| OS | Windows 11 Home 10.0.26200 |
| Inference | llama.cpp `llama-server` build b11938, `win-cpu-x64` |

**How it imitates the reference PC:**
| Trait | How | How close |
|---|---|---|
| No graphics card | `--gpu-layers 0` (CPU only) | Exact |
| 4 cores | `--threads 4` | Close. Turbo clocks may be higher than a school PC's |
| 4 GB RAM | Not limited. Peak RAM is measured, and a model or slot count over the 2450 MB budget fails | Exact (memory use does not depend on the machine) |
| Linux | Not simulated (ran on Windows) | Close. Windows' peak working set and Linux's peak RSS (`VmHWM`, which the runner reads on Linux) both count the mapped model file, the KV cache and the buffers |
| Memory speed | Not simulated | About the same as dual-channel DDR4-2400, about twice a single-stick DDR4 PC |

Speed numbers from this laptop are an **upper bound** for a school PC. A 4 GB PC most likely has one memory module, and generating tokens on a CPU is limited mostly by memory speed, so its tokens/s could be about half of these. Choose a model that clears the targets with room to spare.

**On Linux, a model that does not fit slows down before it fails.** `llama-server` maps the model file into memory. When RAM runs short, Linux drops model pages (for example to cache a video being streamed) and reads them back from disk on the next token, so replies slow to a crawl. That is why the budget keeps a reserve.

## Open items (not built in this step)
* **The AI model in the installer.** The issue asks the Lead whether the model (about 1.5 GB or more) ships in the installer or comes from a USB drive at first run, because it may break "under 3 minutes". The model is not chosen yet (1.5, #73). Until the Lead decides, the installer ships no model and the Hub answers `AI_NOT_AVAILABLE`, as PRD section 5.0 allows.
* **Client installers for the portal.** `/download` serves `<data_dir>/releases/`. Nothing in this step copies the student APK or the desktop installers there. They come from the mobile and desktop teams' builds and are copied there by hand until a later step owns it.
* **Fresh-machine timing.** The "under 3 minutes" and "phones are not blocked" checks need a fresh Windows 10/11 PC or VM and a phone on the classroom router. See the PR's "How to test".
