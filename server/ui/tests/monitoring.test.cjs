const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { retainSamples, graphPath, areaPath, networkBars } = require('../src/monitoring.ts');

test('history expires by elapsed time and stays bounded during rapid responses', () => {
  let samples = [];
  for (let at = 0; at <= 700000; at += 2000) samples = retainSamples(samples, { at, value: null });
  assert.equal(samples.length, 300);
  assert.equal(samples[0].at, 102000);
  for (let at = 700001; at <= 701000; at++) samples = retainSamples(samples, { at, value: null });
  assert.equal(samples.length, 301);
  samples = retainSamples(samples, { at: 2000000, value: null });
  assert.equal(samples.length, 1);
});

test('area fills close each available segment without filling a missing interval', () => {
  assert.equal(areaPath('M1.00,50.00 L2.00,40.00  M4.00,30.00'),
    'M1.00,50.00 L2.00,40.00 L2.00,100 L1.00,100 Z M4.00,30.00 L4.00,100 L4.00,100 Z');
  assert.equal(areaPath(''), '');
});

test('network bars average valid samples per ten seconds, preserving missing buckets and zeros', () => {
  const samples = [
    { at: 1000, value: { rate: 100 } }, { at: 3000, value: { rate: 300 } },
    { at: 5000, value: null }, { at: 11000, value: { rate: null } },
    { at: 21000, value: { rate: 0 } },
  ];
  assert.deepEqual(networkBars(samples, 600000, value => value.rate), [{ x: 0, value: 200 }, { x: 20, value: 0 }]);
});

test('graphs break on missing readings, invalid numbers and sleep gaps; zero remains valid', () => {
  const samples = [
    { at: 0, value: { cpu_percent: 0 } }, { at: 2000, value: { cpu_percent: 20 } },
    { at: 4000, value: null }, { at: 6000, value: { cpu_percent: 40 } },
    { at: 8000, value: { cpu_percent: NaN } }, { at: 10000, value: { cpu_percent: 30 } },
    { at: 30000, value: { cpu_percent: 50 } },
  ];
  const path = graphPath(samples, 30000, 100, v => v.cpu_percent);
  assert.equal((path.match(/M/g) || []).length, 4);
  assert.equal((path.match(/L/g) || []).length, 1);
  assert(path.startsWith('M570.00,100.00 L572.00,80.00'));
  assert(!path.includes('NaN'));
});
