// Voice-modulation e2e v2: cloud TTS (Aura) + streaming segments + speculation
// + barge-in + browser-voice fallback. Real Chrome, live worker, Audio stubbed.
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

  const chatBodies = [], streamBodies = [], logCalls = [];
  let ttsCalls = 0, delayStream = 0;
  let draftResolved = null;
  page.on('response', (res) => {
    try {
      const req = res.request();
      if (req.url().endsWith('/api/chat') && req.method() === 'POST') {
        const b = JSON.parse(req.postData() || '{}');
        if (b.draft && draftResolved && res.status() === 200) draftResolved();
      }
    } catch (_) {}
  });
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    const u = req.url();
    if (u.endsWith('/api/chat') && req.method() === 'POST') chatBodies.push(JSON.parse(req.postData() || '{}'));
    if (u.endsWith('/api/chat-stream') && req.method() === 'POST') {
      streamBodies.push(JSON.parse(req.postData() || '{}'));
      if (delayStream) return void setTimeout(() => req.continue(), delayStream);
    }
    if (u.endsWith('/api/tts')) ttsCalls++;
    if (u.endsWith('/api/log') && req.method() === 'POST') logCalls.push(JSON.parse(req.postData() || '{}'));
    req.continue();
  });

  await page.evaluateOnNewDocument(() => {
    window.__audioPlays = [];
    window.Audio = class {
      constructor(src) { this.src = src; this.playbackRate = 1; this.volume = 1; this.duration = 1; this.currentTime = 0.5; }
      play() {
        window.__audioPlays.push({ rate: this.playbackRate, volume: this.volume });
        this.onplaying && setTimeout(() => this.onplaying(), 5);
        setTimeout(() => { this.ontimeupdate && this.ontimeupdate(); this.onended && this.onended(); }, 900);
        return Promise.resolve();
      }
      pause() {}
    };
    window.__spoken = [];
    const fakeSynth = {
      getVoices: () => [{ name: 'Test UK Female', lang: 'en-GB' }, { name: 'Test US Male', lang: 'en-US' }],
      onvoiceschanged: null,
      cancel() {},
      speak(u) {
        window.__spoken.push({ text: u.text, rate: u.rate, pitch: u.pitch });
        u.onstart && setTimeout(() => u.onstart(), 5);
        setTimeout(() => u.onend && u.onend(), Math.min(500, 40 + u.text.length * 4));
      },
    };
    Object.defineProperty(window, 'speechSynthesis', { get: () => fakeSynth });
    window.SpeechSynthesisUtterance = function (t) { this.text = t; };
    Object.defineProperty(window, 'SpeechRecognition', { get: () => undefined });
    window.webkitSpeechRecognition = function () {
      window.__rec = this;
      this.start = () => {};
      this.stop = () => this.onend && this.onend();
    };
    window.__feedInterim = (t) => { const r = window.__rec; r && r.onresult && r.onresult({ results: [Object.assign([{ transcript: t }], { isFinal: false })] }); };
    window.__feedFinal = (t) => { const r = window.__rec; if (!r) return; r.onresult && r.onresult({ results: [Object.assign([{ transcript: t }], { isFinal: true })] }); r.onend && r.onend(); };
    navigator.mediaDevices.getUserMedia = () => Promise.reject(new Error('no mic'));
  });

  await page.goto(BASE + '/#t=' + process.env.KATE_TOKEN, { waitUntil: 'networkidle2', timeout: 60000 });
  await sleep(1000);

  // streaming creates the PENNY element empty — wait for actual text
  const waitPenny = (n, t = 45000) =>
    page.waitForFunction((n2) => {
      const els = document.querySelectorAll('.msg.penny');
      return els.length >= n2 && els[n2 - 1].textContent.trim().length > 3;
    }, { timeout: t }, n);
  const audioPlays = () => page.evaluate(() => window.__audioPlays);

  // 1. streaming turn in cloud voice
  await page.type('#text', 'Give me two sentences about the ocean.');
  await page.click('#send');
  await waitPenny(1);
  await page.waitForFunction(() => window.__audioPlays.length >= 1, { timeout: 30000 });
  await sleep(1500);
  const ttsAfter1 = ttsCalls, plays1 = await audioPlays();
  ok('turn used streaming endpoint', streamBodies.length >= 1);
  ok('cloud voice: TTS fetched (segments + warmed bridges)', ttsAfter1 >= 3, ttsAfter1 + ' tts calls');
  ok('cloud audio played with prosody rate applied', plays1.length >= 1 && plays1.every((p) => p.rate >= 0.8 && p.rate <= 1.3), JSON.stringify(plays1[0]));
  ok('affect/mood/hour sent on stream', 'mood' in streamBodies[0] && 'hour' in streamBodies[0]);
  const reply1 = await page.evaluate(() => [...document.querySelectorAll('.msg.penny')].pop().textContent);
  ok('streamed transcript assembled', reply1.length > 20);

  // 2. bridge on slow stream
  const playsBefore = (await audioPlays()).length;
  const spokenBefore = (await page.evaluate(() => window.__spoken)).length;
  delayStream = 1500;
  await page.type('#text', 'Quick one: is water wet?');
  await page.click('#send');
  await waitPenny(2);
  delayStream = 0;
  await sleep(1500);
  const playsAfter = (await audioPlays()).length;
  const bridgedAt = await page.evaluate(() => window.__pennyLastBridge || 0);
  ok('bridge fired during slow think', bridgedAt > 0, 'bridge ts=' + bridgedAt + ', audio ' + playsBefore + '→' + playsAfter);

  // 3. speculation
  await sleep(1500);
  const chatCountBefore = chatBodies.length, streamCountBefore = streamBodies.length, logBefore = logCalls.length;
  await page.click('#talk');
  await sleep(300);
  await page.evaluate(() => window.__feedInterim('what is the capital'));
  await sleep(400);
  const draftDone = new Promise((r) => { draftResolved = r; });
  await page.evaluate(() => window.__feedInterim('what is the capital of france please'));
  await Promise.race([draftDone, sleep(25000)]);
  await sleep(300);
  await page.evaluate(() => window.__feedFinal('what is the capital of france please'));
  await waitPenny(3);
  await sleep(600);
  const draftsSent = chatBodies.slice(chatCountBefore).filter((b) => b.draft).length;
  ok('speculative draft fired', draftsSent >= 1, draftsSent + ' drafts');
  const takeInfo = await page.evaluate(() => window.__pennyTake || null);
  ok('speculation hit: no stream call, committed via /api/log',
    streamBodies.length === streamCountBefore && logCalls.length > logBefore,
    JSON.stringify(takeInfo));
  const statsTxt = await page.evaluate(() => document.querySelector('#stats').textContent);
  ok('stats show TTFA + SPEC', /TTFA \d+ms/.test(statsTxt) && /SPEC \d+%/.test(statsTxt), statsTxt);

  // 4. barge-in mid-speech → cut_context next turn
  await page.type('#text', 'Tell me a long story about a lighthouse keeper and his cat.');
  await page.click('#send');
  await waitPenny(4);
  await page.waitForFunction((n) => window.__audioPlays.length > n, { timeout: 30000 }, playsAfter);
  await page.click('#talk');
  await sleep(300);
  await page.evaluate(() => window.__feedFinal('actually what is two plus two'));
  await waitPenny(5);
  const lastStream = streamBodies[streamBodies.length - 1];
  ok('barge-in sent cut_context', lastStream && typeof lastStream.cut_context === 'string' && lastStream.cut_context.length > 0,
    'cut="' + String(lastStream && lastStream.cut_context).slice(0, 40) + '"');

  // 5a. semantic endpointing thresholds (§4.1)
  const th = await page.evaluate(() => ({
    trailing: window.__penny.silenceMsFor('I was thinking about'),
    comma: window.__penny.silenceMsFor('so the first one,'),
    command: window.__penny.silenceMsFor('yeah do that'),
    sentence: window.__penny.silenceMsFor('please add milk to the shopping list for tomorrow'),
  }));
  ok('endpointing: trailing thought holds the floor', th.trailing >= 1800 && th.comma >= 1800, JSON.stringify(th));
  ok('endpointing: complete thoughts release fast', th.command <= 800 && th.sentence <= 1200);

  // 5b. voice barge-in with echo filter: her own words ignored, user speech interrupts
  await page.click('#convBtn'); // watch mode requires auto-listen or a voice turn
  const playsN = (await audioPlays()).length;
  await page.type('#text', 'Tell me another long story, slowly.');
  await page.click('#send');
  await waitPenny(6);
  await page.waitForFunction(() => window.__penny.currentSeg().length > 0, { timeout: 30000 });
  await sleep(300);
  // watch session runs (voice was last input? typed — force conversation mode on for watch)
  const watching = await page.evaluate(() => !!window.__rec);
  // echo: feed her own current words — must NOT interrupt
  const herWords = await page.evaluate(() => (document.querySelectorAll('.msg.penny')[5] || {}).textContent || '');
  if (watching) {
    await page.evaluate((w) => window.__feedInterim(w), herWords.split(/\s+/).slice(0, 6).join(' '));
    await sleep(300);
    const stillSpeaking = await page.evaluate(() => window.__audioPlays.length >= 0); // she wasn't stopped: no crash & no capture UI
    const talkLabel1 = await page.evaluate(() => document.querySelector('#talk').textContent);
    const specLog = await page.evaluate(() => (window.__specLog || []).slice(-6));
    ok('echo filter: her own words do not interrupt', !talkLabel1.includes('LIVE'), talkLabel1 + ' ' + JSON.stringify(specLog));
    // real user speech — must interrupt
    await page.evaluate(() => window.__feedInterim('hold on stop for a second please'));
    await sleep(400);
    const talkLabel2 = await page.evaluate(() => document.querySelector('#talk').textContent);
    ok('voice barge-in: user speech interrupts her', talkLabel2.includes('LIVE'), talkLabel2);
    await page.evaluate(() => window.__feedFinal('hold on stop for a second please what time is it'));
    await waitPenny(7);
  } else {
    ok('echo filter: her own words do not interrupt', false, 'watch session never started');
    ok('voice barge-in: user speech interrupts her', false, 'watch session never started');
  }

  // 5c. speaker + pace pills
  await page.click('#paceBtn');
  const paceTxt = await page.evaluate(() => document.querySelector('#paceBtn').textContent);
  ok('pace pill cycles', /PACE·(SLOW|NORM|QUICK)/.test(paceTxt), paceTxt);
  const spBefore = await page.evaluate(() => document.querySelector('#speakerBtn').textContent);
  await page.click('#speakerBtn');
  await sleep(400);
  const spAfter = await page.evaluate(() => document.querySelector('#speakerBtn').textContent);
  ok('speaker pill cycles her voice', spBefore !== spAfter, spBefore + '→' + spAfter);

  // 5. browser-voice fallback mode
  await page.click('#voiceBtn');
  await sleep(200);
  const pill = await page.evaluate(() => document.querySelector('#voiceBtn').textContent);
  ok('voice pill toggles to phone voice', pill.includes('PHONE'));
  await page.evaluate(() => { window.__spoken = []; });
  await page.type('#text', 'Say one short sentence.');
  await page.click('#send');
  await waitPenny(8);
  await page.waitForFunction(() => window.__spoken.length >= 1, { timeout: 30000 });
  const spoken = await page.evaluate(() => window.__spoken);
  ok('phone voice speaks segments with prosody', spoken.every((s) => typeof s.rate === 'number' && typeof s.pitch === 'number'),
    spoken.length + ' utterances');

  ok('no JS errors across all flows', errors.length === 0, errors[0] || '');
  await page.screenshot({ path: 'voice-final.png' });
  await browser.close();
  console.log(fails.length ? 'RESULT: FAILURES: ' + fails.join(' | ') : 'RESULT: ALL PASS');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('TEST CRASH: ' + e.message); process.exit(2); });
