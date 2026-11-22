import { invoke } from '@tauri-apps/api/core';
import { useEffect, useState } from 'react';
import { useStrings } from '../i18n';

import { areaPath, graphPath, networkBars, retainSamples, INTERVAL_MS, type Metrics, type Sample } from '../monitoring';

const number = (value: number) => value.toLocaleString('en', { maximumFractionDigits: 1 });
function bytes(value: number | null, unavailable: string): string {
  if (value === null || !Number.isFinite(value)) return unavailable;
  if (value >= 1024 ** 3) return `${number(value / 1024 ** 3)} GiB`;
  if (value >= 1024 ** 2) return `${number(value / 1024 ** 2)} MiB`;
  if (value >= 1024) return `${number(value / 1024)} KiB`;
  return `${number(value)} B`;
}

function Chart({ title, samples, maximum, axis, first, second, labels, values, kind: initialKind = 'line', tone, formatValue }: {
  title: string; samples: Sample[]; maximum: number; axis: string;
  first: (value: Metrics) => number | null; second: (value: Metrics) => number | null;
  labels: [string, string]; values: [string, string];
  kind?: 'line' | 'area' | 'bar';
  tone: 'cpu' | 'ram' | 'network'; formatValue: (value: number) => string;
}) {
  const { t } = useStrings();
  const [kind, setKind] = useState(initialKind);
  const end = samples.at(-1)?.at ?? Date.now();
  const paths = [first, second].map(read => graphPath(samples, end, maximum, read));
  const axisTitle = tone === 'cpu' ? t.monitor_axis_processor : tone === 'ram' ? t.monitor_axis_memory : t.monitor_axis_network;
  return <figure className={`monitor-chart monitor-chart-${kind} monitor-chart-${tone}`}>
    <figcaption className="monitor-chart-heading"><h3>{title}</h3><span>{axis}</span></figcaption>
    <label className="monitor-chart-picker">{t.monitor_graph_type}<select aria-label={t.monitor_graph_for.replace('{name}', title)} value={kind}
      onChange={event => { const value = event.target.value; if (value === 'line' || value === 'area' || value === 'bar') setKind(value); }}>
      <option value="line">{t.monitor_graph_line}</option><option value="area">{t.monitor_graph_area}</option><option value="bar">{t.monitor_graph_bars}</option>
    </select></label>
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
      {kind === 'bar' ? [first, second].map((read, i) => <g key={i} className={`monitor-bars-${i}`}>
        {networkBars(samples, end, read).map(bar => {
          const height = Math.max(0, Math.min(100, bar.value / maximum * 100));
          return <rect key={bar.x} x={bar.x + 1 + i * 4} y={100 - height} width="3" height={height} rx="0.5">
            <title>{labels[i]}: {formatValue(bar.value)}</title>
          </rect>;
        })}
      </g>) : <>
        {kind === 'area' && <path d={areaPath(paths[0])} className="monitor-area-fill" />}
        {paths.map((path, i) => <path key={i} d={path} className={`monitor-line-${i}`} />)}
      </>}
    </svg></div>
    <div className="monitor-timeline"><div className="monitor-axis"><span>{t.monitor_ten_minutes_ago}</span><span>{t.monitor_five_minutes_ago}</span><span>{t.monitor_now}</span></div>
      <p className="monitor-axis-title text-center">{t.monitor_axis_time}</p></div>
    {kind === 'bar' && <p className="monitor-chart-note">{t.monitor_network_average}</p>}
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

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    async function sample() {
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
        if (alive) {
          setLoading(false);
          timer = setTimeout(() => void sample(), Math.max(0, INTERVAL_MS - (Date.now() - started)));
        }
      }
    }
    void sample();
    return () => { alive = false; clearTimeout(timer); };
    // The parent callback can change when account data refreshes; the session owns this collector.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const unavailable = t.monitor_unavailable;
  const percent = (value: number | null | undefined) => value == null ? unavailable : `${number(value)}%`;
  const memory = (value: number | null | undefined) => bytes(value ?? null, unavailable);
  const speed = (value: number | null | undefined) => value == null ? unavailable : t.monitor_per_second.replace('{value}', bytes(value, unavailable));
  const adapterName = selected || lastGood?.adapters.find(adapter => adapter.classroom)?.name || '';
  const adapter = current?.adapters.find(adapter => adapter.name === adapterName);
  const readNetwork = (value: Metrics, field: 'received_per_second' | 'sent_per_second') => value.adapters.find(item => item.name === adapterName)?.[field] ?? null;
  const networkMax = Math.max(1024, ...samples.flatMap(sample => sample.value
    ? [readNetwork(sample.value, 'received_per_second') ?? 0, readNetwork(sample.value, 'sent_per_second') ?? 0] : []));
  const uptime = current ? t.monitor_uptime_value.replace('{hours}', String(Math.floor(current.uptime_seconds / 3600)))
    .replace('{minutes}', String(Math.floor(current.uptime_seconds % 3600 / 60))) : unavailable;
  const cards = [
    { title: t.monitor_cpu, tone: 'cpu', icon: 'ph-cpu', value: percent(current?.cpu_percent), comparison: [percent(current?.cpu_percent), percent(current?.hub_cpu_percent)], detail: t.monitor_computer_help },
    { title: t.monitor_ram, tone: 'ram', icon: 'ph-memory', value: memory(current?.ram_used), comparison: [memory(current?.ram_used), memory(current?.hub_ram)], detail: t.monitor_total.replace('{value}', memory(current?.ram_total)) },
    { title: t.monitor_network, tone: 'network', icon: 'ph-arrows-down-up', value: speed(adapter?.received_per_second), detail: `${t.monitor_send}: ${speed(adapter?.sent_per_second)}` },
    { title: t.monitor_disk_free, tone: 'disk', icon: 'ph-hard-drives', value: memory(current?.disk_free), detail: t.monitor_total.replace('{value}', memory(current?.disk_total)) },
    { title: t.monitor_uptime, tone: 'uptime', icon: 'ph-clock', value: uptime, detail: t.monitor_since_started },
  ];

  return <section className="monitor-page" aria-labelledby="monitor-title" data-samples={samples.length}>
    <div className="monitor-heading"><h2 id="monitor-title">{t.monitor_title}</h2>
      <span className="monitor-update"><i className={`ph ${failed ? 'ph-warning-circle' : 'ph-clock'}`} aria-hidden="true" />{failed ? unavailable : t.monitor_update}</span></div>
    {failed && <p role="status" className="admin-offline"><i className="ph ph-warning-circle" aria-hidden="true" />{t.monitor_failed}</p>}
    {loading ? <div className="monitor-cards" role="status" aria-label={t.monitor_loading}>
      {cards.map(card => <div key={card.title} className="monitor-card skeleton"><p>{card.title}</p><div className="h-10 rounded-md bg-lara-subtle" /></div>)}
    </div> : <dl className="monitor-cards">{cards.map(card => <div className={`monitor-card monitor-card-${card.tone}${card.comparison ? ' monitor-card-comparing' : ''}`} key={card.title}>
      <dt><i className={`ph ${card.icon}`} aria-hidden="true" />{card.title}</dt>
      {card.comparison ? <dd className="monitor-comparison">{[t.monitor_whole_pc, t.monitor_hub_process].map((label, i) => <div key={label}>
        <span>{label}</span><strong>{card.comparison[i]}</strong>
      </div>)}</dd> : <dd>{card.value}</dd>}<p>{card.detail}</p>
    </div>)}</dl>}
    <div className="monitor-controls"><label htmlFor="monitor-adapter">{t.monitor_adapter}</label>
      <select id="monitor-adapter" value={selected} onChange={event => setSelected(event.target.value)}>
        <option value="">{t.monitor_auto}{lastGood?.adapters.find(item => item.classroom) ? ` · ${lastGood.adapters.find(item => item.classroom)?.name}` : ''}</option>
        {selected && !lastGood?.adapters.some(item => item.name === selected) && <option value={selected}>{selected}</option>}
        {lastGood?.adapters.map(item => <option key={item.name} value={item.name}>{item.name}</option>)}
      </select><span>{t.monitor_adapter_help}</span>
    </div>
    {!loading && !adapter && <p className="text-sm text-lara-ink-2">{t.monitor_no_adapter}</p>}
    <div className="monitor-charts">
      <Chart tone="cpu" formatValue={percent} title={t.monitor_cpu} samples={samples} maximum={100} axis="100%"
        first={value => value.cpu_percent} second={value => value.hub_cpu_percent}
        labels={[t.monitor_whole_pc, t.monitor_hub_process]} values={[percent(current?.cpu_percent), percent(current?.hub_cpu_percent)]} />
      <Chart kind="area" tone="ram" formatValue={memory} title={t.monitor_ram} samples={samples} maximum={lastGood?.ram_total ?? 1} axis={memory(lastGood?.ram_total)}
        first={value => value.ram_used} second={value => value.hub_ram}
        labels={[t.monitor_whole_pc, t.monitor_hub_process]} values={[memory(current?.ram_used), memory(current?.hub_ram)]} />
      <Chart kind="bar" tone="network" formatValue={speed} title={t.monitor_network} samples={samples} maximum={networkMax} axis={speed(networkMax)}
        first={value => readNetwork(value, 'received_per_second')} second={value => readNetwork(value, 'sent_per_second')}
        labels={[t.monitor_receive, t.monitor_send]} values={[speed(adapter?.received_per_second), speed(adapter?.sent_per_second)]} />
    </div>
    <p className="monitor-footnote">{t.monitor_scope}</p>
  </section>;
}
