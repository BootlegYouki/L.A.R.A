const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.LARA_CHROMIUM_EXECUTABLE });
  const context = await browser.newContext({ viewport: { width: 1100, height: 900 } });
  const admin = { id: 'admin', lrn_or_id: 'ADMIN-0001', full_name: 'Demo Admin', role: 'ADMIN' };
  let users = [admin, ...Array.from({ length: 31 }, (_, i) => ({ id: `demo-${i + 1}`,
    lrn_or_id: String(900000000001 + i), full_name: `Demo Account ${String(i + 1).padStart(2, '0')}`,
    role: i < 12 ? 'TEACHER' : 'STUDENT' }))];
  let reads = 0;
  let offline = false;
  let logouts = 0;
  let finishLogout;
  const errors = [];
  const external = [];
  await context.addInitScript(() => {
    localStorage.setItem('lara:admin-configured', 'true');
    window.__TAURI_INTERNALS__ = { invoke: async () => ({ ip: '127.0.0.1', running: true }) };
  });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) { external.push(url.hostname); return route.abort(); }
    if (url.port !== '8080') return route.continue();
    if (offline) return route.abort();
    if (url.pathname === '/api/auth/login') {
      if (route.request().postDataJSON().pin === '000000') return route.fulfill({ status: 401, json: { error: { code: 'UNAUTHORIZED' } } });
      return route.fulfill({ json: { token: 'synthetic-admin-session', expires_at: Date.now() + 3600000, user: admin } });
    }
    if (url.pathname === '/api/admin/users') { reads++; return route.fulfill({ json: users }); }
    if (url.pathname === '/api/auth/logout') { logouts++; await new Promise(resolve => { finishLogout = resolve; }); return route.fulfill({ json: { ok: true } }); }
    return route.fulfill({ status: 404, json: { error: { code: 'NOT_FOUND' } } });
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(process.env.LARA_ACCOUNTS_UI_URL || 'http://127.0.0.1:5178');
    const evidence = path.resolve(__dirname, '../../docs/evidence/accounts-pagination');
    fs.mkdirSync(evidence, { recursive: true });
    await page.setViewportSize({ width: 960, height: 640 });
    await page.screenshot({ path: path.join(evidence, 'sign-in.png') });
    await page.setViewportSize({ width: 720, height: 480 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(await page.locator('.admin-auth button').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().height < 39).map(node => node.textContent)), []);
    await page.getByRole('button', { name: 'Sign in', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(evidence, 'sign-in-720x480.png') });
    await page.setViewportSize({ width: 960, height: 640 });
    assert.equal(await page.getByRole('button', { name: 'Set admin PIN', exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('The PIN must be 6 digits.', { exact: true }).waitFor();
    for (let i = 1; i <= 6; i++) await page.getByLabel(`PIN digit ${i}`, { exact: true }).fill('0');
    await page.getByRole('button', { name: 'Show PIN', exact: true }).click();
    assert.equal(await page.getByLabel('PIN digit 1', { exact: true }).getAttribute('type'), 'text');
    await page.getByRole('button', { name: 'Hide PIN', exact: true }).click();
    assert.equal(await page.getByLabel('PIN digit 1', { exact: true }).getAttribute('type'), 'password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('alert').getByText('The admin PIN was not accepted. Check it and try again.', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'sign-in-error.png') });
    offline = true;
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('The Hub is not responding.', { exact: false }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'sign-in-offline.png') });
    offline = false;
    for (let i = 1; i <= 6; i++) await page.getByLabel(`PIN digit ${i}`, { exact: true }).fill(String(i));
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'Accounts', exact: true }).click();
    const rows = page.locator('#accounts-table tbody tr');
    const nav = page.getByRole('navigation', { name: 'Account pages' });
    const next = nav.getByRole('button', { name: 'Next', exact: true });
    const previous = nav.getByRole('button', { name: 'Previous', exact: true });
    const size = nav.getByLabel('Rows per page');
    const search = page.getByLabel('Search accounts', { exact: true });
    const role = page.getByRole('combobox', { name: /^Role/ });
    await nav.getByText('Showing 1–10 of 32 accounts', { exact: true }).waitFor();
    assert.equal(await rows.count(), 10);
    assert(await previous.isDisabled());
    await next.focus();
    await page.keyboard.press('Enter');
    await nav.getByText('Page 2 of 4', { exact: true }).waitFor();
    await page.getByRole('rowheader', { name: 'Demo Account 10', exact: true }).waitFor();
    const baseline = reads;
    await page.waitForResponse(response => response.url().endsWith('/api/admin/users') && reads > baseline, { timeout: 7000 });
    assert(await nav.getByText('Page 2 of 4', { exact: true }).isVisible());
    await next.click(); await next.click();
    await nav.getByText('Showing 31–32 of 32 accounts', { exact: true }).waitFor();
    assert.equal(await rows.count(), 2);
    assert(await next.isDisabled());
    await page.getByRole('button', { name: 'Reset PIN Demo Account 31', exact: true }).click();
    await page.getByRole('dialog').getByText('Demo Account 31', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await role.selectOption('TEACHER');
    await nav.getByText('Page 1 of 2', { exact: true }).waitFor();
    await next.click();
    assert.equal(await rows.count(), 2);
    await search.fill('Demo Account 12');
    await nav.getByText('Showing 1–1 of 1 accounts', { exact: true }).waitFor();
    await search.fill('no matching account');
    await page.getByText('No accounts match these filters.', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'accounts-empty.png') });
    assert(await next.isDisabled()); assert(await previous.isDisabled());
    await nav.getByText('Showing 0–0 of 0 accounts', { exact: true }).waitFor();
    await search.fill(''); await role.selectOption('');
    await next.click(); await size.selectOption('25');
    await nav.getByText('Showing 1–25 of 32 accounts', { exact: true }).waitFor();
    assert.equal(await rows.count(), 25);
    await next.click(); assert.equal(await rows.count(), 7);
    await size.selectOption('50'); assert.equal(await rows.count(), 32);
    await size.selectOption('10'); await next.click(); await next.click(); await next.click();
    offline = true;
    await page.getByRole('button', { name: 'Refresh accounts', exact: true }).click();
    await page.getByText('Hub unavailable', { exact: false }).first().waitFor();
    await page.screenshot({ path: path.join(evidence, 'accounts-offline.png') });
    assert.equal(await rows.count(), 2);
    await previous.click(); assert.equal(await rows.count(), 10);
    offline = false;
    users = users.slice(0, 6);
    await page.getByRole('button', { name: 'Refresh accounts', exact: true }).click();
    await nav.getByText('Showing 1–6 of 6 accounts', { exact: true }).waitFor();
    assert(await next.isDisabled());
    users = [admin, ...Array.from({ length: 31 }, (_, i) => ({ id: `screenshot-${i}`, lrn_or_id: String(900000000001 + i), full_name: `Demo Account ${String(i + 1).padStart(2, '0')}`, role: 'STUDENT' }))];
    await page.getByRole('button', { name: 'Refresh accounts', exact: true }).click();
    await nav.getByText('Showing 1–10 of 32 accounts', { exact: true }).waitFor();
    for (const [width, height] of [[720, 480], [720, 640], [960, 640], [1100, 900]]) {
      await page.setViewportSize({ width, height });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), `Page should not scroll at ${width}x${height}`);
      assert(await nav.isVisible());
      assert.deepEqual(await nav.locator('button, select').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().height < 39).map(node => node.textContent)), []);
      const scroll = page.getByRole('region', { name: 'Accounts table', exact: true });
      assert(await scroll.evaluate(node => node.clientHeight >= 40), 'Table must remain usable');
      assert(await nav.evaluate(node => node.getBoundingClientRect().bottom <= innerHeight));
      const headingTop = await page.locator('#accounts-table thead th').first().evaluate(node => node.getBoundingClientRect().top);
      await scroll.evaluate(node => { node.scrollTop = node.scrollHeight; });
      assert(Math.abs(await page.locator('#accounts-table thead th').first().evaluate(node => node.getBoundingClientRect().top) - headingTop) < 1, 'Column headings should remain visible');
      assert(await scroll.evaluate(node => node.scrollTop + node.clientHeight >= node.scrollHeight - 1), 'All rows must be reachable inside the table');
      await scroll.evaluate(node => { node.scrollTop = 0; node.focus({ preventScroll: true }); });
      await page.screenshot({ path: path.join(evidence, `accounts-${width}x${height}.png`), fullPage: false });
    }
    const signOut = page.getByRole('button', { name: 'Sign out', exact: true });
    await signOut.click();
    const confirmation = page.getByRole('dialog', { name: 'Sign out of the Hub?' });
    await confirmation.waitFor();
    assert(await confirmation.getByRole('button', { name: 'Cancel', exact: true }).evaluate(node => node === document.activeElement));
    assert.equal(logouts, 0);
    await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert(await signOut.evaluate(node => node === document.activeElement));
    await signOut.click(); await page.keyboard.press('Escape');
    assert.equal(logouts, 0);
    await signOut.click();
    await page.screenshot({ path: path.join(evidence, 'sign-out-confirmation.png') });
    await confirmation.getByRole('button', { name: 'Sign out', exact: true }).click();
    await confirmation.getByRole('button', { name: 'Signing out…', exact: true }).waitFor();
    assert(await confirmation.getByRole('button', { name: 'Cancel', exact: true }).isDisabled());
    await page.keyboard.press('Escape'); assert(await confirmation.isVisible());
    while (!finishLogout) await page.waitForTimeout(10);
    assert.equal(logouts, 1); finishLogout();
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    assert.equal(await page.getByLabel('PIN digit 1', { exact: true }).inputValue(), '');
    assert.equal(await page.getByLabel('PIN digit 1', { exact: true }).getAttribute('type'), 'password');
    assert.deepEqual(errors, []); assert.deepEqual(external, []);
    console.log('PASS sign-in validation, PIN visibility, rejected/offline login, sign-out cancellation/Escape/focus, confirmed single logout, pending controls, cleared PIN, account pagination and responsive scrolling. Synthetic REST responses; no user accounts changed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
