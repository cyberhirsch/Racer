/**
 * Loads the site in a real browser, starts a race, and checks the car moves.
 *
 * This runs before the site is deployed: a page that 404s on a module or throws
 * on startup looks fine to a file copy but is broken to every visitor.
 *
 * Run: node web/tools/browser-check.mjs [url]
 */
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import process from 'node:process';

const PORT = 8931;
const url = process.argv[2] || `http://127.0.0.1:${PORT}/index.html?quality=low`;

let server;
if (!process.argv[2]) {
  server = spawn('python3', ['-m', 'http.server', String(PORT), '--directory', 'web'], {
    stdio: 'ignore'
  });
  await new Promise(r => setTimeout(r, 1500));
}

// CHROMIUM_PATH lets this run against a system browser instead of Playwright's
// own download, which is handy on machines that already have one.
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--no-sandbox']
});
const page = await browser.newPage({ viewport: { width: 900, height: 420 } });

const problems = [];
page.on('pageerror', e => problems.push(`page error: ${e.message}`));
page.on('response', r => { if (r.status() >= 400) problems.push(`HTTP ${r.status()} ${r.url()}`); });

const fail = async (message) => {
  console.error(`FAIL: ${message}`);
  if (problems.length) console.error(problems.join('\n'));
  await page.screenshot({ path: 'web-check-failure.png' }).catch(() => {});
  await browser.close();
  server?.kill();
  process.exit(1);
};

try {
  await page.goto(url, { waitUntil: 'networkidle', timeout: 60000 });
  await page.waitForTimeout(1500);

  if (!await page.evaluate(() => !!document.getElementById('start'))) {
    await fail('the menu never rendered');
  }

  await page.click('#start');
  await page.waitForFunction(() => window.game?.state === 'racing', null, { timeout: 180000 });

  // Drive it: full throttle down the first straight is enough to prove the
  // whole chain works.
  await page.evaluate(() => {
    window.__drive = setInterval(() => {
      const g = window.game;
      if (g.state !== 'racing') return;
      g.controls.hasOrientation = false;
      g.controls._keys.clear();
      g.controls._keys.add('arrowup');
    }, 16);
  });

  await page.waitForFunction(() => window.game.vehicle.speed > 25, null, { timeout: 180000 })
    .catch(() => null);

  const state = await page.evaluate(() => ({
    speed: Math.round(window.game.vehicle.speed * 3.6),
    fuel: +window.game.vehicle.fuel.toFixed(2),
    level: window.game.cfg.name
  }));
  console.log(`racing "${state.level}" at ${state.speed} km/h, ${state.fuel} kg of fuel left`);

  if (state.speed < 25) await fail(`the car never moved (${state.speed} km/h)`);
  if (problems.length) await fail('the page reported errors');

  console.log('OK: the site loads and the game plays.');
} finally {
  await browser.close();
  server?.kill();
}
