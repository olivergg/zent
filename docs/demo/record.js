// Drives the zent dashboard in headless Chrome through the demo scenario, recording it as PNG
// frames: <out-dir>/NNNNN.png, listed with how long each stays on screen in <out-dir>/frames.txt
// ("<file> <ms>" per line). Chrome's screencast, not Playwright's video: lossless frames, sent
// only when the page changes - its VP8 video blurred the cards' small type.
// Usage: node record.js <dashboard-url> <out-dir>   (see record.sh, which does it all)
const fs = require('fs');
const { chromium } = require('playwright-core');
const [url, outDir] = process.argv.slice(2);
const W = 1280, H = 900, TICK = 100;  // frames resampled on a 100ms grid: 10 fps at most
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: W, height: H } });
  // headless frames have no pointer: draw one
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
  // every frame Chrome paints, with when (s); each acked, or Chrome stops sending
  const shots = [], cdp = await ctx.newCDPSession(p);
  cdp.on('Page.screencastFrame', ({ data, metadata, sessionId }) => {
    cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
    const png = Buffer.from(data, 'base64');
    // the first ones can come before the page has its size: img2webp wants one size throughout
    if (png.readUInt32BE(16) !== W || png.readUInt32BE(20) !== H) return;
    const file = `${String(shots.length).padStart(5, '0')}.png`;
    fs.writeFileSync(`${outDir}/${file}`, png);
    shots.push({ file, t: metadata.timestamp * 1000 });
  });
  fs.mkdirSync(outDir, { recursive: true });
  await cdp.send('Page.startScreencast', { format: 'png', maxWidth: W, maxHeight: H });
  const pause = ms => p.waitForTimeout(ms);
  const point = async (sel, ms = 900) => {
    const bb = await p.locator(sel).first().boundingBox();
    await p.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2, { steps: 12 }); await pause(ms);
  };
  const click = async (sel, ms = 900) => { await point(sel, 350); await p.locator(sel).first().click(); await pause(ms); };
  // every card of `want` in one of its states (data-status, the UI's STATE_CLASS)
  const until = (want, timeout = 60000) => p.waitForFunction(want => Object.entries(want).every(([n, states]) =>
    states.split(' ').includes(document.querySelector(`.node[data-name="${n}"]`)?.dataset.status)), want, { timeout });
  const card = n => `.node[data-name="${n}"]`;
  const preview = async name => { await click('#preset', 700); await click(`[data-preset="${name}"]`, 2600); };

  await p.goto(url);
  await p.waitForSelector('#preset'); await pause(1200);

  // 1. preview a preset - its verdict, what's worth a look - then start it: dependencies first
  await preview('shop');
  await click('#run-toggle', 0);
  await until({ postgres: 'up', broker: 'up', 'catalog-api': 'up', 'orders-api': 'up', storefront: 'up' });
  await pause(4000);                                                 // the meters fill in: orders-api runs hot
  await point(card('orders-api'), 1800);

  // 2. a component's detail, then its logs
  await click(card('orders-api'), 1800);
  await click('#detail .open-logs', 2200);
  await click('#log-pills .pill[aria-pressed="true"]', 1200);       // again: every component
  await click('#log-term', 200); await p.keyboard.type('/order|cart/', { delay: 60 }); await pause(1800);
  await p.fill('#log-term', ''); await p.dispatchEvent('#log-term', 'input'); await pause(300);

  // 3. judge the running stack: the preset's checks, one failing
  await click('#tabs [data-tab="checks"]', 1000);
  await click('#checks-run-all', 0);
  await p.waitForSelector('#checks-run-all:not([disabled])', { timeout: 90000 }); await pause(1500);
  await click('#checks-tree >> text=Orders listed', 2800);

  // 4. the repos the preset runs: catalog-api is behind main - rebase it, from here
  await click('#tabs [data-tab="branches"]', 2200);
  await click('#branches-grid button[data-verb="rebase"]', 1400);  // asks first, in a bubble
  await click('#confirm-go', 0);
  await p.waitForSelector('.toast', { timeout: 30000 }); await pause(3000);

  // 5. switch preset: the preview says what it stops; the mailer's branch fails to build, the notifier is skipped
  await click('#tabs [data-tab="stack"]', 800);
  await preview('checkout');
  await click('#run-toggle', 0);
  await until({ payments: 'external', mailer: 'failed', notifier: 'skipped', 'orders-api': 'up' });
  await pause(1500);
  await click(card('mailer'), 3500);

  await cdp.send('Page.stopScreencast');
  const end = Date.now(); await ctx.close(); await b.close();
  if (errs.length) { console.error('page errors:', errs); process.exit(1); }

  // on a TICK grid, the frame on screen at each tick; a frame held over several ticks, one entry
  const timeline = [];
  for (let t = shots[0].t, i = 0; t < end; t += TICK) {
    while (i + 1 < shots.length && shots[i + 1].t <= t) i++;
    const last = timeline[timeline.length - 1];
    if (last?.file === shots[i].file) last.ms += TICK; else timeline.push({ file: shots[i].file, ms: TICK });
  }
  fs.writeFileSync(`${outDir}/frames.txt`, timeline.map(f => `${f.file} ${f.ms}\n`).join(''));
  console.log(`${timeline.length} frames, ${((end - shots[0].t) / 1000).toFixed(1)}s`);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
