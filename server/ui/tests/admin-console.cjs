const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

const root = path.resolve(__dirname, '../../..');
const mode = process.argv[2];
assert(['mock', 'real'].includes(mode), 'Choose mock or real; use an isolated development Hub.');
const base = 'http://127.0.0.1:8080';
const evidence = path.join(root, 'server/docs/evidence');

async function main() {
  try {
    await fetch(`${base}/api/admin/users`, { signal: AbortSignal.timeout(1000) });
    throw new Error('Port 8080 is occupied. Stop the development Hub before this test.');
  } catch (error) {
    if (error.message.startsWith('Port 8080')) throw error;
  }
  fs.mkdirSync(evidence, { recursive: true });
  const dataDir = fs.mkdtempSync(path.join(root, 'server/backend/target/admin-console-'));
  const hub = mode === 'mock'
    ? spawn(process.env.LARA_PYTHON || 'python3', [path.join(root, 'scripts/mock_hub.py')], { cwd: root, stdio: 'ignore' })
    : spawn(path.join(root, `server/backend/target/debug/lara-hub${process.platform === 'win32' ? '.exe' : ''}`), [], {
      cwd: root, stdio: 'ignore', env: { ...process.env, LARA_DATA_DIR: dataDir, LARA_TEST_SEED: '0' },
    });
  let browser;
  let page;
  let releaseUsers = () => {};
  try {
    for (let attempt = 0; ; attempt++) {
      try { await fetch(`${base}/api/admin/users`); break; }
      catch { if (attempt === 50) throw new Error('Test Hub did not start'); await new Promise(r => setTimeout(r, 200)); }
    }
    browser = await chromium.launch({ headless: true,
      ...(process.env.LARA_CHROMIUM_EXECUTABLE ? { executablePath: process.env.LARA_CHROMIUM_EXECUTABLE } : {}),
    });
    const context = await browser.newContext({ viewport: { width: 960, height: 800 } });
    const remoteRequests = [];
    await context.route('**/*', route => {
      const host = new URL(route.request().url()).hostname;
      if (host !== '127.0.0.1' && host !== 'localhost') {
        remoteRequests.push(host); return route.abort();
      }
      return route.continue();
    });
    // The browser has no Tauri OS bridge. Only lan_address is simulated; REST is the test Hub.
    await context.addInitScript(() => {
      window.__TAURI_INTERNALS__ = { invoke: async () => ({ ip: '127.0.0.1', running: true }) };
    });
    page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(30000);
    const visible = locator => locator.waitFor({ state: 'visible' });
    async function pin(value) {
      // Password inputs have no implicit textbox role.
      for (let n = 0; n < 4; n++) await page.getByLabel(`PIN digit ${n + 1}`, { exact: true }).fill(value[n]);
    }
    const usersReady = new Promise(resolve => { releaseUsers = resolve; });
    await page.route(`${base}/api/admin/users`, async route => { await usersReady; await route.continue(); });
    await page.goto('http://127.0.0.1:5173', { timeout: 60000, waitUntil: 'domcontentloaded' });
    await visible(page.getByRole('heading', { name: 'Set the admin PIN' }));
    await page.getByLabel('Full name', { exact: true }).fill('Test Coordinator');
    await pin('1234');
    await page.getByRole('button', { name: 'Set admin PIN', exact: true }).click();
    if (mode === 'mock') {
      await visible(page.getByRole('heading', { name: 'Admin sign in' }));
      await pin('1234');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    }
    await visible(page.getByRole('heading', { name: 'Accounts', exact: true }));
    await visible(page.getByRole('status', { name: 'Loading accounts' }));
    await page.screenshot({ path: path.join(evidence, `issue-43-${mode}-loading.png`), fullPage: true });
    releaseUsers();
    await page.unrouteAll({ behavior: 'wait' });
    await visible(page.getByRole('cell', { name: 'ADMIN-0001', exact: true }));
    console.log('PASS setup/login and admin-only account list');

    for (const [id, name, role] of [['T-ADMIN-UI', 'Test Teacher', 'Teacher'], ['123456789019', 'Test Learner', 'Learner']]) {
      await page.getByRole('button', { name: 'Create account', exact: true }).click();
      await page.getByLabel('Full name', { exact: true }).fill(name);
      await page.getByLabel('Teacher ID / Learner LRN', { exact: true }).fill(id);
      await page.getByRole('radio', { name: role, exact: true }).check();
      await pin('0284');
      await page.getByRole('button', { name: 'Create account', exact: true }).last().click();
      await visible(page.getByRole('heading', { name: 'PIN saved' }));
      assert.equal(await page.getByText('0284', { exact: true }).count(), 1);
      await page.getByRole('button', { name: 'I have recorded the PIN' }).click();
      assert.equal(await page.getByText('0284', { exact: true }).count(), 0);
      const login = await context.request.post(`${base}/api/auth/login`, { data: { lrn_or_id: id, pin: '0284' } });
      assert.equal(login.status(), 200);
      const session = await login.json();
      assert.equal((await context.request.get(`${base}/api/admin/users`, { headers: { Authorization: `Bearer ${session.token}` } })).status(), 403);
    }
    console.log('PASS teacher and learner creation, one-time PIN, login, role guards');
    await page.getByRole('button', { name: 'Reset PIN Test Teacher', exact: true }).click();
    await pin('0907');
    await page.getByRole('button', { name: 'Reset PIN', exact: true }).click();
    await visible(page.getByRole('heading', { name: 'PIN saved' }));
    await page.keyboard.press('Escape');
    assert.equal(await page.getByText('0907', { exact: true }).count(), 0);
    assert(await page.getByRole('button', { name: 'Reset PIN Test Teacher', exact: true }).evaluate(node => node === document.activeElement));
    for (const [value, status] of [['0284', 401], ['0907', 200]]) {
      assert.equal((await context.request.post(`${base}/api/auth/login`, { data: { lrn_or_id: 'T-ADMIN-UI', pin: value } })).status(), status);
    }
    await page.screenshot({ path: path.join(evidence, `issue-43-${mode}-accounts.png`), fullPage: true });
    console.log('PASS PIN reset and old/new login checks');

    await page.getByRole('button', { name: 'Create account', exact: true }).click();
    await page.getByLabel('Full name', { exact: true }).fill('Unsaved account');
    await page.route('http://127.0.0.1:8080/**', route => route.abort());
    await visible(page.getByText('Hub unavailable', { exact: false }));
    assert.equal(await page.getByLabel('Full name', { exact: true }).inputValue(), 'Unsaved account');
    assert(await page.getByRole('button', { name: 'Create account', exact: true }).last().isDisabled());
    await page.screenshot({ path: path.join(evidence, `issue-43-${mode}-offline.png`), fullPage: true });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert(await page.getByRole('cell', { name: 'T-ADMIN-UI', exact: true }).isVisible());
    await page.unroute('http://127.0.0.1:8080/**');
    await page.getByRole('button', { name: 'Refresh accounts' }).click();
    await page.getByText('Hub unavailable', { exact: false }).waitFor({ state: 'hidden' });
    console.log('PASS unreachable Hub keeps accounts and draft, disables writes and recovers');

    await page.route('http://127.0.0.1:8080/api/admin/users', route => route.fulfill({ json: [] }));
    await page.getByRole('button', { name: 'Refresh accounts' }).click();
    await visible(page.getByRole('heading', { name: 'No accounts to show' }));
    await page.unroute('http://127.0.0.1:8080/api/admin/users');
    await page.getByRole('button', { name: 'Refresh accounts' }).click();
    await visible(page.getByRole('cell', { name: 'T-ADMIN-UI', exact: true }));
    const buttons = await page.getByRole('button').evaluateAll(nodes => nodes.filter(n => n.getBoundingClientRect().height > 0).map(n => n.getBoundingClientRect().height));
    assert(buttons.every(height => height >= 40), 'desktop button targets');
    assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), ['lara:admin-configured']);
    assert.equal(await page.evaluate(() => sessionStorage.length), 0);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await visible(page.getByRole('heading', { name: 'Admin sign in' }));
    await page.reload();
    await visible(page.getByRole('heading', { name: 'Admin sign in' }));
    assert.equal(await page.getByRole('cell', { name: 'T-ADMIN-UI', exact: true }).count(), 0);
    assert.deepEqual(remoteRequests, []);
    assert.deepEqual(errors, []);
    console.log('PASS empty state, desktop targets, sign-out, reload, memory-only secrets and local requests');
  } finally {
    releaseUsers();
    await page?.unrouteAll({ behavior: 'ignoreErrors' });
    await browser?.close();
    hub.kill();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
