// Voice-modulation e2e: real Chrome + live worker. Speech APIs stubbed with
// prosody capture; network intercepted to observe bodies and inject latency.
const puppeteer = require('puppeteer');

const BASE = 'https://kate-portal.luke1-temp16.workers.dev';
const fails = [];
const ok = (name, cond, extra) => {
  console.log((cond ? 'PASS' : 'FAIL') + ' — ' + name + (extra ? '  [' + extra + ']' : ''));
  if (!cond) fails.push(name);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 915 });

  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));

  // network observer + injectable latency
  const chatBodies = [], logCalls = [];
  let delayChat = 0;
  await page.setRequestInterception(true);
  let draftResolved = null, draftPromise = null;
  page.on('response', async (res) => {
    try {
      const req = res.request();
      if (req.url().endsWith('/api/chat') && req.method() === 'POST') {
        const b = JSON.parse(req.postData() || '{}');
        if (b.draft && draftResolved) draftResolved();
      }
    } catch (_) {}
  });
  page.on('request', (req) => {
    const u = req.url();
    if (u.endsWith('/api/chat') && req.method() === 'POST') {
      chatBodies.push(JSON.parse(req.postData() || '{}'));
      if (delayChat) return void setTimeout(() => req.continue(), delayChat);
    }
    if (u.endsWith('/api/log') && req.method() === 'POST') logCalls.push(JSON.parse(req.postData() || '{}'));
    req.continue();
  });

  // speech stubs with prosody capture + controllable recognition
  await page.evaluateOnNewDocument(() => {
    window.__spoken = [];
    const fakeSynth = {
      getVoices: () => [{ name: 'Test UK Female', lang: 'en-GB' }],
      onvoiceschanged: null,
      cancel() { if (window.__current) { const c = window.__current; window.__current = null; clearTimeout(c.t); } },
      speak(u) {
        window.__spoken.push({ text: u.text, rate: u.rate, pitch: u.pitch, volume: u.volume });
        u.onstart && setTimeout(() => u.onstart(), 5);
        const dur = Math.min(700, 60 + u.text.length * 6);
        const self = { t: setTimeout(() => { window.__current = null; u.onend && u.onend(); }, dur) };
        window.__current = self;
      },
    };
    Object.defineProperty(window, 'speechSynthesis', { get: () => fakeSynth });
    window.SpeechSynthesisUtterance = function (t) { this.text = t; };
    Object.defineProperty(window, 'SpeechRecognition', { get: () => undefined });
    // recognition driven manually from the test
    window.webkitSpeechRecognition = function () {
      window.__rec = this;
      this.start = () => {};
      this.stop = () => this.onend && this.onend();
    };
    window.__feedInterim = (text) => {
      const r = window.__rec;
      r && r.onresult && r.onresult({ results: [Object.assign([{ transcript: text }], { isFinal: false })] });
    };
    window.__feedFinal = (text) => {
      const r = window.__rec;
      if (!r) return;
      r.onresult && r.onresult({ results: [Object.assign([{ transcript: text }], { isFinal: true })] });
      r.onend && r.onend();
    };
    // no real mic in headless
    navigator.mediaDevices.getUserMedia = () => Promise.reject(new Error('no mic'));
  });

  const TOKEN = process.env.KATE_TOKEN;
  await page.goto(BASE + '/#t=' + TOKEN, { waitUntil: 'networkidle2', timeout: 60000 });
  await sleep(1000);

  const spoken = () => page.evaluate(() => window.__spoken);
  const clearSpoken = () => page.evaluate(() => { window.__spoken = []; });
  const pennyCount = () => page.evaluate(() => document.querySelectorAll('.msg.penny').length);
  const waitPenny = async (n, t = 40000) =>
    page.waitForFunction((n2) => document.querySelectorAll('.msg.penny').length >= n2, { timeout: t }, n);

  // 1. typed chat → segments spoken with prosody params
  await page.type('#text', 'Give me two sentences about the ocean.');
  await page.click('#send');
  await waitPenny(1);
  await sleep(2500); // let segments play out
  let sp = await spoken();
  const bridgesTxt = ['Hmm', 'Okay —', 'Let me', 'Mm,', 'Right,', 'Ooh'];
  const segsOnly = sp.filter((u) => !(u.text.length < 20 && bridgesTxt.some((b) => u.text.startsWith(b))));
  ok('reply spoken as prosody segments', segsOnly.length >= 1 && segsOnly.every((u) => typeof u.rate === 'number' && typeof u.pitch === 'number'),
    segsOnly.length + ' segs, rate=' + (segsOnly[0] && segsOnly[0].rate));
  const reply1 = await page.evaluate(() => [...document.querySelectorAll('.msg.penny')].pop().textContent);
  const spokenJoined = sp.map((u) => u.text).join(' ').replace(/\s+/g, ' ');
  ok('segments cover the whole reply', spokenJoined.length >= reply1.length * 0.8);
  ok('affect fields sent to server', chatBodies.length >= 1 && 'mood' in chatBodies[0] && 'hour' in chatBodies[0]);

  // 2. filler bridge when the server is slow (>600ms)
  await clearSpoken();
  delayChat = 1500;
  await page.type('#text', 'Quick one: is water wet?');
  await page.click('#send');
  await waitPenny(2);
  delayChat = 0;
  sp = await spoken();
  const bridges = ['Hmm', 'Okay', 'Let me', 'Mm', 'Right', 'Ooh'];
  ok('filler bridge spoken during slow think', sp.length >= 2 && bridges.some((b) => sp[0].text.startsWith(b)), 'first="' + (sp[0] && sp[0].text) + '"');

  // 3. speculation: drafts fire on partials, final matches → cached reply + /api/log
  await sleep(2500);
  await clearSpoken();
  const chatCountBefore = chatBodies.length;
  const logCountBefore = logCalls.length;
  await page.click('#talk');
  await sleep(300);
  await page.evaluate(() => window.__feedInterim('what is the capital'));
  await sleep(400);
  draftPromise = new Promise((r) => { draftResolved = r; });
  await page.evaluate(() => window.__feedInterim('what is the capital of france please'));
  // wait for the draft request to actually round-trip before ending the turn
  await Promise.race([draftPromise, sleep(20000)]);
  await sleep(300);
  await page.evaluate(() => window.__feedFinal('what is the capital of france please'));
  await waitPenny(3);
  await sleep(500);
  const draftsSent = chatBodies.slice(chatCountBefore).filter((b) => b.draft).length;
  const nonDraftAfter = chatBodies.slice(chatCountBefore).filter((b) => !b.draft).length;
  const logged = logCalls.length > logCountBefore;
  ok('speculative draft fired on partial transcript', draftsSent >= 1, draftsSent + ' drafts');
  ok('speculation hit: no fresh chat call, turn committed via /api/log', nonDraftAfter === 0 && logged);
  const statsTxt = await page.evaluate(() => document.querySelector('#stats').textContent);
  ok('stats show TTFA + SPEC', /TTFA \d+ms/.test(statsTxt) && /SPEC \d+%/.test(statsTxt), statsTxt);

  // 4. barge-in: interrupt her mid-speech → next request carries cut_context
  await sleep(1000);
  await page.type('#text', 'Tell me a long story about a lighthouse keeper and his cat.');
  await page.click('#send');
  await waitPenny(4);
  await sleep(400); // she's mid-segment (stub speaks ~real-time-ish)
  const wasSpeaking = await page.evaluate(() => window.__current != null);
  await page.click('#talk'); // barge in
  await sleep(300);
  await page.evaluate(() => window.__feedFinal('actually what is two plus two'));
  await waitPenny(5);
  const lastNonDraft = [...chatBodies].reverse().find((b) => !b.draft);
  ok('barge-in captured cut position and sent cut_context', wasSpeaking && lastNonDraft && typeof lastNonDraft.cut_context === 'string' && lastNonDraft.cut_context.length > 0,
    'cut="' + String(lastNonDraft && lastNonDraft.cut_context).slice(0, 40) + '"');

  ok('no JS errors across all flows', errors.length === 0, errors[0] || '');
  await page.screenshot({ path: 'voice-final.png' });
  await browser.close();
  console.log(fails.length ? 'RESULT: FAILURES: ' + fails.join(' | ') : 'RESULT: ALL PASS');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('TEST CRASH: ' + e.message); process.exit(2); });
