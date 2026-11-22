# Overview resource monitoring

The Hub Overview shows whole-PC and Hub-process CPU/RAM, network receive/send
rates, free space on the volume holding Hub data, and elapsed time since the Hub
window opened. This is local admin-console monitoring, approved on 22 November
2026. It adds no REST endpoint, WebSocket event, database column or telemetry.

The processor and memory cards show **This computer** and **L.A.R.A Hub** side
by side, matching the graph legends. The computer total includes the Hub and
other programs. User-facing labels say Processor (CPU), Memory (RAM), Network
activity, Network connection, Receiving, Sending, Storage available and Hub
running for. Network activity includes all programs using the chosen connection.

Each chart has a Graph type selector: Line, Area or Bars. Choices are independent
and remain while switching console pages; sign-out or reload restores the defaults
(processor line, memory area, network bars). Switching types keeps the same
history, comparison series, units and resource colors. Bars show ten-second means
for any resource, with correctly formatted percentages, memory or network rates.
Charts label both axes: Time along the bottom (10 minutes ago, 5 minutes ago,
Now), and Processor use (%), Memory used or Transfer speed vertically. Five
numbered row ticks show the actual scale with percentage, memory or rate units;
the ticks share the same scale and grid as the plotted readings in every mode.

## Data flow

- `src-tauri/src/monitoring.rs` owns a reusable `sysinfo` collector. The native
  `hub_metrics` command validates the token against the backend's session store
  and requires the admin role before collecting. Expired/revoked tokens and
  teacher/learner tokens cannot read metrics. No filesystem paths are returned.
- Collection runs on a blocking worker. Only the current process is refreshed;
  CPU and RAM collection excludes unrelated process metadata. CPU percentages
  share a 0–100% whole-machine scale; the Hub process is divided by logical CPU
  count. Hub RAM is resident process memory. Child `llama-server` usage appears
  in whole-PC totals, not the Hub-process series.
- Adapter rates use monotonic elapsed time and cumulative byte-counter deltas.
  New adapters, counter resets, initial samples and intervals longer than five
  seconds return null rates. The adapter whose address matches Hub discovery is
  the automatic selection; the admin may select another. Traffic includes all
  programs on the chosen adapter. Disconnected adapters are unavailable rather
  than silently replaced. Historical network data is selected by adapter name.
- `ui/src/pages/Monitoring.tsx` samples every two seconds while signed in,
  including on other console pages. Calls are sequential. It unmounts and stops
  polling on sign-out/session expiry. `ui/src/monitoring.ts` keeps at most 301
  samples and prunes by ten minutes of elapsed wall time. History exists only in
  React memory and is cleared on sign-out, close or reload.
- Missing readings render as Unavailable and break graph lines. Initial CPU
  sampling needs two readings. Time gaps after sleep also break graph lines.
  A native-command failure keeps historical graphs but clears current cards,
  displays a retry notice and retries automatically. There are no usage alarms.

The UI uses existing Nunito/Phosphor assets and design tokens. CPU uses a green
line chart; RAM uses a blue area chart with a separate dashed Hub-process line.
Network receive/send appear as paired amber/blue bars of ten-second averages
of available samples. Missing buckets have no bars. The cards use matching
green, blue and amber backgrounds, with neutral disk/uptime cards. These colors
identify resources, not usage alerts. Text legends and current values remain
accessible. Network units
are bytes per second (KiB/MiB); memory and disk use binary units. CPU axes are
fixed at 100%, RAM at total physical memory and network at the visible peak.

## Verification

- `npm run build` in `server/ui`.
- `node --test tests/monitoring.test.cjs` checks bounded expiry, real-time graph
  placement, null/nonfinite readings, zero usage and sleep gaps.
- `node tests/monitoring-browser.cjs` with installed Playwright/Edge checks
  values/graphs, adapter selection/loss, responsive layouts, history across page
  switches, unavailable/recovered readings, sign-out clearing/stopping and expiry.
  This suite uses synthetic REST and native replies; it does not measure OS data.
- `cargo test -j1 --features tauri/custom-protocol` in `server/src-tauri` checks
  real Windows collection, warm-up, counter resets, normalization and real backend
  session authorization, including expired/revoked and non-admin sessions.

Windows readings and browser layouts were verified locally. Linux collection
uses the same cross-platform library but has not been run on Linux in this session.
OS APIs used by `sysinfo` do not expose a per-refresh error for every counter;
some OS-level failures may leave the library's previous counter intact. Native
command failures and missing/removed counters are represented as unavailable.

See `docs/evidence/monitoring/` for screenshots and verification results.
