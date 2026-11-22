# CloudWatch patterns adapted for the Hub

Research and implementation: 22 November 2026. This is an offline monitoring
dashboard inspired by CloudWatch; it does not connect to AWS or add an agent,
account, telemetry dependency, REST route, event or database column.

| AWS pattern | Hub implementation |
| --- | --- |
| Linked metric views and periods | Shared 5/10/15/60-minute history, 10/30/60-second groups, average/peak selection across five graphs |
| Number/gauge widgets | Current CPU, memory and storage bars with numeric readings and accessible meter labels |
| Alarm status widgets | CPU/memory/storage warnings with configurable limits; a continuous 30-second window is required |
| Insufficient data | Missing, stale, sleeping or paused readings never produce a healthy warning state |
| Metrics data tables | Latest/minimum/average/peak/count over available samples in the selected history |
| Enlarged graph widgets | Each graph can expand across the dashboard with a taller plot |
| Local diagnostic export | UTC timestamps and allowlisted numeric metrics, exported as CSV through the existing authenticated native save dialog |

Sources: [AWS dashboard widgets](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/create-and-work-with-widgets.html),
[metric periods/statistics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html),
[alarm missing-data behavior](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/alarms-and-missing-data.html),
[Application Signals metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AppSignals-MetricsCollected.html).

## Data and boundaries

`ui/src/monitoring.ts` keeps at most 1,801 samples and one hour in memory.
`monitoringAnalysis.ts` groups available points, keeps empty groups as graph gaps,
computes sample statistics, evaluates warnings and serializes only named numeric
metrics. Pausing stops new native metric calls. Manual refresh takes one reading
while paused. Calls remain sequential, including when pausing/resuming during an
in-flight call. Sign-out stops collection and clears history. History is not
persisted across reload or app exit.

`pages/Monitoring.tsx` owns controls, current meters, graphs, the statistics table
and export. Cards remain current; history/period/statistic controls affect charts,
while table/export use available raw samples in the selected history. Network
graphs/table/export share the selected adapter. CPU/memory/storage warnings refer
to the whole computer, not just the Hub process. Graph legends show latest values;
group descriptions identify the plotted statistic.

`pages/MonitoringWarnings.tsx` stores only validated thresholds in
`lara:monitoring-limits:v1`. Defaults are CPU 85%, memory 90% and storage available
5 GiB. Warning evaluation requires a fresh 30-second span with no missing values
or gaps over five seconds. Values must stay at/over the CPU/memory limit, or at/
below the free-space limit, throughout the window. A nonbreaching reading clears
the sustained warning; any gap makes evaluation unavailable. Warnings are local
advisories and do not stop processes, send notifications or write Activity logs.

`src-tauri/src/import_files.rs` retains its filename/size allowlist and active
admin authorization before and after the save dialog; it additionally accepts
`LARA-Hub-resource-usage.csv`. Export is bounded below its existing 1 MiB cap.
No user IDs, account fields, tokens, request bodies or file contents enter the
monitoring export. Native browser fallback uses a downloadable Blob.

Request rates/latency are existing rolling one-minute aggregates. Averages of
these samples are not per-request weighted latency and overlapping request
counts must not be summed. The current collector does not separate HTTP 4xx/5xx,
so this pass does not claim AWS-style availability or service fault rates. There
is no latency distribution for p95/p99, distributed tracing, persisted metric
history, email delivery or AI queue measurement. Those need additional data
sources and, where shared APIs/schema change, the contract workflow first.

## Verification

- UI production build.
- Eight logic tests: one-hour retention/cap, gaps/zero readings, grouping,
  sample statistics, stale/missing warning windows, recovery, threshold validation
  and allowlisted CSV output.
- Extended browser suite: all five graphs/types, shared ranges/groups/statistics,
  expansion, current meters, pause/manual-refresh/resume, saved/reset warning
  limits, real 30-second warning transitions, CSV contents, responsive layouts,
  unavailable/recovered readings, session resets and expiry.
- Existing native file allowlist test covers the additional CSV filename as well
  as empty/oversized/unknown/path-like filenames.
- Offline invariant guardrail and whitespace check.

Screenshots use synthetic metrics; no classroom data or physical load is used.
