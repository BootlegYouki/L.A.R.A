import { invoke } from '@tauri-apps/api/core';
import { useEffect, useRef, useState } from 'react';
import { useStrings } from '../i18n';
import { useWorkloadCards } from './WorkloadPanel';
import DashboardCards, { type DashboardItem } from './DashboardCards';
import MonitoringWarnings from './MonitoringWarnings';
import { aggregate, finite, metricsCsv, seriesPath, summarize, type Statistic } from '../monitoringAnalysis';
import { saveImportFile } from '../importFiles';

import { areaPath, retainSamples, INTERVAL_MS, type Metrics, type Sample } from '../monitoring';

const number = (value: number) => value.toLocaleString('en', { maximumFractionDigits: 1 });
function bytes(value: number | null, unavailable: string): string {
  if (value === null || !Number.isFinite(value)) return unavailable;
  if (value >= 1024 ** 3) return `${number(value / 1024 ** 3)} GiB`;
  if (value >= 1024 ** 2) return `${number(value / 1024 ** 2)} MiB`;
  if (value >= 1024) return `${number(value / 1024)} KiB`;
  return `${number(value)} B`;
}

function Chart({ title, samples, maximum, axis, first, second, labels, values, kind: initialKind = 'line', tone, formatValue, measure, windowMs, periodMs, statistic }: {
  title: string; samples: Sample[]; maximum: number; axis: string;
  first: (value: Metrics) => number | null; second: (value: Metrics) => number | null;
  labels: [string, string]; values: [string, string];
  kind?: 'line' | 'area' | 'bar';
  tone: 'cpu' | 'ram' | 'network'; formatValue: (value: number) => string; measure?: string;
  windowMs: number; periodMs: number; statistic: Statistic;
}) {
  const { t } = useStrings();
  const [kind, setKind] = useState(initialKind);
  const [wide, setWide] = useState(false);
  const end = samples.at(-1)?.at ?? Date.now();
  const series = [first, second].map(read => aggregate(samples, end, windowMs, periodMs, statistic, read));
  const paths = series.map(points => seriesPath(points, end, windowMs, maximum));
  const barWidth = Math.max(0.25, Math.min(8, 600 / (windowMs / periodMs) / 2 - 1));
  const axisTitle = measure ?? (tone === 'cpu' ? t.monitor_axis_processor : tone === 'ram' ? t.monitor_axis_memory : t.monitor_axis_network);
  return <figure className={`monitor-chart monitor-chart-${kind} monitor-chart-${tone}${wide ? ' monitor-chart-wide' : ''}`}>
    <figcaption className="monitor-chart-heading"><h3>{title}</h3><span>{axis}</span></figcaption>
    <label className="monitor-chart-picker">{t.monitor_graph_type}<select aria-label={t.monitor_graph_for.replace('{name}', title)} value={kind}
      onChange={event => { const value = event.target.value; if (value === 'line' || value === 'area' || value === 'bar') setKind(value); }}>
      <option value="line">{t.monitor_graph_line}</option><option value="area">{t.monitor_graph_area}</option><option value="bar">{t.monitor_graph_bars}</option>
    </select></label>
    <button type="button" className="monitor-expand" aria-expanded={wide} aria-label={(wide ? t.monitor_restore_graph : t.monitor_expand_graph).replace('{name}', title)} onClick={() => setWide(value => !value)}><i className={`ph ${wide ? 'ph-corners-in' : 'ph-corners-out'}`} aria-hidden="true" />{wide ? t.monitor_restore : t.monitor_expand}</button>
    <div className="monitor-legend">{labels.map((label, i) => <span key={label}>
      <span className={`monitor-key monitor-line-${i}`} aria-hidden="true" />{label}<strong>{values[i]}</strong>
    </span>)}</div>
    <p className="monitor-axis-title">{axisTitle}</p>
    <div className="monitor-plot"><div className="monitor-y-ticks" aria-label={axisTitle}>
      {[1, 0.75, 0.5, 0.25, 0].map(fraction => <span key={fraction}>{formatValue(maximum * fraction)}</span>)}
    </div><svg viewBox="-2 -5 604 112" preserveAspectRatio="none" role="img" aria-label={`${title}: ${axisTitle}; ${t.monitor_axis_time}; ${labels[0]} ${values[0]}, ${labels[1]} ${values[1]}`}>
      <title>{title}</title>
      {[0, 25, 50, 75, 100].map(y => <line key={y} x1="0" y1={y} x2="600" y2={y} className="monitor-grid" />)}
      {[0, 300, 600].map(x => <line key={x} x1={x} y1="0" x2={x} y2="100" className="monitor-grid" />)}
      {kind === 'bar' ? series.map((points, i) => <g key={i} className={`monitor-bars-${i}`}>
        {points.filter(point => finite(point.value)).map(point => {
          const height = Math.max(0, Math.min(100, point.value! / maximum * 100));
          const x = (point.at - end + windowMs) / windowMs * 600;
          return <rect key={point.at} x={x - barWidth + i * (barWidth + 0.5)} y={100 - height} width={barWidth} height={height} rx="0.5">
            <title>{labels[i]}: {formatValue(point.value!)} ({new Date(point.at).toLocaleTimeString('en')})</title>
          </rect>;
        })}
      </g>) : <>
        {kind === 'area' && <path d={areaPath(paths[0])} className="monitor-area-fill" />}
        {paths.map((path, i) => <path key={i} d={path} className={`monitor-line-${i}`} />)}
        {series.map((points, i) => <g key={i}>{points.filter(point => finite(point.value)).map(point => <circle key={point.at} cx={(point.at - end + windowMs) / windowMs * 600} cy={100 - Math.min(100, Math.max(0, point.value! / maximum * 100))} r="2" className={`monitor-dot monitor-line-${i}`}><title>{labels[i]}: {formatValue(point.value!)} ({new Date(point.at).toLocaleTimeString('en')})</title></circle>)}</g>)}
      </>}
    </svg></div>
    <div className="monitor-timeline"><div className="monitor-axis"><span>{t.monitor_minutes_ago.replace('{value}', String(windowMs / 60000))}</span><span>{t.monitor_minutes_ago.replace('{value}', String(windowMs / 120000))}</span><span>{t.monitor_now}</span></div>
      <p className="monitor-axis-title text-center">{t.monitor_axis_time}</p></div>
    <p className="monitor-chart-note">{t.monitor_group_note.replace('{seconds}', String(periodMs / 1000)).replace('{statistic}', statistic === 'peak' ? t.monitor_peak : t.monitor_average)}</p>
    {samples.length < 2 && <p className="monitor-chart-empty">{t.monitor_collecting}</p>}
  </figure>;
}

export default function Monitoring({ token, expired }: { token: string; expired: () => void }) {
  const { t } = useStrings();
  const [samples, setSamples] = useState<Sample[]>([]);
  const [current, setCurrent] = useState<Metrics | null>(null);
  const [lastGood, setLastGood] = useState<Metrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [selected, setSelected] = useState('');
  const [minutes, setMinutes] = useState(10);
  const [periodMs, setPeriodMs] = useState(10000);
  const [statistic, setStatistic] = useState<Statistic>('average');
  const [paused, setPaused] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saveNotice, setSaveNotice] = useState('');
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    async function sample() {
      if (!alive) return;
      if (inFlight.current) { timer = setTimeout(() => void sample(), 250); return; }
      inFlight.current = true;
      const started = Date.now();
      try {
        const value = await invoke<Metrics>('hub_metrics', { token });
        if (!alive) return;
        // A bounded native call is the only source; malformed replies are unavailable readings.
        if (!value || !Array.isArray(value.adapters)) throw new Error('INVALID_METRICS');
        setCurrent(value); setLastGood(value); setFailed(false);
        setSamples(previous => retainSamples(previous, { at: Date.now(), value }));
      } catch (cause) {
        if (!alive) return;
        if (cause === 'UNAUTHORIZED' || cause === 'FORBIDDEN') { expired(); return; }
        setCurrent(null); setFailed(true);
        setSamples(previous => retainSamples(previous, { at: Date.now(), value: null }));
      } finally {
        inFlight.current = false;
        if (alive) {
          setLoading(false);
          if (!paused) timer = setTimeout(() => void sample(), Math.max(0, INTERVAL_MS - (Date.now() - started)));
        }
      }
    }
    if (!paused || refreshKey > 0) void sample();
    return () => { alive = false; clearTimeout(timer); };
    // The parent callback can change when account data refreshes; the session owns this collector.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, paused, refreshKey]);

  const unavailable = t.monitor_unavailable;
  const percent = (value: number | null | undefined) => !finite(value) ? unavailable : `${number(value)}%`;
  const memory = (value: number | null | undefined) => bytes(value ?? null, unavailable);
  const speed = (value: number | null | undefined) => !finite(value) ? unavailable : t.monitor_per_second.replace('{value}', bytes(value, unavailable));
  const adapterName = selected || lastGood?.adapters.find(adapter => adapter.classroom)?.name || '';
  const adapter = current?.adapters.find(adapter => adapter.name === adapterName);
  const readNetwork = (value: Metrics, field: 'received_per_second' | 'sent_per_second') => value.adapters.find(item => item.name === adapterName)?.[field] ?? null;
  const end = samples.at(-1)?.at ?? Date.now();
  const visible = samples.filter(sample => sample.at >= end - minutes * 60000);
  const networkMax = Math.max(1024, ...visible.flatMap(sample => sample.value
    ? [readNetwork(sample.value, 'received_per_second'), readNetwork(sample.value, 'sent_per_second')] : []).filter(finite));
  const uptime = current ? t.monitor_uptime_value.replace('{hours}', String(Math.floor(current.uptime_seconds / 3600)))
    .replace('{minutes}', String(Math.floor(current.uptime_seconds % 3600 / 60))) : unavailable;
  const cards = [
    { title: t.monitor_cpu, tone: 'cpu', icon: 'ph-cpu', value: percent(current?.cpu_percent), comparison: [percent(current?.cpu_percent), percent(current?.hub_cpu_percent)], detail: t.monitor_computer_help },
    { title: t.monitor_ram, tone: 'ram', icon: 'ph-memory', value: memory(current?.ram_used), comparison: [memory(current?.ram_used), memory(current?.hub_ram)], detail: t.monitor_total.replace('{value}', memory(current?.ram_total)) },
    { title: t.monitor_network, tone: 'network', icon: 'ph-arrows-down-up', value: speed(adapter?.received_per_second), detail: `${t.monitor_send}: ${speed(adapter?.sent_per_second)}` },
    { title: t.monitor_disk_free, tone: 'disk', icon: 'ph-hard-drives', value: memory(current?.disk_free), detail: t.monitor_total.replace('{value}', memory(current?.disk_total)) },
    { title: t.monitor_uptime, tone: 'uptime', icon: 'ph-clock', value: uptime, detail: t.monitor_since_started },
  ];
  const requestMax = Math.max(1, ...visible.map(sample => sample.value?.workload?.requests_per_second).filter(finite));
  const responseMax = Math.max(1, ...visible.map(sample => sample.value?.workload?.slowest_response_ms).filter(finite));
  const perSecond = (value: number) => t.monitor_per_second.replace('{value}', number(value));
  const milliseconds = (value: number) => t.monitor_ms.replace('{value}', number(value));
  const chartProps = { samples: visible, windowMs: minutes * 60000, periodMs, statistic };
  const metrics = [
    { label: `${t.monitor_cpu} / ${t.monitor_whole_pc} (%)`, read: (v: Metrics) => v.cpu_percent, format: percent },
    { label: `${t.monitor_cpu} / ${t.monitor_hub_process} (%)`, read: (v: Metrics) => v.hub_cpu_percent, format: percent },
    { label: `${t.monitor_ram} / ${t.monitor_whole_pc} (B)`, read: (v: Metrics) => v.ram_used, format: memory },
    { label: `${t.monitor_ram} / ${t.monitor_hub_process} (B)`, read: (v: Metrics) => v.hub_ram, format: memory },
    { label: `${t.monitor_receive} (B/s)`, read: (v: Metrics) => readNetwork(v, 'received_per_second'), format: speed },
    { label: `${t.monitor_send} (B/s)`, read: (v: Metrics) => readNetwork(v, 'sent_per_second'), format: speed },
    { label: t.monitor_request_rate, read: (v: Metrics) => v.workload?.requests_per_second ?? null, format: perSecond },
    { label: `${t.monitor_failed_requests} / ${t.monitor_request_rate}`, read: (v: Metrics) => v.workload?.failed_per_second ?? null, format: perSecond },
    { label: `${t.monitor_response_time} / ${t.monitor_average} (ms)`, read: (v: Metrics) => v.workload?.average_response_ms ?? null, format: milliseconds },
    { label: t.monitor_disk_read + ' (B/s)', read: (v: Metrics) => v.disk_read_per_second ?? null, format: speed },
    { label: t.monitor_disk_write + ' (B/s)', read: (v: Metrics) => v.disk_write_per_second ?? null, format: speed },
  ];
  async function exportReadings() {
    setSaving(true); setSaveNotice('');
    try {
      const saved = await saveImportFile(token, 'LARA-Hub-resource-usage.csv', new TextEncoder().encode(metricsCsv(visible, metrics, t.monitor_csv_time)), t.monitor_export);
      if (saved) setSaveNotice(t.monitor_export_saved);
    } catch { setSaveNotice(t.monitor_export_failed); }
    finally { setSaving(false); }
  }

  const workloadCards = useWorkloadCards({ value: current, memory, speed });
  const resourceCards: DashboardItem[] = cards.map(card => {
    const gauge = card.tone === 'cpu' ? current?.cpu_percent : card.tone === 'ram' ? current?.ram_total && current?.ram_used != null ? current.ram_used / current.ram_total * 100 : null : card.tone === 'disk' && current?.disk_total && current?.disk_free != null ? (1 - current.disk_free / current.disk_total) * 100 : null;
    return { id: 'resource-' + card.tone, label: card.title, size: card.comparison ? 'comparison' : 'compact',
      content: loading ? <div className="monitor-card skeleton" role="status" aria-label={t.monitor_loading}><p>{card.title}</p><div className="h-10 rounded-md bg-lara-subtle" /></div> :
      <div className={'monitor-card monitor-card-' + card.tone}><dl><dt><i className={'ph ' + card.icon} aria-hidden="true" />{card.title}</dt>
        {card.comparison ? <dd className="monitor-comparison">{[t.monitor_whole_pc, t.monitor_hub_process].map((label, i) => <div key={label}><span>{label}</span><strong>{card.comparison[i]}</strong></div>)}</dd> : <dd>{card.value}</dd>}</dl>
        <p>{card.detail}</p>{finite(gauge) && <div className="monitor-gauge" role="meter" aria-label={card.tone === 'disk' ? t.monitor_storage_used : card.title} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, Math.max(0, gauge))}><span style={{ width: String(Math.min(100, Math.max(0, gauge))) + '%' }} /></div>}
      </div> };
  });
  const widgets: DashboardItem[] = [
    { id: 'warnings', label: t.monitor_warnings, size: 'full', content: <MonitoringWarnings samples={samples} paused={paused} /> },
    ...resourceCards,
    ...workloadCards,
    { id: 'graph-cpu', label: t.monitor_chart_card.replace('{name}', t.monitor_cpu), size: 'graph', content: <Chart {...chartProps} tone="cpu" formatValue={percent} title={t.monitor_cpu} maximum={100} axis="100%"
        first={value => value.cpu_percent} second={value => value.hub_cpu_percent}
        labels={[t.monitor_whole_pc, t.monitor_hub_process]} values={[percent(current?.cpu_percent), percent(current?.hub_cpu_percent)]} /> },
    { id: 'graph-ram', label: t.monitor_chart_card.replace('{name}', t.monitor_ram), size: 'graph', content: <Chart {...chartProps} kind="area" tone="ram" formatValue={memory} title={t.monitor_ram} maximum={lastGood?.ram_total ?? 1} axis={memory(lastGood?.ram_total)}
        first={value => value.ram_used} second={value => value.hub_ram}
        labels={[t.monitor_whole_pc, t.monitor_hub_process]} values={[memory(current?.ram_used), memory(current?.hub_ram)]} /> },
    { id: 'graph-network', label: t.monitor_chart_card.replace('{name}', t.monitor_network), size: 'graph', content: <Chart {...chartProps} kind="bar" tone="network" formatValue={speed} title={t.monitor_network} maximum={networkMax} axis={speed(networkMax)}
        first={value => readNetwork(value, 'received_per_second')} second={value => readNetwork(value, 'sent_per_second')}
        labels={[t.monitor_receive, t.monitor_send]} values={[speed(adapter?.received_per_second), speed(adapter?.sent_per_second)]} /> },
    { id: 'graph-requests', label: t.monitor_chart_card.replace('{name}', t.monitor_request_rate), size: 'graph', content: <Chart {...chartProps} tone="cpu" measure={t.monitor_request_rate} formatValue={perSecond} title={t.monitor_request_rate} maximum={requestMax} axis={perSecond(requestMax)}
        first={value => value.workload?.requests_per_second ?? null} second={value => value.workload?.failed_per_second ?? null}
        labels={[t.monitor_requests, t.monitor_failed_requests]} values={[current?.workload ? perSecond(current.workload.requests_per_second) : unavailable, current?.workload ? perSecond(current.workload.failed_per_second) : unavailable]} /> },
    { id: 'graph-response', label: t.monitor_chart_card.replace('{name}', t.monitor_response_time), size: 'full', content: <Chart {...chartProps} tone="ram" measure={t.monitor_response_time} formatValue={milliseconds} title={t.monitor_response_time} maximum={responseMax} axis={milliseconds(responseMax)}
        first={value => value.workload?.average_response_ms ?? null} second={value => value.workload?.slowest_response_ms ?? null}
        labels={[t.monitor_average, t.monitor_slowest]} values={[current?.workload?.average_response_ms != null ? milliseconds(current.workload.average_response_ms) : unavailable, current?.workload?.slowest_response_ms != null ? milliseconds(current.workload.slowest_response_ms) : unavailable]} /> },
    { id: 'statistics', label: t.monitor_statistics, size: 'full', content: <section className="monitor-statistics" aria-labelledby="monitor-statistics-title"><h2 id="monitor-statistics-title">{t.monitor_statistics}</h2><p className="monitor-footnote">{t.monitor_statistics_help}</p>
      <div className="monitor-statistics-scroll" role="region" aria-label={t.monitor_statistics_table} tabIndex={0}><table><colgroup><col className="statistics-metric-column" /><col span={5} className="statistics-value-column" /></colgroup><thead><tr>{[t.monitor_metric, t.monitor_latest, t.monitor_minimum, t.monitor_average, t.monitor_peak, t.monitor_readings].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{metrics.map(metric => { const stats = summarize(visible, metric.read); return <tr key={metric.label}><th scope="row">{metric.label}</th>{[stats.latest, stats.minimum, stats.average, stats.peak].map((value, i) => <td key={i}>{value == null ? unavailable : metric.format(value)}</td>)}<td>{stats.count}</td></tr>; })}</tbody></table></div>
    </section> },
  ];

  return <section className="monitor-page" aria-labelledby="monitor-title" data-samples={samples.length}>
    <div className="monitor-heading"><h2 id="monitor-title">{t.monitor_title}</h2>
      <span className="monitor-update"><i className={`ph ${failed ? 'ph-warning-circle' : paused ? 'ph-pause' : 'ph-clock'}`} aria-hidden="true" />{paused ? t.monitor_paused : failed ? unavailable : t.monitor_update}</span></div>
    <div className="monitor-toolbar" role="group" aria-label={t.monitor_dashboard_controls}>
      <label>{t.monitor_history}<select aria-label={t.monitor_history} value={minutes} onChange={e => setMinutes(Number(e.target.value))}>{[5, 10, 15, 60].map(value => <option key={value} value={value}>{t.monitor_last_minutes.replace('{value}', String(value))}</option>)}</select></label>
      <label>{t.monitor_group_readings}<select aria-label={t.monitor_group_readings} value={periodMs} onChange={e => setPeriodMs(Number(e.target.value))}>{[10000, 30000, 60000].map(value => <option key={value} value={value}>{t.monitor_seconds.replace('{value}', String(value / 1000))}</option>)}</select></label>
      <label>{t.monitor_graph_values}<select aria-label={t.monitor_graph_values} value={statistic} onChange={e => setStatistic(e.target.value === 'peak' ? 'peak' : 'average')}><option value="average">{t.monitor_average}</option><option value="peak">{t.monitor_peak}</option></select></label>
      <button className="admin-secondary" type="button" onClick={() => { setPaused(value => !value); setRefreshKey(0); }}><i className={`ph ${paused ? 'ph-play' : 'ph-pause'}`} aria-hidden="true" />{paused ? t.monitor_resume : t.monitor_pause}</button>
      <button className="admin-secondary" type="button" onClick={() => setRefreshKey(value => value + 1)}>{t.activity_refresh}</button>
      <button className="admin-secondary" type="button" disabled={saving || !visible.length} onClick={() => void exportReadings()}>{saving ? t.admin_working : t.monitor_export}</button>
    </div>
    <p className="monitor-footnote">{samples.length ? t.monitor_updated_at.replace('{time}', new Date(end).toLocaleTimeString('en')) : t.monitor_collecting} {t.monitor_live_scope}</p>
    {paused && <p role="status" className="admin-offline">{t.monitor_paused_help}</p>}
    {saveNotice && <p role="status" className="monitor-footnote">{saveNotice}</p>}
    {failed && <p role="status" className="admin-offline"><i className="ph ph-warning-circle" aria-hidden="true" />{t.monitor_failed}</p>}
    <p className="monitor-footnote">{t.monitor_request_scope}</p>
    <div className="monitor-controls"><label htmlFor="monitor-adapter">{t.monitor_adapter}</label>
      <select id="monitor-adapter" value={selected} onChange={event => setSelected(event.target.value)}>
        <option value="">{t.monitor_auto}{lastGood?.adapters.find(item => item.classroom) ? ` · ${lastGood.adapters.find(item => item.classroom)?.name}` : ''}</option>
        {selected && !lastGood?.adapters.some(item => item.name === selected) && <option value={selected}>{selected}</option>}
        {lastGood?.adapters.map(item => <option key={item.name} value={item.name}>{item.name}</option>)}
      </select><span>{t.monitor_adapter_help}</span>
    </div>
    {!loading && !adapter && <p className="text-sm text-lara-ink-2">{t.monitor_no_adapter}</p>}
    <DashboardCards items={widgets} />
    <p className="monitor-footnote">{t.monitor_session_history}</p>
  </section>;
}
