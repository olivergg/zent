// Drives the zent dashboard in headless Chrome through the demo scenario and records it as a webm.
// Usage: node record.js <dashboard-url> <out-dir>   (see record.sh, which does it all)
const { chromium } = require('playwright-core');
const [url, outDir] = process.argv.slice(2);
const W = 1280, H = 760;
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: W, height: H },
                                   recordVideo: { dir: outDir, size: { width: W, height: H } } });
  // headless video has no pointer: draw one
  await ctx.addInitScript(() => addEventListener('DOMContentLoaded', () => {
    const c = document.createElement('div');
    c.style.cssText = 'position:fixed;z-index:99999;width:14px;height:14px;border-radius:50%;'
      + 'background:rgba(196,99,63,.55);border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35);'
      + 'pointer-events:none;left:-40px;top:-40px;transform:translate(-50%,-50%);transition:left .35s,top .35s';
    document.body.appendChild(c);
    addEventListener('mousemove', e => { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }, true);
  }));

  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  const pause = ms => p.waitForTimeout(ms);
  const click = async (sel, ms = 900) => {
    const loc = p.locator(sel).first(), bb = await loc.boundingBox();
    await p.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2, { steps: 12 }); await pause(350);
    await loc.click(); await pause(ms);
  };
  // every card of `names` in one of `states` (data-status, the UI's STATE_CLASS)
  const until = (want, timeout = 60000) => p.waitForFunction(want => Object.entries(want).every(([n, states]) =>
    states.split(' ').includes(document.querySelector(`.node[data-name="${n}"]`)?.dataset.status)), want, { timeout });
  const card = n => `.node[data-name="${n}"]`;
  const play = n => `.preset-row:has([data-preset="${n}"]) .act.play`;

  await p.goto(url);
  await p.waitForSelector('[data-preset="shop"]'); await pause(1200);

  // 1. preview a preset, then start it: the graph fills in, dependencies first
  await click('[data-preset="shop"]', 2200);
  await click('#preview-banner .play', 0);
  await until({ postgres: 'up', seed: 'done', broker: 'up', 'catalog-api': 'up', 'orders-api': 'up', storefront: 'up' });
  await pause(1800);

  // 2. a component's detail, then its logs
  await click(card('orders-api'), 1800);
  await click('#detail .open-logs', 2500);
  await click('.pill[aria-pressed="true"]', 1500);                   // again: every component
  await click('#log-term', 200); await p.keyboard.type('/order|cart/', { delay: 60 }); await pause(1500);
  await p.fill('#log-term', ''); await p.dispatchEvent('#log-term', 'input'); await pause(300);
  await click('[data-level="warn"]', 1500);
  await click('[data-level=""]', 300);
  await click('#tabs [data-tab="graph"]', 600);
  await p.keyboard.press('Escape'); await pause(600);

  // 3. preview a switch: what it stops, starts, keeps warm - then make it
  await click('[data-preset="checkout"]', 3000);
  await click('#preview-banner .play', 0);
  await until({ payments: 'external', mailer: 'failed', 'orders-api': 'up' });
  await pause(1500);
  await click(card('mailer') + ' .name', 3000);                      // its branch's build broke

  // 4. switch again: broker isn't needed but :keep-warm, postgres :permanent
  await click(play('browse'), 0);
  await until({ 'catalog-api': 'up', storefront: 'up', broker: 'idle' });
  await pause(1500);
  await click('#show-stopped', 2500);
  await click(card('broker') + ' .act.stop', 0);
  await until({ broker: 'stopped' }, 20000); await pause(2000);

  const video = p.video(); await ctx.close(); await b.close();
  if (errs.length) { console.error('page errors:', errs); process.exit(1); }
  console.log(await video.path());
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
