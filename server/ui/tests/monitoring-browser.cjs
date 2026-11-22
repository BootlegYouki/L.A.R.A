const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.LARA_PLAYWRIGHT_PACKAGE || 'playwright');

async function main() {
  const evidence = path.resolve(__dirname, '../../docs/evidence/monitoring');
  fs.mkdirSync(evidence, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.LARA_CHROMIUM_EXECUTABLE });
  const context = await browser.newContext({ viewport: { width: 960, height: 800 } });
  await context.addInitScript(() => {
    localStorage.setItem('lara:admin-configured', 'true');
    window.monitorMode = 'ok';
    window.monitorCalls = 0;
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      if (command === 'lan_address') return { ip: '192.168.1.20', running: true };
      if (command !== 'hub_metrics') throw 'UNKNOWN_COMMAND';
      if (args.token !== 'monitor-token') throw 'UNAUTHORIZED';
      window.monitorCalls++;
      if (window.monitorMode === 'failed') throw 'MONITORING_UNAVAILABLE';
      if (window.monitorMode === 'expired') throw 'UNAUTHORIZED';
      const sample = window.monitorCalls;
      return { cpu_percent: 20 + sample % 40, hub_cpu_percent: 3, ram_used: 8 * 1024 ** 3,
        ram_total: 16 * 1024 ** 3, hub_ram: 256 * 1024 ** 2, disk_free: 50 * 1024 ** 3,
        disk_total: 500 * 1024 ** 3, uptime_seconds: 3720,
        adapters: window.monitorMode === 'missing' ? [] : [
          { name: 'Ethernet', classroom: true, received_per_second: 1024 ** 2, sent_per_second: 128 * 1024 },
          { name: 'Wi-Fi', classroom: false, received_per_second: 8 * 1024, sent_per_second: 2 * 1024 },
        ] };
    } };
  });
  const external = [], errors = [];
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) { external.push(url.hostname); return route.abort(); }
    if (url.port !== '8080') return route.continue();
    const user = { id: 'admin', lrn_or_id: 'ADMIN-0001', full_name: 'Hub admin', role: 'ADMIN' };
    const data = url.pathname === '/api/auth/login' ? { token: 'monitor-token', expires_at: Date.now() + 3600000, user }
      : url.pathname === '/api/admin/users' ? [user]
      : url.pathname === '/api/admin/activity-logs' ? { entries: [], next_before: null, latest_seq: 0, history_id: 'preview', oldest_seq: null }
      : {};
    return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(data) });
  });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  const monitor = page.locator('.monitor-page');
  const count = () => monitor.getAttribute('data-samples').then(Number);
  async function signIn() {
    for (let i = 1; i <= 6; i++) await page.getByLabel(`PIN digit ${i}`, { exact: true }).fill('1');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('Resource usage', { exact: true }).waitFor();
  }
  try {
    await page.goto('http://127.0.0.1:5175');
    assert.equal(await page.evaluate(() => window.monitorCalls), 0);
    await signIn();
    await page.getByText('8 GiB', { exact: true }).first().waitFor();
    await page.waitForFunction(() => Number(document.querySelector('.monitor-page')?.dataset.samples) >= 3);
    assert.equal(await page.locator('.monitor-chart').count(), 3);
    const cpu = page.locator('.monitor-card-cpu');
    const ram = page.locator('.monitor-card-ram');
    assert((await cpu.innerText()).includes('Processor (CPU)'));
    assert((await cpu.innerText()).includes('This computer'));
    assert((await cpu.innerText()).includes('L.A.R.A Hub'));
    assert((await ram.innerText()).includes('256 MiB'));
    assert((await ram.innerText()).includes('8 GiB'));
    assert.equal(await page.locator('.monitor-chart-line').count(), 1);
    assert.equal(await page.locator('.monitor-chart-area .monitor-area-fill').count(), 1);
    assert(await page.locator('.monitor-chart-bar rect').count() >= 2);
    const charts = page.locator('.monitor-chart');
    assert.deepEqual(await charts.first().locator('.monitor-y-ticks span').allTextContents(), ['100%', '75%', '50%', '25%', '0%']);
    assert.deepEqual(await charts.first().locator('.monitor-axis span').allTextContents(), ['10 min ago', '5 min ago', 'Now']);
    assert((await charts.nth(1).locator('.monitor-y-ticks').innerText()).includes('16 GiB'));
    assert((await charts.nth(2).locator('.monitor-y-ticks').innerText()).includes('1 MiB/s'));
    const colors = await charts.evaluateAll(nodes => nodes.map(node => getComputedStyle(node).borderTopColor));
    for (let i = 0; i < 3; i++) {
      const chart = charts.nth(i);
      const picker = chart.getByRole('combobox');
      assert.equal(await chart.locator('.monitor-y-ticks span').count(), 5);
      assert.equal(await chart.locator('.monitor-timeline').getByText('Time', { exact: true }).count(), 1);
      assert((await picker.boundingBox()).height >= 40);
      for (const kind of ['line', 'area', 'bar']) {
        await picker.selectOption(kind);
        assert((await chart.getAttribute('class')).includes(`monitor-chart-${kind}`));
        if (kind === 'area') assert.equal(await chart.locator('.monitor-area-fill').count(), 1);
        if (kind === 'bar') {
          assert(await chart.locator('rect').count() >= 2);
          const tooltip = await chart.locator('rect title').first().textContent();
          assert(tooltip.includes(i === 0 ? '%' : i === 1 ? 'GiB' : '/s'));
        }
      }
    }
    assert.deepEqual(await charts.evaluateAll(nodes => nodes.map(node => getComputedStyle(node).borderTopColor)), colors);
    for (let i = 0; i < 3; i++) await charts.nth(i).getByRole('combobox').selectOption(['line', 'area', 'bar'][i]);
    assert((await monitor.innerText()).includes('1 MiB/s'));
    for (const width of [720, 960, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert((await page.getByLabel('Network connection', { exact: true }).boundingBox()).height >= 40);
      await page.screenshot({ path: path.join(evidence, `overview-${width}.png`), fullPage: true });
    }
    await page.getByLabel('Network connection', { exact: true }).selectOption('Wi-Fi');
    assert((await monitor.innerText()).includes('8 KiB/s'));
    const before = await count();
    await charts.first().getByRole('combobox').selectOption('bar');
    await page.getByRole('button', { name: 'Accounts', exact: true }).click();
    await page.waitForFunction(n => Number(document.querySelector('.monitor-page')?.dataset.samples) > n, before);
    await page.getByRole('button', { name: 'Overview', exact: true }).click();
    assert(await count() > before);
    assert.equal(await charts.first().getByRole('combobox').inputValue(), 'bar');
    await page.evaluate(() => { window.monitorMode = 'missing'; });
    await page.getByText('Selected connection unavailable.', { exact: false }).waitFor();
    await page.evaluate(() => { window.monitorMode = 'failed'; });
    await page.getByText('Resource readings unavailable. Retrying automatically.', { exact: true }).waitFor();
    assert((await page.locator('.monitor-card').first().innerText()).includes('Unavailable'));
    await page.screenshot({ path: path.join(evidence, 'unavailable.png'), fullPage: true });
    await page.evaluate(() => { window.monitorMode = 'ok'; });
    await page.waitForFunction(() => !document.body.innerText.includes('Resource readings unavailable.'));
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByRole('dialog', { name: 'Sign out of the Hub?' }).getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    const stopped = await page.evaluate(() => window.monitorCalls);
    await page.waitForTimeout(2300);
    assert.equal(await page.evaluate(() => window.monitorCalls), stopped);
    await signIn();
    assert.equal(await count(), 1);
    assert.deepEqual(await charts.getByRole('combobox').evaluateAll(nodes => nodes.map(node => node.value)), ['line', 'area', 'bar']);
    assert.equal(await page.getByLabel('Network connection', { exact: true }).inputValue(), '');
    await page.evaluate(() => { window.monitorMode = 'expired'; });
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log('PASS: monitoring values, graphs, adapter selection/loss, layouts, page history, failure/recovery, sign-out reset and expiry');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
