# KATE — Personal AI Voice Assistant

Spec: `docs/kate-spec.md` (v1.1). Two systems: the **Android APK** (`app/`) and the
**Cloudflare portal** (`portal/`).

## Download

https://kate-releases.pages.dev — APK served from R2 via the portal Worker.

## What's built (all 5 iterations)

- **Voice core**: living Orb (idle/listening/thinking/speaking), streaming transcript,
  Driving Mode (auto-triggers on car Bluetooth), design system locked (near-black,
  electric cyan, mono type).
- **Voice**: Kokoro-82M int8 via sherpa-onnx (`bf_emma` default, `bf_isabella`
  selectable), sentence-streaming synthesis (speaks sentence 1 while 2 generates),
  Piper en_GB low-battery fallback, Voice Lab dev screen.
- **Ears**: "Kate" wake word (sherpa-onnx KWS — offline, no key), Silero VAD
  endpointing, whisper small.en with streaming partials, barge-in, mic foreground
  service with AEC.
- **Brain**: Groq `llama-3.3-70b-versatile` streaming → on-device Qwen3 8B int4 →
  Qwen3 1.7B (battery/thermal aware), silent fallback. Persona: warm, concise, British.
- **Memory**: Room (D1-shaped schema), every turn captured, topic/entity graph,
  semantic recall (USE embedder, hashing fallback), "what did I say about X",
  "forget that" (tombstones — deletions win), "pin that".
- **Skills / AI #2**: versioned JSON skills (spec §1.3), voice creation & triggering,
  WorkManager queue, `requires: online` steps wait for connectivity, Christian Bot
  built in, artifacts saved locally + uploaded to R2.
- **Dashboard**: force-directed memory graph (pinch/zoom/tap/pin/delete), answers
  log with model/latency/ratings, builds, live queue.
- **Portal** (`portal/`): Worker + D1 + R2 + Workers AI at
  https://kate-portal.luke1-temp16.workers.dev — HLC event sync (`/sync`),
  heavy skill steps (`/api/ai/step`), nightly memory consolidation (02:00 cron),
  web dashboard (same design), APK release hosting.

## Setup on the phone

1. Install APK → grant mic/notifications.
2. Settings → Voice Lab → download models you want (Kokoro 132MB, Piper 21MB,
   wake word 16MB, VAD 1MB, whisper 636MB; offline brains 1–4.9GB; embedder 100MB).
   Everything degrades gracefully while models are missing.
3. Settings → keys: paste a Groq API key (free tier) for the online brain.
4. Settings → portal sync: URL is prefilled; paste the portal token
   (`portal/.token` on the dev machine — never committed).

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:testDebugUnitTest   # 52 unit tests
./gradlew :app:assembleRelease     # signed (keystore/, dev-only)
```

Portal: `cd portal && npx wrangler deploy` (account: luke1.temp16; D1 `kate-db`,
R2 `kate-artifacts`, secret `KATE_TOKEN`).

## Spec deviations (documented, deliberate)

- **Wake word**: sherpa-onnx KWS instead of Porcupine (needs a paid access key);
  spec allows the OSS alternative. Picovoice key field exists for a later swap.
- **Offline LLMs**: Qwen3 8B/1.7B LiteRT-LM — Meta-gated Llama .task builds can't
  be fetched anonymously; same size/quality class as spec's Llama 8B/3B.
- **Vectors**: embeddings in Room + cosine (sqlite-vec needs a custom SQLite build;
  identical behaviour at single-user scale). Portal search embeds via Workers AI
  instead of Vectorize; Durable Objects deferred (dashboard polls).
- **Portal dashboard**: served as Worker static assets rather than a separate
  Pages project (current Cloudflare direction; same result).
