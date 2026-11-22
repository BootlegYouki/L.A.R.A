const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

// Run against the isolated Rust activity_preview example, never the Hub's school database.
async function main() {
  const metadata = JSON.parse(fs.readFileSync(process.env.LARA_ACTIVITY_PREVIEW, 'utf8'));
  const evidence = path.resolve(__dirname, '../../docs/evidence/368');
  fs.mkdirSync(evidence, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.LARA_CHROMIUM_EXECUTABLE ? { executablePath: process.env.LARA_CHROMIUM_EXECUTABLE } : {}) });
  const context = await browser.newContext({ viewport: { width: 960, height: 640 } });
  await context.addInitScript(() => {
    localStorage.setItem('lara:admin-configured', 'true');
    window.__TAURI_INTERNALS__ = { invoke: async () => ({ ip: '127.0.0.1', running: true }) };
  });
  let offline = false;
  let reads = 0;
  const errors = [];
  const external = [];
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) { external.push(url.hostname); return route.abort(); }
    if (url.port !== '8080') return route.continue();
    if (url.pathname === '/api/admin/activity-logs') reads++;
    if (offline) return route.abort();
    url.port = String(metadata.http_port);
    const response = await route.fetch({ url: url.toString() });
    return route.fulfill({ response, headers: { ...response.headers(), 'access-control-allow-origin': 'http://127.0.0.1:5175' } });
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  async function visible(locator) { await locator.waitFor({ state: 'visible', timeout: 12000 }); }
  try {
    await page.goto('http://127.0.0.1:5175');
    for (let n = 0; n < 6; n++) await page.getByLabel(`PIN digit ${n + 1}`, { exact: true }).fill('123456'[n]);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'Activity logs', exact: true }).click();
    await visible(page.getByText('Account created', { exact: true }).first());
    await page.getByRole('button', { name: 'Load older', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('tr[data-entry]').length > 100);
    assert((await page.locator('tr[data-entry]').count()) >= 122);
    await page.getByRole('button', { name: /^Details for entry/ }).first().click();
    await visible(page.getByText('Service', { exact: true }).first());
    await page.getByRole('button', { name: 'Pause updates', exact: true }).click();
    const pausedReads = reads;
    await page.waitForTimeout(5500);
    assert.equal(reads, pausedReads, 'pause must stop display polling');
    await page.screenshot({ path: path.join(evidence, 'activity-960.png'), fullPage: false });
    for (const [width, height] of [[720, 480], [1280, 800]]) {
      await page.setViewportSize({ width, height });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `horizontal overflow at ${width}`);
      await page.screenshot({ path: path.join(evidence, `activity-${width}.png`), fullPage: false });
    }
    const smallTargets = await page.locator('button:visible, select:visible, summary:visible').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().height < 39).map(node => node.textContent));
    assert.deepEqual(smallTargets, []);
    await page.getByLabel('Search activity', { exact: true }).fill('nevermatchingevent');
    await visible(page.getByRole('heading', { name: 'No matching entries' }));
    await page.getByLabel('Search activity', { exact: true }).fill('');
    await visible(page.getByText('Account created', { exact: true }).first());
    await page.getByRole('combobox', { name: /^Severity/ }).selectOption('WARNING');
    await visible(page.getByText('Document text extraction failed', { exact: true }));
    assert.equal(await page.getByText('Hub started', { exact: true }).count(), 0);
    await page.getByRole('combobox', { name: /^Severity/ }).selectOption('');
    await visible(page.getByText('Account created', { exact: true }).first());
    await page.getByRole('button', { name: 'Resume updates', exact: true }).click();
    offline = true;
    await visible(page.getByText('Hub unavailable', { exact: true }).last());
    await page.screenshot({ path: path.join(evidence, 'activity-offline.png'), fullPage: false });
    offline = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await page.getByText('Showing the last loaded entries.').waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: 'Accounts', exact: true }).click();
    const hiddenReads = reads;
    await page.waitForTimeout(5500);
    assert.equal(reads, hiddenReads, 'hidden activity page must stop polling');
    await page.getByLabel('Search accounts', { exact: true }).fill('T-0001');
    await visible(page.getByRole('cell', { name: 'T-0001', exact: true }));
    assert.equal(await page.getByRole('cell', { name: 'ADMIN-0001', exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await visible(page.getByRole('button', { name: 'Sign in', exact: true }));
    assert.equal(await page.locator('tr[data-entry]').count(), 0, 'logout clears activity DOM');
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log('PASS real Hub: login, activity paging/details, pause/resume, filtering, offline/retry, hidden polling, account search, logout, 720/960/1280 layouts, 40px controls, no external requests.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
