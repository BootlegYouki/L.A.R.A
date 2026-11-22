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
    window.isTauri = true;
    window.monitorMode = 'ok';
    window.monitorCalls = 0;
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      if (command === 'lan_address') return { ip: '192.168.1.20', running: true };
      if (command === 'save_import_file') { window.savedMonitoring = { filename: args.filename, csv: new TextDecoder().decode(new Uint8Array(args.bytes)) }; return true; }
      if (command !== 'hub_metrics') throw 'UNKNOWN_COMMAND';
      if (args.token !== 'monitor-token') throw 'UNAUTHORIZED';
      window.monitorCalls++;
      if (window.monitorMode === 'failed') throw 'MONITORING_UNAVAILABLE';
      if (window.monitorMode === 'expired') throw 'UNAUTHORIZED';
      const sample = window.monitorCalls;
      return { cpu_percent: 20 + sample % 40, hub_cpu_percent: 3, ram_used: 8 * 1024 ** 3,
        ram_total: 16 * 1024 ** 3, hub_ram: 256 * 1024 ** 2, disk_free: 50 * 1024 ** 3,
        disk_total: 500 * 1024 ** 3, uptime_seconds: 3720,
        connected_apps: 12, ai_running: false, ai_cpu_percent: null, ai_ram: null, ai_waiting: null,
        disk_read_per_second: 2 * 1024 ** 2, disk_write_per_second: 512 * 1024,
        storage_use: { videos: 4 * 1024 ** 3, documents: 64 * 1024 ** 2, homework: 32 * 1024 ** 2, database: 16 * 1024 ** 2 },
        workload: { requests_per_second: 2.5, failed_per_second: 0.1, requests_last_minute: 150, failed_last_minute: 6, average_response_ms: 42, slowest_response_ms: 180,
          uploads: 2, downloads: 3, video_streams: 4, documents_waiting: 2, documents_processing: 1, documents_finished: 8, documents_failed: 1 },
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
    assert.equal(await page.locator('.monitor-chart').count(), 5);
    assert((await page.locator('[data-card-id="request-rate"]').innerText()).includes('2.5/s'));
    assert((await page.locator('[data-card-id="response-time"]').innerText()).includes('42 ms'));
    assert((await page.locator('[data-card-id="connected-apps"]').innerText()).includes('12'));
    await page.getByText('No Hub AI process detected', { exact: true }).waitFor();
    await page.getByText('AI queue readings are unavailable.', { exact: true }).waitFor();
    assert((await page.getByRole('region', { name: 'Storage breakdown' }).innerText()).includes('4 GiB'));
    const cpu = page.locator('.monitor-card-cpu');
    const ram = page.locator('.monitor-card-ram');
    assert((await cpu.innerText()).includes('Processor (CPU)'));
    assert((await cpu.innerText()).includes('This computer'));
    assert((await cpu.innerText()).includes('L.A.R.A Hub'));
    assert((await ram.innerText()).includes('256 MiB'));
    assert((await ram.innerText()).includes('8 GiB'));
    assert.equal(await page.locator('.monitor-chart-line').count(), 3);
    assert.equal(await page.locator('.monitor-chart-area .monitor-area-fill').count(), 1);
    assert(await page.locator('.monitor-chart-bar rect').count() >= 2);
    const charts = page.locator('.monitor-chart');
    assert.deepEqual(await charts.first().locator('.monitor-y-ticks span').allTextContents(), ['100%', '75%', '50%', '25%', '0%']);
    assert.deepEqual(await charts.first().locator('.monitor-axis span').allTextContents(), ['10 min ago', '5 min ago', 'Now']);
    assert.equal(await page.getByRole('meter').count(), 3);
    const cardOrder = () => page.locator('[data-card-id]').evaluateAll(nodes => nodes.map(node => node.dataset.cardId));
    const defaultOrder = await cardOrder();
    assert.equal(defaultOrder.length, 21);
    await charts.first().getByRole('combobox').selectOption('bar');
    await page.getByRole('button', { name: 'Arrange cards', exact: true }).click();
    const handle = id => page.locator(`[data-card-id="${id}"] .dashboard-drag-handle`);
    assert.equal(await page.locator('.dashboard-drag-handle').count(), 21);
    await handle('resource-cpu').dragTo(handle('resource-ram'));
    assert((await cardOrder()).indexOf('resource-cpu') > (await cardOrder()).indexOf('resource-ram'));
    await handle('graph-cpu').focus();
    await handle('graph-cpu').press('Home');
    assert.equal((await cardOrder())[0], 'graph-cpu');
    assert(await handle('graph-cpu').evaluate(node => node === document.activeElement));
    await handle('graph-cpu').press('End');
    assert.equal((await cardOrder()).at(-1), 'graph-cpu');
    await handle('graph-cpu').press('ArrowUp');
    assert.equal((await cardOrder()).at(-2), 'graph-cpu');
    await page.locator('[data-card-id="graph-cpu"]').getByRole('button', { name: 'Move Processor (CPU) graph earlier', exact: true }).click();
    assert.equal((await cardOrder()).at(-3), 'graph-cpu');
    assert.equal(await page.locator('[data-card-id="graph-cpu"] .monitor-chart').getByRole('combobox').inputValue(), 'bar');
    assert.deepEqual(JSON.parse(await page.evaluate(() => localStorage.getItem('lara:overview-layout:v1'))), await cardOrder());
    await page.screenshot({ path: path.join(evidence, 'arrange-cards.png'), fullPage: true });
    await handle('graph-cpu').press('Escape');
    assert.equal(await page.locator('.dashboard-drag-handle').count(), 0);
    assert(await page.getByRole('button', { name: 'Arrange cards', exact: true }).evaluate(node => node === document.activeElement));
    await page.evaluate(() => localStorage.setItem('lara:monitoring-limits:v1', JSON.stringify({cpu: 90, ram: 90, storage: 5})));
    await page.getByRole('button', { name: 'Reset card positions', exact: true }).click();
    assert.deepEqual(await cardOrder(), defaultOrder);
    assert.equal(await page.evaluate(() => localStorage.getItem('lara:overview-layout:v1')), null);
    assert.equal(JSON.parse(await page.evaluate(() => localStorage.getItem('lara:monitoring-limits:v1'))).cpu, 90);
    assert.equal(await charts.first().getByRole('combobox').inputValue(), 'bar');
    await charts.first().getByRole('combobox').selectOption('line');

    await page.getByLabel('History', { exact: true }).selectOption('60');
    assert.deepEqual(await charts.first().locator('.monitor-axis span').allTextContents(), ['60 min ago', '30 min ago', 'Now']);
    await page.getByLabel('Group readings', { exact: true }).selectOption('30000');
    await page.getByLabel('Graph values', { exact: true }).selectOption('peak');
    assert((await charts.first().innerText()).includes('Peak of available readings in each 30-second group'));
    await charts.first().getByRole('button', { name: 'Expand Processor (CPU) graph', exact: true }).click();
    assert((await charts.first().getAttribute('class')).includes('monitor-chart-wide'));
    assert((await charts.first().locator('svg').boundingBox()).height >= 220);
    await charts.first().getByRole('button', { name: 'Restore Processor (CPU) graph', exact: true }).click();
    await page.getByLabel('History', { exact: true }).selectOption('10');
    await page.getByLabel('Group readings', { exact: true }).selectOption('10000');
    await page.getByLabel('Graph values', { exact: true }).selectOption('average');
    await page.getByRole('button', { name: 'Pause readings', exact: true }).click();
    await page.waitForTimeout(100);
    const pausedCalls = await page.evaluate(() => window.monitorCalls);
    await page.waitForTimeout(2300);
    assert.equal(await page.evaluate(() => window.monitorCalls), pausedCalls);
    assert.equal(await page.locator('.monitor-warning-missing').count(), 3);
    await monitor.getByRole('button', { name: 'Refresh', exact: true }).click();
    await page.waitForFunction(n => window.monitorCalls === n + 1, pausedCalls);
    await page.waitForTimeout(2300);
    assert.equal(await page.evaluate(() => window.monitorCalls), pausedCalls + 1);
    await page.getByRole('button', { name: 'Export readings CSV', exact: true }).click();
    await page.getByText('Resource readings saved. The CSV contains aggregate metrics only.', { exact: true }).waitFor();
    const exported = await page.evaluate(() => window.savedMonitoring);
    assert.equal(exported.filename, 'LARA-Hub-resource-usage.csv');
    assert(exported.csv.includes('Time (UTC)') && exported.csv.includes('42'));
    assert(!exported.csv.includes('monitor-token'));
    await page.getByRole('button', { name: 'Resume readings', exact: true }).click();
    await page.getByRole('button', { name: 'Warning limits', exact: true }).click();
    const limits = page.getByRole('dialog', { name: 'Warning limits', exact: true });
    await limits.getByLabel('CPU warning (%)', { exact: true }).fill('20');
    await limits.getByLabel('Memory warning (%)', { exact: true }).fill('40');
    await limits.getByLabel('Storage available warning (GiB)', { exact: true }).fill('60');
    await limits.getByRole('button', { name: 'Save limits', exact: true }).click();
    assert.deepEqual(JSON.parse(await page.evaluate(() => localStorage.getItem('lara:monitoring-limits:v1'))), { cpu: 20, ram: 40, storage: 60 });
    await page.waitForFunction(() => document.querySelectorAll('.monitor-warning-attention').length === 3, null, { timeout: 45000 });
    await page.screenshot({ path: path.join(evidence, 'resource-warnings.png'), fullPage: true });
    await page.getByRole('button', { name: 'Warning limits', exact: true }).click();
    await limits.getByRole('button', { name: 'Use defaults', exact: true }).click();
    await limits.getByRole('button', { name: 'Save limits', exact: true }).click();
    assert.equal(await page.locator('.monitor-warning-normal').count(), 3);
    assert.equal(await page.getByRole('region', { name: 'Reading statistics', exact: true }).locator('tbody tr').count(), 11);
    assert((await charts.nth(1).locator('.monitor-y-ticks').innerText()).includes('16 GiB'));
    assert((await charts.nth(2).locator('.monitor-y-ticks').innerText()).includes('1 MiB/s'));
    const colors = await charts.evaluateAll(nodes => nodes.map(node => getComputedStyle(node).borderTopColor));
    for (let i = 0; i < 5; i++) {
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
          assert(tooltip.includes(i === 0 ? '%' : i === 1 ? 'GiB' : i === 4 ? 'ms' : '/s'));
        }
      }
    }
    assert.deepEqual(await charts.evaluateAll(nodes => nodes.map(node => getComputedStyle(node).borderTopColor)), colors);
    for (let i = 0; i < 5; i++) await charts.nth(i).getByRole('combobox').selectOption(['line', 'area', 'bar', 'line', 'line'][i]);
    assert((await monitor.innerText()).includes('1 MiB/s'));
    for (const width of [720, 960, 1280, 1920]) {
      await page.setViewportSize({ width, height: 800 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      const frame = await page.locator('main.hub-main').boundingBox();
      const content = await monitor.boundingBox();
      assert(Math.abs(frame.width - width) < 2, 'Overview should fill the window width');
      assert(Math.abs(content.x + content.width - (width - 24)) < 2, 'Overview should retain only its outer padding');
      if (width === 1920) assert((await charts.first().boundingBox()).width >= 700, 'Wide screens should have roomy graphs');
      assert((await page.getByLabel('Network connection', { exact: true }).boundingBox()).height >= 40);
      const alignment = await page.locator('.monitor-statistics table').evaluate(table => {
        const rows = [...table.querySelectorAll('tbody tr')];
        const headers = [...table.querySelectorAll('thead th')].map(n => n.getBoundingClientRect());
        return { heights: rows.map(n => n.getBoundingClientRect().height), aligned: rows.every(row => [...row.children].every((cell, i) => Math.abs(cell.getBoundingClientRect().right - headers[i].right) < 1)) };
      });
      assert(alignment.aligned, 'Statistics columns must align across every row');
      assert(Math.max(...alignment.heights) - Math.min(...alignment.heights) < 1, 'Statistics rows must have consistent heights');
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
    await page.waitForFunction(() => Number(document.querySelector('.monitor-page')?.dataset.samples) >= 1);
    assert.equal(await count(), 1);
    assert.deepEqual(await charts.getByRole('combobox').evaluateAll(nodes => nodes.map(node => node.value)), ['line', 'area', 'bar', 'line', 'line']);
    assert.equal(await page.getByLabel('Network connection', { exact: true }).inputValue(), '');
    await page.getByRole('button', { name: 'Arrange cards', exact: true }).click();
    await handle('resource-ram').focus();
    await handle('resource-ram').press('Home');
    await page.getByRole('button', { name: 'Done arranging', exact: true }).click();
    await page.reload();
    await signIn();
    assert.equal((await cardOrder())[0], 'resource-ram', 'Saved positions must survive a fresh window session');
    await page.getByRole('button', { name: 'Reset card positions', exact: true }).click();
    assert.deepEqual(await cardOrder(), defaultOrder);
    await page.evaluate(() => { window.monitorMode = 'expired'; });
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
    console.log('PASS: monitoring values, graphs, adapter selection/loss, layouts, page history, failure/recovery, sign-out reset and expiry');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
