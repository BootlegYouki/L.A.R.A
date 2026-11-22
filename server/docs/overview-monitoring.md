# Overview resource monitoring

The Hub Overview shows whole-PC and Hub-process CPU/RAM, network receive/send
rates, free space on the volume holding Hub data, and elapsed time since the Hub
window opened. This is local admin-console monitoring, approved on 22 November
2026. It adds no REST endpoint, WebSocket event, database column or telemetry.

The Overview uses the full window width with 24px outer gutters. Its sidebar
retains its fixed width and graphs use the remaining space. The width override
is scoped to the Overview shell; other console pages keep their existing layout.
Browser checks cover 720/960/1280/1920px windows and verify full-width use without
horizontal page overflow.

The processor and memory cards show **This computer** and **L.A.R.A Hub** side
by side, matching the graph legends. The computer total includes the Hub and
other programs. User-facing labels say Processor (CPU), Memory (RAM), Network
activity, Network connection, Receiving, Sending, Storage available and Hub
running for. Network activity includes all programs using the chosen connection.

Each chart has a Graph type selector: Line, Area or Bars. Choices are independent
and remain while switching console pages; sign-out or reload restores the defaults
(processor line, memory area, network bars). Switching types keeps the same
history, comparison series, units and resource colors. All chart types use the
selected 10/30/60-second average or peak groups, with correctly formatted units.
Charts label both axes: Time along the bottom (selected start, midpoint,
Now), and Processor use (%), Memory used or Transfer speed vertically. Five
numbered row ticks show the actual scale with percentage, memory or rate units;
the ticks share the same scale and grid as the plotted readings in every mode.

## Data flow

- `src-tauri/src/monitoring.rs` owns a reusable `sysinfo` collector. The native
  `hub_metrics` command validates the token against the backend's session store
  and requires the admin role before collecting. Expired/revoked tokens and
  teacher/learner tokens cannot read metrics. No filesystem paths are returned.
- Collection runs on a blocking worker. Process CPU and RAM are refreshed to find
  Hub descendants; command lines, environment and executable paths are not read
  or returned. CPU percentages
  share a 0–100% whole-machine scale; the Hub process is divided by logical CPU
  count. Hub RAM is resident process memory. Descendant `llama-server` processes
  have their own summed CPU/RAM readings and remain included in whole-PC totals.
- Adapter rates use monotonic elapsed time and cumulative byte-counter deltas.
  New adapters, counter resets, initial samples and intervals longer than five
  seconds return null rates. The adapter whose address matches Hub discovery is
  the automatic selection; the admin may select another. Traffic includes all
  programs on the chosen adapter. Disconnected adapters are unavailable rather
  than silently replaced. Historical network data is selected by adapter name.
- `ui/src/pages/Monitoring.tsx` samples every two seconds while signed in,
  including on other console pages. Calls are sequential. It unmounts and stops
  polling on sign-out/session expiry. `ui/src/monitoring.ts` keeps at most 1,801
  samples and prunes by one hour of elapsed wall time. History exists only in
  React memory and is cleared on sign-out, close or reload.
- Missing readings render as Unavailable and break graph lines. Initial CPU
  sampling needs two readings. Time gaps after sleep also break graph lines.
  A native-command failure keeps historical graphs but clears current cards,
  displays a retry notice and retries automatically. Local resource warnings
  require a fresh, continuous 30-second window; missing data is unavailable.

The UI uses existing Nunito/Phosphor assets and design tokens. CPU uses a green
line chart; RAM uses a blue area chart with a separate dashed Hub-process line.
Network receive/send appear as paired amber/blue bars of selected period statistics
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

## Classroom workload

`backend/src/services/workload.rs` retains at most 60 one-second buckets in
memory: completed HTTP requests, replies with status 400 or higher, average
response time and slowest response time. The window is the last minute; during
startup the rate uses elapsed running time. Times end at response headers, so
they exclude streaming the reply body. Browser preflights are excluded.
Background account/activity requests count toward usage while remaining excluded
from Activity logs. No identifiers, URLs, bodies or headers enter these counters.
All counters reset when the Hub closes; there are no new routes or database fields.

The existing authenticated native command also reads live WebSocket connections
from the broker. Connected apps counts connections, not unique physical devices.
Request and response graphs share the selected history window and graph options.
`ui/src/pages/WorkloadPanel.tsx` presents current workload alongside the existing
computer-versus-Hub resource cards.

Request/response bodies hold RAII guards for material/homework uploads, material
and submission downloads, installer downloads and video streams. Completion or
disconnect releases the guard without buffering data. Document extraction holds
a waiting guard while acquiring its parser slot, then a processing guard until
the worker exits, including after a caller timeout. Finished/failed totals are
since startup; empty/unreadable extraction and timeout count as failed.

`src-tauri/src/monitoring/storage_usage.rs` scans metadata for flat stored material
and homework files and the database/WAL/SHM files every 30 seconds. It skips links,
caps directory entries and returns only byte totals. Temporary uploads, backups
and app/model binaries are outside this breakdown. Disk read/write rates cover
the volume containing Hub data, including other programs; disk latency is not
measured. AI CPU/RAM covers only descendant llama-server processes. There is no
implemented runtime queue source, so queue readings are explicitly unavailable.

Backend tests cover request aggregation/expiry, live counter cancellation,
stream completion/disconnect, parser timeout lifetime and real HTTP polling/error
counts. Native tests cover storage totals and Windows collection; browser tests
cover all five graphs, workload panels, responsive layouts and unavailable states.
