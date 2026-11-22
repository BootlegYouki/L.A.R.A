const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { normalizeOrder, moveCard } = require('../src/dashboardLayout.ts');

test('saved layouts reject corrupt values, remove obsolete cards and include new cards', () => {
  const ids = ['cpu', 'ram', 'graph'];
  for (const invalid of [null, {}, 42, 'cpu']) assert.deepEqual(normalizeOrder(invalid, ids), ids);
  assert.deepEqual(normalizeOrder(['ram', 'removed', 'ram', 42], ids), ['ram', 'cpu', 'graph']);
});

test('cards move in either direction without mutating the original order', () => {
  const ids = Object.freeze(['cpu', 'ram', 'graph']);
  assert.deepEqual(moveCard(ids, 'cpu', 'graph'), ['ram', 'graph', 'cpu']);
  assert.deepEqual(moveCard(ids, 'graph', 'cpu'), ['graph', 'cpu', 'ram']);
  assert.deepEqual(moveCard(ids, 'cpu', 'ram'), ['ram', 'cpu', 'graph']);
  for (const pair of [['cpu', 'cpu'], ['missing', 'cpu'], ['cpu', 'missing']]) {
    assert.deepEqual(moveCard(ids, ...pair), ids);
  }
  assert.deepEqual(ids, ['cpu', 'ram', 'graph']);
});
