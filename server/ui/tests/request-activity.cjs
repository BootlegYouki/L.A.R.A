const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

async function main() {
  const metadata = JSON.parse(fs.readFileSync(process.env.LARA_ACTIVITY_PREVIEW, 'utf8'));
  const base = `http://127.0.0.1:${metadata.http_port}`;
  const evidence = path.resolve(__dirname, '../../docs/evidence/request-activity');
  fs.mkdirSync(evidence, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.LARA_CHROMIUM_EXECUTABLE });
  const context = await browser.newContext({ viewport: { width: 960, height: 800 } });
  await context.addInitScript(() => {
    localStorage.setItem('lara:admin-configured', 'true');
    window.__TAURI_INTERNALS__ = { invoke: async () => ({ ip: '127.0.0.1', running: true }) };
  });
  let offline = false;
  const external = [];
  const errors = [];
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) { external.push(url.hostname); return route.abort(); }
    if (url.port !== '8080') return route.continue();
    if (offline) return route.abort();
    url.port = String(metadata.http_port);
    const response = await route.fetch({ url: url.toString() });
    return route.fulfill({ response, headers: { ...response.headers(), 'access-control-allow-origin': 'http://127.0.0.1:5175' } });
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  try {
    assert.equal((await context.request.get(`${base}/api/materials/PRIVATE_ID/chunks?token=QUERY_SECRET`)).status(), 401);
    assert.equal((await context.request.get(`${base}/PRIVATE_PATH?lrn=123456789012`)).status(), 404);
    await page.goto('http://127.0.0.1:5175');
    for (let n = 0; n < 6; n++) await page.getByLabel(`PIN digit ${n + 1}`, { exact: true }).fill('123456'[n]);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'Activity logs', exact: true }).click();
    const search = page.getByLabel('Search activity', { exact: true });
    const requestRow = (endpoint, status) => page.locator('tr[data-entry]').filter({ has: page.getByText(endpoint, { exact: true }) }).filter({ has: page.getByRole('cell', { name: String(status), exact: true }) }).first();
    async function filtered(query, severity, action) {
      const pending = page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname === '/api/admin/activity-logs' && url.searchParams.get('q') === query
          && (url.searchParams.get('severity') || '') === severity && response.status() === 200;
      });
      await action();
      const data = await (await pending).json();
      assert(data.entries.length > 0);
      assert(data.entries.every(entry => entry.details.endpoint === query));
    }
    await filtered('/api/admin/users', '', () => search.fill('/api/admin/users'));
    await requestRow('/api/admin/users', 200).waitFor();
    await page.getByRole('button', { name: /^Details for entry/ }).first().click();
    await page.getByText('HTTP method', { exact: true }).first().waitFor();
    await page.getByText('Status', { exact: true }).first().waitFor();
    await filtered('/api/materials/{id}/chunks', '', () => search.fill('/api/materials/{id}/chunks'));
    await requestRow('/api/materials/{id}/chunks', 401).waitFor();
    await filtered('/api/materials/{id}/chunks', 'WARNING', () => page.getByRole('combobox', { name: /^Severity/ }).selectOption('WARNING'));
    await requestRow('/api/materials/{id}/chunks', 401).waitFor();
    await page.getByRole('button', { name: /^Details for entry/ }).first().click();
    const text = await page.locator('body').innerText();
    for (const secret of ['PRIVATE_ID', 'QUERY_SECRET', 'PRIVATE_PATH', '123456789012']) assert(!text.includes(secret));
    await page.screenshot({ path: path.join(evidence, 'request-details.png'), fullPage: false });
    for (const width of [720, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    }
    const smallTargets = await page.locator('button:visible, select:visible, summary:visible').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().height < 39).map(node => node.textContent));
    assert.deepEqual(smallTargets, []);
    await page.setViewportSize({ width: 960, height: 800 });
    offline = true;
    await page.getByText('Hub unavailable', { exact: true }).last().waitFor({ timeout: 12000 });
    await requestRow('/api/materials/{id}/chunks', 401).waitFor();
    await page.screenshot({ path: path.join(evidence, 'request-offline.png'), fullPage: false });
    offline = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await page.getByText('Showing the last loaded entries.').waitFor({ state: 'hidden' });
    const unfiltered = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === '/api/admin/activity-logs' && !url.searchParams.has('q') && !url.searchParams.has('severity') && response.status() === 200;
    });
    await page.getByRole('button', { name: 'Clear filters', exact: true }).first().click();
    await unfiltered;
    await page.getByRole('button', { name: 'Pause updates', exact: true }).click();
    while (await page.locator('.activity-table button[aria-expanded="true"]').count()) await page.locator('.activity-table button[aria-expanded="true"]').first().click();
    assert(await page.locator('tr[data-entry]').count() >= 4);
    await page.locator('.activity-table-scroll').evaluate(node => { node.scrollTop = 0; node.scrollLeft = 0; });
    await page.screenshot({ path: path.join(evidence, 'request-table.png'), fullPage: false });
    const firstDetails = page.getByRole('button', { name: /^Details for entry/ }).first();
    await firstDetails.focus();
    await page.keyboard.press('Enter');
    assert.equal(await firstDetails.getAttribute('aria-expanded'), 'true');
    await page.keyboard.press('Enter');
    assert.equal(await firstDetails.getAttribute('aria-expanded'), 'false');
    await search.fill('no-matching-request');
    await page.getByRole('heading', { name: 'No matching entries', exact: true }).waitFor();
    await page.screenshot({ path: path.join(evidence, 'request-empty.png'), fullPage: false });
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log('PASS request activity: real HTTP requests, templates/status/details, search/severity, no private inputs, offline retention/retry, 720/960/1280 layouts, 40px controls, no external requests.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
