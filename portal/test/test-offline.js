// Quota-free client suite: chat/tts mocked at the network layer.
// Exercises: streaming player, watch mode, echo filter, voice barge-in, endpointing.
const puppeteer = require('puppeteer');
const fails = [];
const ok = (n, c, x) => { console.log((c ? 'PASS' : 'FAIL') + ' — ' + n + (x ? '  [' + x + ']' : '')); if (!c) fails.push(n); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SEGS = [
  { text: 'Right then, let me tell you a story about a lighthouse keeper.', rate: 1, pitch: 1, volume: 1, pausePost: 120 },
  { text: 'Every night he climbed four hundred steps with his old cat.', rate: 1, pitch: 1, volume: 1, pausePost: 120 },
  { text: 'And every night the cat complained about the wind.', rate: 1, pitch: 1, volume: 1, pausePost: 0 },
];
const sse = SEGS.map((s) => 'data: ' + JSON.stringify({ segment: s }) + '\n\n').join('')
  + 'data: ' + JSON.stringify({ done: true, reply: SEGS.map((s) => s.text).join(' '), tone: 'warm', conversation_id: 'web:test' }) + '\n\n';

(async () => {
  const browser = await puppeteer.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const streamBodies = [];
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    const u = req.url();
    if (u.endsWith('/api/chat-stream')) {
      streamBodies.push(JSON.parse(req.postData() || '{}'));
      return req.respond({ status: 200, contentType: 'text/event-stream', body: sse });
    }
    if (u.endsWith('/api/tts')) return req.respond({ status: 200, contentType: 'audio/mpeg', body: 'ID3fakebytes' });
    if (u.endsWith('/api/chat')) return req.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ reply: 'Paris, love.', segments: [{ text: 'Paris, love.', rate: 1, pitch: 1, volume: 1 }], tone: 'warm', conversation_id: null }) });
    if (u.endsWith('/api/log')) return req.respond({ status: 200, contentType: 'application/json', body: '{"ok":true,"conversation_id":"web:test"}' });
    req.continue();
  });

  await page.evaluateOnNewDocument(() => {
    window.__audioPlays = [];
    window.Audio = class {
      constructor(src) { this.src = src; this.playbackRate = 1; this.volume = 1; this.duration = 3; this.currentTime = 1.4; }
      play() {
        window.__audioPlays.push(1);
        this.onplaying && setTimeout(() => this.onplaying(), 5);
        setTimeout(() => { this.ontimeupdate && this.ontimeupdate(); }, 100);
        setTimeout(() => this.onended && this.onended(), 1600); // slow: stay speaking a while
        return Promise.resolve();
      }
      pause() {}
    };
    const fakeSynth = { getVoices: () => [{ name: 'V', lang: 'en-GB' }], onvoiceschanged: null, cancel() {}, speak(u) { u.onstart && setTimeout(() => u.onstart(), 5); setTimeout(() => u.onend && u.onend(), 200); } };
    Object.defineProperty(window, 'speechSynthesis', { get: () => fakeSynth });
    window.SpeechSynthesisUtterance = function (t) { this.text = t; };
    Object.defineProperty(window, 'SpeechRecognition', { get: () => undefined });
    window.webkitSpeechRecognition = function () {
      window.__rec = this; window.__recCount = (window.__recCount || 0) + 1;
      this.abort = () => {}; this.start = () => {};
      this.stop = () => this.onend && this.onend();
    };
    window.__feedInterim = (t) => { const r = window.__rec; r && r.onresult && r.onresult({ results: [Object.assign([{ transcript: t }], { isFinal: false })] }); };
    window.__feedFinal = (t) => { const r = window.__rec; if (!r) return; r.onresult && r.onresult({ results: [Object.assign([{ transcript: t }], { isFinal: true })] }); r.onend && r.onend(); };
    navigator.mediaDevices.getUserMedia = () => Promise.reject(new Error('no mic'));
  });

  await page.goto('https://kate-portal.luke1-temp16.workers.dev/#t=dummy', { waitUntil: 'networkidle2', timeout: 60000 });
  await sleep(800);

  // enable conversation mode so she watches while speaking
  await page.click('#convBtn');

  // typed turn → she speaks (slow stub audio keeps her talking)
  await page.type('#text', 'tell me the story');
  await page.click('#send');
  await page.waitForFunction(() => window.__penny.currentSeg().length > 0, { timeout: 20000 });
  await sleep(300);
  const watchLog1 = await page.evaluate(() => (window.__specLog || []).filter((l) => /session start watch/.test(l)).length);
  ok('watch session starts while she speaks', watchLog1 >= 1, watchLog1 + ' watch starts');

  // echo: feed her exact current words → must NOT interrupt
  const herNow = await page.evaluate(() => window.__penny.currentSeg());
  await page.evaluate((w) => window.__feedInterim(w), herNow.split(/\s+/).slice(0, 6).join(' '));
  await sleep(300);
  const label1 = await page.evaluate(() => document.querySelector('#talk').textContent);
  const log1 = await page.evaluate(() => (window.__specLog || []).slice(-3));
  ok('echo filter: her own words ignored', !label1.includes('LIVE'), label1 + ' ' + JSON.stringify(log1));
  const stillSpeaking = await page.evaluate(() => window.__penny.currentSeg().length > 0);
  ok('she keeps speaking through the echo', stillSpeaking);

  // real interruption → she stops, capture goes live
  await sleep(200);
  await page.evaluate(() => window.__feedInterim('hold on stop please what time is it'));
  await sleep(400);
  const label2 = await page.evaluate(() => document.querySelector('#talk').textContent);
  ok('real speech interrupts her (voice barge-in)', label2.includes('LIVE'), label2);

  // finish the interruption → next stream call carries cut_context
  await page.evaluate(() => window.__feedFinal('hold on stop please what time is it'));
  await page.waitForFunction(() => document.querySelectorAll('.msg.penny').length >= 2, { timeout: 20000 });
  const last = streamBodies[streamBodies.length - 1];
  ok('cut_context sent after voice barge-in', last && typeof last.cut_context === 'string' && last.cut_context.length > 0,
    'cut="' + String(last && last.cut_context).slice(0, 40) + '"');

  ok('no JS errors', errors.length === 0, errors[0] || '');
  await browser.close();
  console.log(fails.length ? 'RESULT: FAILURES: ' + fails.join(' | ') : 'RESULT: ALL PASS');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('TEST CRASH: ' + e.message); process.exit(2); });
