const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const ts = require('typescript');
const XLSX = require('xlsx');

require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { parseRoster, markExisting, generatePin, createRoster, credentialCsv, rosterTemplate } = require('../src/learnerImport.ts');
const { HubError } = require('../src/admin.ts');
const bytes = text => new TextEncoder().encode(text).buffer;
const csv = text => parseRoster(bytes(text), 'roster.csv');
const roster = () => csv('LRN,Full name\r\n000123456789,Ana\r\n123456789012,José Reyes');
const user = (row, role = 'STUDENT') => ({ id: row.lrn, lrn_or_id: row.lrn, full_name: row.name, role });
const workbook = (values, sheetName = 'Learners', edit = () => {}) => {
  const book = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet(values);
  edit(sheet);
  XLSX.utils.book_append_sheet(book, sheet, sheetName);
  return XLSX.write(book, { type: 'array', bookType: 'xlsx' });
};

test('demonstration workbook imports 20 fictional learners without losing leading zeros', () => {
  const file = fs.readFileSync(path.resolve(__dirname, '../../docs/examples/Demo learners.xlsx'));
  const rows = parseRoster(file.buffer.slice(file.byteOffset, file.byteOffset + file.byteLength), 'Demo learners.xlsx');
  assert.equal(rows.length, 20);
  assert.equal(new Set(rows.map(row => row.lrn)).size, 20);
  assert(rows.every(row => row.status === 'pending' && /^0[0-9]{11}$/.test(row.lrn) && row.name.startsWith('Demo ')));
});

test('CSV preserves leading zeros, quoted names, Unicode and trims whitespace', () => {
  const rows = csv('\uFEFFLRN,Full name\r\n000123456789,"  Reyes, José  "\r\n,\r\n123456789012,"Ana ""Annie"" Cruz"');
  assert.equal(rows.length, 2);
  assert.equal(rows[0].lrn, '000123456789');
  assert.equal(rows[0].name, 'Reyes, José');
  assert.equal(rows[1].name, 'Ana "Annie" Cruz');
  assert(rows.every(row => row.status === 'pending'));
});

test('Excel accepts text and exact 12-digit numbers and requires Learners worksheet', () => {
  const rows = parseRoster(workbook([['LRN', 'Full name'], ['000123456789', 'Ana'], [123456789012, 'José']]), 'roster.xlsx');
  assert.deepEqual(rows.map(row => row.lrn), ['000123456789', '123456789012']);
  assert.throws(() => parseRoster(workbook([['LRN', 'Full name']], 'Sheet1'), 'roster.xlsx'), { issue: 'sheet' });
  const formula = workbook([['LRN', 'Full name'], ['000123456789', 'Ana']], 'Learners', sheet => { sheet.B2.f = '"Ana"'; });
  assert.equal(parseRoster(formula, 'roster.xlsx')[0].issue, 'formula');
});

test('validation blocks wrong headers, empty/oversized files, extra columns and invalid fields', () => {
  for (const value of ['Name,LRN\nAna,123456789012', 'LRN,Full name,Role\n123456789012,Ana,STUDENT']) {
    assert.throws(() => csv(value), { issue: 'headers' });
  }
  assert.throws(() => csv('LRN,Full name'), { issue: 'empty' });
  assert.throws(() => parseRoster(new ArrayBuffer(5 * 1024 * 1024 + 1), 'roster.csv'), { issue: 'file' });
  assert.throws(() => parseRoster(bytes('LRN,Full name'), 'roster.xls'), { issue: 'file' });
  assert.equal(csv('LRN,Full name\n123,Ana')[0].issue, 'lrn');
  assert.equal(csv('LRN,Full name\n123456789012,   ')[0].issue, 'name');
  assert.equal(csv(`LRN,Full name\n123456789012,${'A'.repeat(101)}`)[0].issue, 'name');
  assert.equal(csv('LRN,Full name\n123456789012,=CMD()')[0].issue, 'formula');
  assert(csv('LRN,Full name\n123456789012,Ana\n123456789012,Ben').every(row => row.issue === 'duplicate'));
});

test('500 rows accepted; 501 rejected; templates have fixed headers and text LRN formatting', () => {
  const lines = Array.from({ length: 500 }, (_, index) => `${String(index).padStart(12, '0')},Learner ${index}`);
  assert.equal(csv('LRN,Full name\n' + lines.join('\n')).length, 500);
  assert.equal(csv('LRN,Full name\n\n' + lines.join('\n\n')).length, 500);
  assert.throws(() => csv('LRN,Full name\n' + [...lines, '999999999999,Extra'].join('\n')), { issue: 'limit' });
  const template = XLSX.read(rosterTemplate(true), { type: 'array', cellNF: true });
  assert.equal(template.Sheets.Learners.A2.z, '@');
  assert.equal(template.Sheets.Learners.A1.v, 'LRN');
  assert.equal(template.Sheets.Learners.B1.v, 'Full name');
  assert.throws(() => csv(new TextDecoder().decode(rosterTemplate(false))), { issue: 'empty' });
});

test('existing accounts are skipped regardless of role; invalid roster never makes requests', async () => {
  const rows = roster();
  assert.equal(markExisting(rows, [user(rows[0], 'TEACHER')])[0].status, 'skipped');
  await assert.rejects(createRoster([{ ...rows[0], status: 'invalid' }], {
    list: () => assert.fail('must not list'), create: () => assert.fail('must not create'),
  }, () => {}));
});

test('progressive creation refreshes duplicates, generates distinct PINs and exports confirmed accounts only', async () => {
  const rows = roster();
  const calls = [];
  const result = await createRoster(rows, { list: async () => [], create: async (row, pin) => {
    calls.push({ row, pin }); return user(row);
  } }, () => {});
  assert(result.every(row => row.status === 'created'));
  assert(calls.every(call => /^\d{6}$/.test(call.pin)));
  assert.notEqual(calls[0].pin, calls[1].pin);
  const output = new TextDecoder().decode(credentialCsv([...result, { ...rows[0], status: 'uncertain', pin: '999999' }]));
  assert(output.includes('"000123456789"'));
  assert(!output.includes('999999'));
  const skipped = await createRoster(rows, { list: async () => rows.map(row => user(row)), create: () => assert.fail('must skip') }, () => {});
  assert(skipped.every(row => row.status === 'skipped'));
});

test('lost response preserves successes and reconciliation does not repeat a committed creation', async () => {
  const accounts = [];
  let snapshot;
  let calls = 0;
  const api = { list: async () => accounts, create: async row => {
    accounts.push(user(row));
    if (++calls === 2) throw new HubError('HUB_UNREACHABLE', 0);
    return user(row);
  } };
  await assert.rejects(createRoster(roster(), api, rows => { snapshot = rows; }));
  assert.deepEqual(snapshot.map(row => row.status), ['created', 'uncertain']);
  const resumed = await createRoster(snapshot, api, () => {});
  assert.equal(calls, 2);
  assert.deepEqual(resumed.map(row => row.status), ['created', 'uncertain']);
  assert(!new TextDecoder().decode(credentialCsv(resumed)).includes('123456789012'));
});

test('uncommitted interrupted row retries its original PIN; conflicts skip; auth failure stops', async () => {
  const rows = [{ ...roster()[0], status: 'uncertain', pin: '000123' }];
  const resumed = await createRoster(rows, { list: async () => [], create: async (row, pin) => {
    assert.equal(pin, '000123'); return user(row);
  } }, () => {});
  assert.equal(resumed[0].status, 'created');
  const conflict = await createRoster(roster(), { list: async () => [], create: async () => { throw new HubError('ALREADY_EXISTS', 409); } }, () => {});
  assert(conflict.every(row => row.status === 'skipped' && !row.pin));
  let snapshot;
  let calls = 0;
  await assert.rejects(createRoster(roster(), { list: async () => [], create: async () => {
    calls++; throw new HubError('UNAUTHORIZED', 401);
  } }, rows => { snapshot = rows; }));
  assert.equal(calls, 1);
  assert(snapshot.every(row => row.status === 'pending' && !row.pin));
});

test('session teardown prevents further creation and PIN generation avoids reuse', async () => {
  let active = true;
  let calls = 0;
  await createRoster(roster(), { list: async () => [], create: async row => {
    calls++; active = false; return user(row);
  } }, () => {}, () => active);
  assert.equal(calls, 1);
  const used = new Set();
  for (let count = 0; count < 500; count++) assert.match(generatePin(used), /^\d{6}$/);
  assert.equal(used.size, 500);
});
