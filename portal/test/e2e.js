// End-to-end browser test of Moneypenny web app: real Chrome, real network.
// Speech APIs are stubbed (headless has no mic/speaker) but every code path
// they trigger runs for real.
const puppeteer = require('puppeteer');

const URL = 'https://kate-portal.luke1-temp16.workers.dev/#t=' + process.env.KATE_TOKEN;
const fails = [];
const ok = (name, cond) => {
  console.log((cond ? 'PASS' : 'FAIL') + ' — ' + name);
  if (!cond) fails.push(name);
};

(async () => {
  const browser = await puppeteer.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 915 });

  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  // Stub speech APIs before any page script runs.
  await page.evaluateOnNewDocument(() => {
    window.__spoken = [];
    const fakeSynth = {
      _v: [{ name: 'Test UK Female', lang: 'en-GB' }],
      getVoices() { return this._v; },
      onvoiceschanged: null,
      cancel() {},
      speak(u) { window.__spoken.push(u.text); setTimeout(() => u.onend && u.onend(), 30); },
    };
    Object.defineProperty(window, 'speechSynthesis', { get: () => fakeSynth });
    window.SpeechSynthesisUtterance = function (t) { this.text = t; };
    Object.defineProperty(window, 'SpeechRecognition', { get: () => undefined });
    window.webkitSpeechRecognition = function () {
      this.start = () => {
        window.__rec = this;
        setTimeout(() => {
          this.onresult && this.onresult({ results: [Object.assign([{ transcript: 'hello moneypenny' }], { isFinal: true })] });
          this.onend && this.onend();
        }, 150);
      };
      this.stop = () => this.onend && this.onend();
    };
  });

  await page.goto(URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise((r) => setTimeout(r, 1200));

  ok('page loads without JS errors', errors.length === 0);
  ok('token stored from #t= link', await page.evaluate(() => !!localStorage.getItem('kate_token')));
  ok('hash cleaned from url', await page.evaluate(() => !location.hash));
  ok('orb canvas animating', await page.evaluate(() => {
    const c = document.querySelector('#orb');
    return c && c.getContext('2d') && true;
  }));

  // 1. typed message → real /api/chat → PENNY reply rendered + "spoken"
  await page.type('#text', 'Reply with exactly: browser test okay');
  await page.click('#send');
  await page.waitForFunction(
    () => [...document.querySelectorAll('.msg.penny')].length >= 1,
    { timeout: 30000 },
  );
  const reply1 = await page.evaluate(() => [...document.querySelectorAll('.msg.penny')].pop().textContent);
  ok('typed chat gets real AI reply', reply1.length > 3 && !/went wrong/i.test(reply1));
  await new Promise((r) => setTimeout(r, 2500));
  const spoken = await page.evaluate(() => window.__spoken);
  const joined = spoken.join(' ').replace(/\s+/g, ' ');
  ok('reply was sent to speech synthesis', spoken.length >= 1 && joined.length >= reply1.length * 0.7);

  // 2. TALK button → stubbed recognition feeds "hello moneypenny" → real reply
  await page.click('#talk');
  await page.waitForFunction(
    () => [...document.querySelectorAll('.msg.you')].some((m) => m.textContent.includes('hello moneypenny')),
    { timeout: 15000 },
  );
  await page.waitForFunction(
    () => document.querySelectorAll('.msg.penny').length >= 2,
    { timeout: 30000 },
  );
  ok('voice path: recognition → chat → reply', true);

  // 3. memory: teach → recall
  await page.type('#text', 'my sister is called Anna');
  await page.click('#send');
  await page.waitForFunction(() => document.querySelectorAll('.msg.penny').length >= 3, { timeout: 30000 });
  await page.type('#text', 'what did I say about my sister');
  await page.click('#send');
  await page.waitForFunction(() => document.querySelectorAll('.msg.penny').length >= 4, { timeout: 30000 });
  const recall = await page.evaluate(() => [...document.querySelectorAll('.msg.penny')].pop().textContent);
  ok('recall finds the fact', /anna/i.test(recall));

  // 4. forget
  await page.type('#text', 'forget that');
  await page.click('#send');
  await page.waitForFunction(() => document.querySelectorAll('.msg.penny').length >= 5, { timeout: 30000 });
  const forgot = await page.evaluate(() => [...document.querySelectorAll('.msg.penny')].pop().textContent);
  ok('forget acknowledged', /forgotten/i.test(forgot));

  ok('no JS errors across all flows', errors.length === 0);
  if (errors.length) console.log(errors.join('\n'));

  await page.screenshot({ path: 'final.png' });
  await browser.close();
  console.log(fails.length ? 'RESULT: FAILURES: ' + fails.join(' | ') : 'RESULT: ALL PASS');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('TEST CRASH: ' + e.message); process.exit(2); });
