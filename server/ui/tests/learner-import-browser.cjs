const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const XLSX = require('xlsx');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

const root = path.resolve(__dirname, '../../..');
const evidence = path.join(root, 'server/docs/evidence/account-import');

async function main() {
  const fixture = spawn(process.env.LARA_IMPORT_FIXTURE || path.join(root, 'server/backend/target/debug/examples', `admin_console_fixture${process.platform === 'win32' ? '.exe' : ''}`), [],
    { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
  let browser;
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'lara-import-test-'));
  try {
    const port = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Fixture startup timed out')), 30000);
      fixture.on('error', reject);
      fixture.on('exit', code => { clearTimeout(timeout); reject(new Error(`Fixture exited: ${code}`)); });
      fixture.stdout.on('data', data => {
        const found = String(data).match(/IMPORT_FIXTURE_PORT=(\d+)/);
        if (found) { clearTimeout(timeout); resolve(Number(found[1])); }
      });
    });
    const hub = `http://127.0.0.1:${port}`;
    browser = await chromium.launch({ headless: true,
      ...(process.env.LARA_CHROMIUM_EXECUTABLE ? { executablePath: process.env.LARA_CHROMIUM_EXECUTABLE } : {}),
    });
    const context = await browser.newContext({ viewport: { width: 1000, height: 820 }, acceptDownloads: true });
    const errors = [];
    const remote = [];
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (!['localhost', '127.0.0.1'].includes(url.hostname)) { remote.push(url.hostname); return route.abort(); }
      if (url.port === '8080') return route.fulfill({ response: await route.fetch({ url: `${hub}${url.pathname}` }) });
      return route.continue();
    });
    await context.addInitScript(() => {
      window.__TAURI_INTERNALS__ = { invoke: async () => ({ ip: '127.0.0.1', running: true }) };
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(15000);
    await page.goto(process.env.LARA_IMPORT_UI_URL || 'http://127.0.0.1:5173');
    await page.getByLabel('Full name', { exact: true }).fill('Import test admin');
    for (let digit = 1; digit <= 6; digit++) await page.getByLabel(`PIN digit ${digit}`, { exact: true }).fill(String(digit));
    await page.getByRole('button', { name: 'Set admin PIN', exact: true }).click();
    await page.getByRole('button', { name: 'Accounts', exact: true }).click();
    await page.getByRole('heading', { name: 'Accounts', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Import learners', exact: true }).click();
    const dialog = page.getByRole('dialog');
    const upload = async content => dialog.getByLabel('Learner roster file').setInputFiles({ name: 'learners.csv', mimeType: 'text/csv', buffer: Buffer.from(content) });
    const start = () => dialog.getByRole('button', { name: 'Create learner accounts', exact: true });
    const download = async name => {
      const waiting = page.waitForEvent('download');
      await dialog.getByRole('button', { name, exact: true }).click();
      const item = await waiting;
      const target = path.join(temp, item.suggestedFilename());
      await item.saveAs(target);
      return fs.readFileSync(target);
    };
    const template = await download('Excel template');
    assert.equal(XLSX.read(template).Sheets.Learners.A1.v, 'LRN');
    await upload('LRN,Full name\n123,Ana\n123456789012,Ben\n123456789012,Duplicate');
    await dialog.getByText('LRN must contain exactly 12 digits.', { exact: false }).waitFor();
    assert(await start().isDisabled());
    fs.mkdirSync(evidence, { recursive: true });
    await page.screenshot({ path: path.join(evidence, 'invalid-roster.png') });

    const book = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([['LRN', 'Full name'], ['000123456789', 'José Test'], ['123456789012', 'Ana Test']]), 'Learners');
    await dialog.getByLabel('Learner roster file').setInputFiles({ name: 'learners.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: XLSX.write(book, { type: 'buffer', bookType: 'xlsx' }) });
    await dialog.getByText('Ready: 2', { exact: false }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'preview.png') });
    await start().click();
    await dialog.getByText('Import checked.', { exact: false }).waitFor();
    const credentials = XLSX.read(await download('Download credentials CSV'), { type: 'buffer', raw: true });
    const accounts = XLSX.utils.sheet_to_json(credentials.Sheets[credentials.SheetNames[0]]);
    assert.equal(accounts.length, 2);
    assert.equal(accounts[0].LRN, '000123456789');
    for (const account of accounts) {
      assert.match(account.PIN, /^\d{6}$/);
      const response = await context.request.post(`${hub}/api/auth/login`, { data: { lrn_or_id: account.LRN, pin: account.PIN } });
      assert.equal(response.status(), 200);
      assert.equal((await response.json()).user.role, 'STUDENT');
    }
    await page.screenshot({ path: path.join(evidence, 'results.png') });
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await dialog.getByRole('button', { name: 'Close and clear PINs', exact: true }).click();
    await page.getByRole('button', { name: 'Import learners', exact: true }).click();
    await upload('LRN,Full name\n000123456789,Renamed existing\n123456789012,Ana Test');
    await dialog.getByText('Ready: 0 · Skipped: 2', { exact: false }).waitFor();
    assert.equal(await start().count(), 0);
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('rowheader', { name: 'José Test', exact: true }).waitFor();
    console.log('PASS Excel/CSV validation, templates, generated credentials, learner login, duplicate preservation and credential dismissal');

    await page.getByRole('button', { name: 'Import learners', exact: true }).click();
    await upload('LRN,Full name\n123456789013,Confirmed First\n123456789014,Lost Response\n123456789015,Continue Last');
    await dialog.getByText('Ready: 3', { exact: false }).waitFor();
    let writes = 0;
    const interrupted = async route => {
      if (route.request().method() !== 'POST') return route.fallback();
      const response = await route.fetch({ url: `${hub}/api/admin/users` });
      if (++writes === 2) return route.abort();
      return route.fulfill({ response });
    };
    await page.route('http://127.0.0.1:8080/api/admin/users', interrupted);
    await start().click();
    await dialog.getByText('The Hub could not finish this import.', { exact: false }).waitFor();
    await page.unroute('http://127.0.0.1:8080/api/admin/users', interrupted);
    await dialog.getByRole('button', { name: 'Check and continue', exact: true }).click();
    await dialog.getByText('Import checked.', { exact: false }).waitFor();
    await dialog.getByText('Not confirmed: may need PIN reset', { exact: true }).waitFor();
    const partial = XLSX.read(await download('Download credentials CSV'), { type: 'buffer', raw: true });
    const partialRows = XLSX.utils.sheet_to_json(partial.Sheets[partial.SheetNames[0]]);
    assert.deepEqual(partialRows.map(row => row.LRN), ['123456789013', '123456789015']);
    await page.screenshot({ path: path.join(evidence, 'interrupted.png') });
    await page.keyboard.press('Escape');
    await dialog.getByRole('button', { name: 'Close and clear PINs', exact: true }).click();
    await page.getByRole('button', { name: 'Import learners', exact: true }).click();
    await upload('LRN,Full name\n123456789016,Offline Draft');
    await dialog.getByText('Ready: 1', { exact: false }).waitFor();
    const offline = route => route.abort();
    await page.route('http://127.0.0.1:8080/api/admin/users', offline);
    await start().click();
    await dialog.getByText('The Hub could not finish this import.', { exact: false }).waitFor();
    await dialog.getByText('Ready: 1', { exact: false }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'offline.png') });
    await page.unroute('http://127.0.0.1:8080/api/admin/users', offline);
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Refresh accounts', exact: true }).click();
    await page.getByText('Hub unavailable', { exact: false }).waitFor({ state: 'hidden' });
    const buttons = await page.getByRole('button').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().height > 0).map(node => node.getBoundingClientRect().height));
    assert(buttons.every(height => height >= 40));
    assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), ['lara:admin-configured']);
    assert.equal(await page.evaluate(() => sessionStorage.length), 0);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByRole('dialog', { name: 'Sign out of the Hub?' }).getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByRole('heading', { name: 'Admin sign in' }).waitFor();
    assert.equal(await page.getByRole('dialog').count(), 0);
    const expiry = async route => {
      const response = await route.fetch({ url: `${hub}/api/auth/login` });
      const body = await response.json();
      body.expires_at = Date.now() + 3000;
      return route.fulfill({ response, json: body });
    };
    await page.route('http://127.0.0.1:8080/api/auth/login', expiry);
    for (let digit = 1; digit <= 6; digit++) await page.getByLabel(`PIN digit ${digit}`, { exact: true }).fill(String(digit));
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'Accounts', exact: true }).click();
    await page.getByRole('button', { name: 'Import learners', exact: true }).click();
    await upload('LRN,Full name\n123456789017,Expiry First\n123456789018,Expiry Second');
    await dialog.getByText('Ready: 2', { exact: false }).waitFor();
    let expiredWrites = 0;
    await page.route('http://127.0.0.1:8080/api/admin/users', async route => {
      if (route.request().method() !== 'POST') return route.fallback();
      expiredWrites++;
      const response = await route.fetch({ url: `${hub}/api/admin/users` });
      await new Promise(resolve => setTimeout(resolve, 4000));
      return route.fulfill({ response });
    });
    await start().click();
    await page.getByRole('heading', { name: 'Admin sign in' }).waitFor();
    assert.equal(await page.getByRole('dialog').count(), 0);
    await page.unrouteAll({ behavior: 'wait' });
    assert.equal(expiredWrites, 1);
    assert.equal(await page.getByRole('button', { name: 'Download credentials CSV' }).count(), 0);
    assert.deepEqual(remote, []);
    assert.deepEqual(errors, []);
    console.log('PASS lost response recovery, confirmed-only export, offline draft retention, session expiry stops further creation, desktop targets and local requests');
  } finally {
    await browser?.close();
    fixture.stdin.end();
    await new Promise(resolve => { if (!fixture.pid || fixture.exitCode !== null) resolve(); else fixture.once('exit', resolve); });
    fs.rmSync(temp, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
