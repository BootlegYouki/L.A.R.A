const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { aggregate, seriesPath, summarize, warning, validLimits, metricsCsv } = require('../src/monitoringAnalysis.ts');
const read = value => value.cpu_percent;
const sample = (at, value) => ({ at, value: value == null ? null : { cpu_percent: value } });

test('grouping distinguishes averages, peaks, genuine zeros and empty periods', () => {
  const samples = [sample(0, 0), sample(2000, 100), sample(12000, null), sample(22000, 75), sample(60000, 999)];
  const averages = aggregate(samples, 30000, 30000, 10000, 'average', read);
  assert.deepEqual(averages.map(v => v.value), [50, null, 75]);
  assert.deepEqual(aggregate(samples, 30000, 30000, 10000, 'peak', read).map(v => v.value), [100, null, 75]);
  const line = seriesPath(averages, 30000, 30000, 100);
  assert.equal((line.match(/M/g) || []).length, 2);
  assert(!line.includes('L'));
  assert(!line.includes('NaN'));
});

test('warnings require thirty seconds of fresh continuous readings and recover on a nonbreach', () => {
  const samples = Array.from({ length: 16 }, (_, i) => sample(i * 2000, 90));
  assert.equal(warning(samples.slice(0, 15), 28000, read, 85), 'missing');
  assert.equal(warning(samples, 30000, read, 85), 'attention');
  assert.equal(warning(samples, 40000, read, 85), 'missing');
  assert.equal(warning([...samples.slice(0, -1), sample(30000, 20)], 30000, read, 85), 'normal');
  assert.equal(warning(samples.map((v, i) => i === 5 ? sample(v.at, null) : v), 30000, read, 85), 'missing');
  assert.equal(warning(samples.filter(v => v.at < 8000 || v.at > 18000), 30000, read, 85), 'missing');
  assert.equal(warning(samples.map(v => sample(v.at, 2)), 30000, read, 5, true), 'attention');
});

test('table statistics omit missing/nonfinite readings and keep a missing latest value unavailable', () => {
  const stats = summarize([sample(0, 0), sample(2000, 100), sample(4000, NaN), sample(6000, null)], read);
  assert.deepEqual(stats, { count: 2, latest: null, minimum: 0, average: 50, peak: 100 });
  assert.equal(summarize([], read).count, 0);
});

test('only validated preference values and allowlisted numeric metrics enter exports', () => {
  assert(validLimits({ cpu: 85, ram: 90, storage: 5 }));
  for (const invalid of [null, {}, { cpu: 101, ram: 90, storage: 5 }, { cpu: 85, ram: NaN, storage: 5 }, { cpu: 85, ram: 90, storage: 0 }]) assert(!validLimits(invalid));
  const samples = [sample(0, 0), { at: 2000, value: { cpu_percent: 42, token: 'SECRET', pin: 'SECRET' } }, sample(4000, null)];
  const csv = metricsCsv(samples, [{ label: 'CPU "use" (%)', read }], 'Time (UTC)');
  assert(csv.includes('"CPU ""use"" (%)"'));
  assert(csv.includes('"1970-01-01T00:00:00.000Z",0'));
  assert(csv.endsWith('"1970-01-01T00:00:04.000Z",'));
  assert(!csv.includes('SECRET'));
});
