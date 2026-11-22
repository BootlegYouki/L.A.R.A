type Adapter = { name: string; classroom: boolean; received_per_second: number | null; sent_per_second: number | null };
export type Metrics = {
  cpu_percent: number | null; hub_cpu_percent: number | null;
  ram_used: number | null; ram_total: number | null; hub_ram: number | null;
  disk_free: number | null; disk_total: number | null; uptime_seconds: number; adapters: Adapter[];
};
export type Sample = { at: number; value: Metrics | null };
const WINDOW_MS = 10 * 60 * 1000;
export const INTERVAL_MS = 2000;

export function retainSamples(samples: Sample[], next: Sample): Sample[] {
  return [...samples.filter(sample => sample.at > next.at - WINDOW_MS), next].slice(-301);
}

export function graphPath(samples: Sample[], end: number, maximum: number, read: (value: Metrics) => number | null): string {
  let connected = false;
  let previous = 0;
  return samples.map(sample => {
    const value = sample.value && read(sample.value);
    if (value === null || !Number.isFinite(value)) { connected = false; return ''; }
    const x = Math.max(0, Math.min(600, (sample.at - end + WINDOW_MS) / WINDOW_MS * 600));
    const y = 100 - Math.max(0, Math.min(100, value / Math.max(1, maximum) * 100));
    const command = connected && sample.at - previous < 5000 ? 'L' : 'M';
    connected = true; previous = sample.at;
    return `${command}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(' ');
}

export function areaPath(line: string): string {
  return (line.match(/M[^M]+/g) ?? []).map(segment => {
    const points = [...segment.matchAll(/[ML]([\d.]+),([\d.]+)/g)];
    return points.length ? `${segment.trim()} L${points.at(-1)?.[1]},100 L${points[0][1]},100 Z` : '';
  }).join(' ');
}

// Ten-second means keep two-second readings legible across the ten-minute chart.
export function networkBars(samples: Sample[], end: number, read: (value: Metrics) => number | null): { x: number; value: number }[] {
  const buckets = new Map<number, { total: number; count: number }>();
  for (const sample of samples) {
    const value = sample.value && read(sample.value);
    if (value === null || !Number.isFinite(value) || sample.at < end - WINDOW_MS || sample.at > end) continue;
    const index = Math.min(59, Math.floor((sample.at - end + WINDOW_MS) / 10000));
    const bucket = buckets.get(index) ?? { total: 0, count: 0 };
    bucket.total += value; bucket.count++;
    buckets.set(index, bucket);
  }
  return [...buckets].map(([index, bucket]) => ({ x: index * 10, value: bucket.total / bucket.count }));
}
