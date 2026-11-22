import type { Metrics, Sample } from './monitoring';

export type Statistic = 'average' | 'peak';
export type Reading = { at: number; value: number | null };
export type Limits = { cpu: number; ram: number; storage: number };
export const DEFAULT_LIMITS: Limits = { cpu: 85, ram: 90, storage: 5 };
export const finite = (n: number | null | undefined): n is number => n != null && Number.isFinite(n);

export function validLimits(value: unknown): value is Limits {
  if (!value || typeof value !== 'object') return false;
  const v = value as Partial<Limits>;
  return finite(v.cpu) && v.cpu >= 1 && v.cpu <= 100 && finite(v.ram) && v.ram >= 1 && v.ram <= 100 && finite(v.storage) && v.storage >= 0.1 && v.storage <= 100000;
}

export function summarize(samples: Sample[], read: (v: Metrics) => number | null) {
  const values = samples.map(s => s.value && read(s.value)).filter(finite);
  const last = samples.at(-1)?.value;
  const latest = last ? read(last) : null;
  return { count: values.length, latest: finite(latest) ? latest : null,
    minimum: values.length ? Math.min(...values) : null,
    average: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null,
    peak: values.length ? Math.max(...values) : null };
}

export function aggregate(samples: Sample[], end: number, window: number, period: number, statistic: Statistic, read: (v: Metrics) => number | null): Reading[] {
  const start = end - window;
  const groups: number[][] = Array.from({ length: Math.ceil(window / period) }, () => []);
  for (const sample of samples) {
    if (sample.at < start || sample.at > end) continue;
    const value = sample.value && read(sample.value);
    if (finite(value)) groups[Math.min(groups.length - 1, Math.floor((sample.at - start) / period))].push(value);
  }
  return groups.map((values, i) => ({ at: start + (i + 0.5) * period,
    value: !values.length ? null : statistic === 'peak' ? Math.max(...values) : values.reduce((a, b) => a + b, 0) / values.length }));
}

export function seriesPath(points: Reading[], end: number, window: number, maximum: number): string {
  let connected = false;
  return points.map(point => {
    if (!finite(point.value)) { connected = false; return ''; }
    const x = (point.at - end + window) / window * 600;
    const y = 100 - Math.max(0, Math.min(100, point.value / Math.max(1, maximum) * 100));
    const path = `${connected ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`;
    connected = true;
    return path;
  }).join(' ');
}

// A warning requires a continuous thirty-second window, not repeated stale values.
export function warning(samples: Sample[], now: number, read: (v: Metrics) => number | null, limit: number, below = false): 'attention' | 'normal' | 'missing' {
  let anchor = samples.length - 1;
  while (anchor >= 0 && samples[anchor].at > now - 30000) anchor--;
  if (anchor < 0 || now - 30000 - samples[anchor].at > 5000) return 'missing';
  const window = samples.slice(anchor).filter(s => s.at <= now);
  if (!window.length || now - window[window.length - 1].at > 5000) return 'missing';
  let all = true;
  for (let i = 0; i < window.length; i++) {
    const value = window[i].value && read(window[i].value!);
    if (!finite(value) || (i > 0 && window[i].at - window[i - 1].at > 5000)) return 'missing';
    all &&= below ? value <= limit : value >= limit;
  }
  return all ? 'attention' : 'normal';
}

export function metricsCsv(samples: Sample[], columns: { label: string; read: (v: Metrics) => number | null }[], timeLabel: string): string {
  const quote = (s: string) => `"${s.replaceAll('"', '""')}"`;
  return [ [timeLabel, ...columns.map(c => c.label)].map(quote).join(','),
    ...samples.map(sample => [quote(new Date(sample.at).toISOString()), ...columns.map(c => {
      const value = sample.value && c.read(sample.value);
      return finite(value) ? String(value) : '';
    })].join(',')) ].join('\r\n');
}
