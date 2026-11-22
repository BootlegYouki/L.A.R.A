import { useState } from 'react';
import { useStrings } from '../i18n';
import type { Metrics, Sample } from '../monitoring';
import { DEFAULT_LIMITS, finite, validLimits, warning, type Limits } from '../monitoringAnalysis';
import Dialog from './AdminDialog';

const KEY = 'lara:monitoring-limits:v1';
function load(): Limits {
  try { const value: unknown = JSON.parse(localStorage.getItem(KEY) ?? 'null'); return validLimits(value) ? value : DEFAULT_LIMITS; }
  catch { return DEFAULT_LIMITS; }
}

export default function MonitoringWarnings({ samples, paused }: { samples: Sample[]; paused: boolean }) {
  const { t } = useStrings();
  const [limits, setLimits] = useState<Limits>(load);
  const [draft, setDraft] = useState<Limits | null>(null);
  const [notice, setNotice] = useState('');
  const definitions: { key: keyof Limits; name: string; field: string; read: (v: Metrics) => number | null; below?: boolean }[] = [
    { key: 'cpu', name: t.monitor_cpu, field: t.monitor_cpu_limit, read: v => v.cpu_percent },
    { key: 'ram', name: t.monitor_ram, field: t.monitor_ram_limit, read: v => finite(v.ram_used) && finite(v.ram_total) && v.ram_total > 0 ? v.ram_used / v.ram_total * 100 : null },
    { key: 'storage', name: t.monitor_disk_free, field: t.monitor_storage_limit, read: v => finite(v.disk_free) ? v.disk_free / 1024 ** 3 : null, below: true },
  ];
  return <section className="monitor-warnings" aria-labelledby="monitor-warnings-title">
    <div className="monitor-heading"><h2 id="monitor-warnings-title">{t.monitor_warnings}</h2><button className="admin-secondary" type="button" onClick={() => setDraft({ ...limits })}><i className="ph ph-sliders-horizontal" aria-hidden="true" />{t.monitor_edit_limits}</button></div>
    <p className="monitor-footnote">{t.monitor_warning_help}</p>
    <div className="monitor-warning-grid">{definitions.map(item => {
      const state = paused ? 'missing' : warning(samples, Date.now(), item.read, limits[item.key], item.below);
      const status = state === 'attention' ? t.monitor_attention : state === 'normal' ? t.monitor_within_limits : t.monitor_not_enough;
      return <div key={item.key} className={`monitor-warning monitor-warning-${state}`}><h3>{item.name}</h3>
        <p><i className={`ph ${state === 'attention' ? 'ph-warning-circle' : state === 'normal' ? 'ph-check-circle' : 'ph-clock'}`} aria-hidden="true" />{status}</p>
        <span>{(item.below ? t.monitor_below_limit : t.monitor_above_limit).replace('{value}', String(limits[item.key])).replace('{unit}', item.below ? t.monitor_gib : '%')}</span>
      </div>;
    })}</div>
    {notice && <p role="status" className="monitor-footnote">{notice}</p>}
    {draft && <Dialog title={t.monitor_edit_limits} close={() => setDraft(null)}><form onSubmit={event => {
      event.preventDefault();
      if (!validLimits(draft)) return;
      setLimits({ ...draft });
      try { localStorage.setItem(KEY, JSON.stringify(draft)); setNotice(t.monitor_limits_saved); }
      catch { setNotice(t.monitor_limits_session); }
      setDraft(null);
    }}><p className="mb-4 text-sm text-lara-ink-2">{t.monitor_limit_help}</p><div className="flex flex-col gap-4">{definitions.map(item => <label key={item.key} className="admin-label">{item.field}
      <input type="number" required min={item.below ? 0.1 : 1} max={item.below ? 100000 : 100} step={item.below ? 0.1 : 1} value={finite(draft[item.key]) ? draft[item.key] : ''} onChange={event => setDraft({ ...draft, [item.key]: event.target.valueAsNumber })} />
    </label>)}</div><div className="mt-6 flex flex-wrap justify-end gap-2"><button type="button" className="admin-secondary" onClick={() => setDraft({ ...DEFAULT_LIMITS })}>{t.monitor_reset_limits}</button><button type="button" className="admin-secondary" onClick={() => setDraft(null)}>{t.admin_cancel}</button><button className="admin-primary" type="submit">{t.monitor_save_limits}</button></div></form></Dialog>}
  </section>;
}
