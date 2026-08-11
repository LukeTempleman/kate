# KATE — Personal AI Voice Assistant
## System Specification v1.1 — Iteration-First Build Plan

Target device: **Samsung Galaxy S25 Edge** (Snapdragon 8 Elite, 12GB RAM) — flagship tier assumptions apply throughout.

Two systems, built in strict order:

1. **Kate APK** (Iterations 1–4) — Android app. Voice-first, fully offline-capable, dual-AI, skills engine, local memory graph, futuristic driving-first UI.
2. **Kate Portal** (Iteration 5) — Cloudflare-only sync hub, heavy AI compute, and web dashboard. **Nothing in Iterations 1–4 depends on the portal existing.**

The APK connects to the portal through **D1 as the shared datastore**. D1 is only reachable from inside Workers, so the APK talks to a thin **Sync Worker API** (HTTPS + JSON) in front of D1 — functionally the same as "connected via D1," the Worker is just the doorway Cloudflare requires.

```
┌─────────────── PHONE (offline-capable) ───────────────┐
│  Wake word → VAD → STT → Kate Brain → TTS (Kokoro)    │
│       │                     │                          │
│  Task Queue (AI #2)    Local Memory (SQLite + vectors) │
│       │                     │                          │
│       └───────── Sync Engine (Iteration 5) ────────────┤
└────────────────────────────┬───────────────────────────┘
                             │ HTTPS
┌─────────────── CLOUDFLARE (Iteration 5) ─▼─────────────┐
│  Sync Worker ── D1 (source-of-truth mirror)            │
│  Workers AI (heavy jobs) ── Queues ── R2 (artifacts)   │
│  Vectorize (semantic search) ── Pages (web dashboard)  │
└─────────────────────────────────────────────────────────┘
```

---

# ITERATION PLAN

## ITERATION 1 — Voice Core + Interface (the current build)

**Goal:** a beautiful, working Kate you can talk to in the car. Interface, voice models, and LLM built together — no memory, no skills, no portal yet. The vocoder is the star; everything is judged by how good she sounds and how fast she responds.

### Milestone 1.1 — App shell & interface

- Kotlin + Jetpack Compose project scaffold, single-activity architecture
- **The Orb** — Kate's face, a living waveform/orb component with four animated states: idle pulse, listening ripple, thinking shimmer, speaking waveform (amplitude-driven from TTS output)
- Conversation transcript view (live partial STT text appears as you speak, Kate's reply streams in)
- Settings screen: voice selection (Emma / Isabella / Piper fallback), brain selection (auto / online / offline), wake word on/off, latency readout toggle
- Design system locked in now: near-black base, single electric-cyan accent, thin-line geometry, mono/semi-mono display type
- **Driving Mode v1**: full-screen orb, giant text, zero small tap targets, auto-trigger on Bluetooth car connection

*Exit: the app looks like the finished product with a dummy brain behind it.*

### Milestone 1.2 — The voice (TTS first)

TTS lands before STT because the vocoder is the flagship — validate it before anything else.

- **Kokoro-82M via sherpa-onnx**, default voice `bf_emma`, `bf_isabella` selectable
- **Streaming sentence-by-sentence synthesis** — Kate begins speaking on the first complete sentence, never waits for full text
- Orb speaking-state driven by real audio amplitude
- Piper `en_GB` fallback wired in (low-battery / thermal mode)
- Dev screen: type anything, hear Kate say it — used to tune pacing, pitch, and chunking
- (Deferred to later polish: Chatterbox voice cloning — viable on the S25 Edge, but not Iteration 1)

*Exit: Kate's voice is genuinely pleasant — the "wow" is real before the brain exists.*

### Milestone 1.3 — The ears

- **Picovoice Porcupine** custom "Kate" wake word, running in a foreground service (persistent notification + battery-optimization exemption, as Android requires)
- **Silero VAD** for endpointing — detects the instant you stop talking
- **whisper.cpp `small.en`** (the S25 Edge handles it comfortably; better car-noise accuracy than `base.en`), streaming partial transcripts to the UI while you speak
- Barge-in: saying "Kate" while she's speaking cuts audio immediately and opens the mic

*Exit: say "Kate" from across the room, watch your words appear live as you speak.*

### Milestone 1.4 — The brain

- **Online:** Groq free tier (Llama 3.3 70B), streaming tokens
- **Offline:** on-device via MLC / MediaPipe LLM Inference —
  - Primary: **Llama 3.1 8B (4-bit, ~4.5–5GB, ~10–15 tok/s, NPU-accelerated)**
  - Fallback: **Llama 3.2 3B** on RAM pressure, battery saver, or thermal throttle (a phone mount in direct sun is a real scenario)
  - 8B loads on demand; models are not held resident alongside Whisper + Kokoro unnecessarily (~6GB combined ceiling)
- Routing: Groq → on-device 8B → on-device 3B, automatic and silent
- Kate's persona prompt: warm, concise British assistant; short spoken turns; long content deferred with a spoken summary

*Exit: full conversations online and in airplane mode.*

### Milestone 1.5 — The loop (latency war)

Wire the whole pipeline and fight for the sub-second feel:

1. Wake word fires (~100 ms), mic already hot
2. STT streams partials while you talk; VAD fires on speech end
3. Transcript sent instantly; first Groq token in 200–400 ms (on-device: 400–800 ms)
4. Kokoro speaks sentence #1 while sentence #2 is still generating

- Latency instrumentation on every stage, visible via the settings readout
- Barge-in tested under playback; echo cancellation tuned so Kate doesn't hear herself
- Driving Mode field test: real car, real road noise, phone mount

**Iteration 1 exit criteria: voice-to-first-word < 1s online, < 1.5s offline; feels instant while driving; Kate's voice is the best thing about the app.**

---

## ITERATION 2 — Memory

- SQLite (Room) + `sqlite-vec` local vector store; every turn captured
- Entity/topic extraction (background, on-device model)
- Memory graph tables (nodes + edges) — the data layer for the Obsidian view later
- Voice recall: "Kate, what did I say about X?" → vector + graph search → answer with source
- Controls: "forget that," pin permanent memories
- *Exit: airplane-mode conversation with working recall of past sessions.*

## ITERATION 3 — Dual-AI: Skills + Task Queue

- **AI #2** arrives: WorkManager-backed task queue; AI #1 acknowledges instantly, work runs behind her, results announced by voice or notification
- Skill engine: create skills by voice, saved as versioned JSON (see §1.3 of the system spec below), triggered by name
- Reference skill: **Christian Bot** — topic in → biblical analysis → verse research (online step) → YouTube script artifact out
- Offline behavior: `requires: online` steps mark tasks `pending-online`, auto-fire on connectivity
- *Exit: "build me X" → saved → "run X" → artifact delivered.*

## ITERATION 4 — In-App Dashboard

- Memory Graph view: force-directed Obsidian-style canvas — topic clusters, conversation and entity nodes; tap to expand, long-press to edit/delete/pin
- Kate's Answers log: every Q&A with model used, latency, 👍/👎 rating
- Builds view: all skills and artifacts with run history
- Task Queue view: live queue state
- *Exit: the full memory tree is visible and editable on the phone.*

## ITERATION 5 — Portal + Sync (Cloudflare)

- Sync Worker + D1 schema (see System 2 below); event-sourced offline-first sync with hybrid logical clocks; deletions always win
- Workers AI takes over heavy skill steps and nightly memory consolidation; Vectorize for portal-side semantic search; R2 for artifacts; Durable Objects for live task status
- Pages web dashboard: full-canvas memory graph, answer review, skill studio, task monitor, artifact library — same design language as the APK
- *Exit: a task queued in the car finishes in the cloud and Kate announces it.*

## LATER — Capability Modules

Calendar, music (local + Spotify), navigation hand-off, phone/SMS, smart home, email triage, timers. The `KateCapability` interface (below) ships in Iteration 1's scaffold so these slot in without core changes.

---

# SYSTEM 1 — KATE APK (reference spec)

## 1.1 Voice Pipeline

| Stage | Component | Notes |
|---|---|---|
| Wake word | Picovoice Porcupine (custom "Kate") | Foreground service, ~1% CPU. openWakeWord as OSS alternative. |
| Endpointing | Silero VAD | Speech-end detection |
| STT | whisper.cpp `small.en` | Streaming partials; Android SpeechRecognizer as fallback |
| **TTS** | **Kokoro-82M via sherpa-onnx, `bf_emma`** | Streaming, sentence-chunked |
| TTS fallback | Piper `en_GB` | Low-battery / thermal mode |
| TTS premium (post-v1) | Chatterbox (MIT) cloning | Viable on S25 Edge; optional |

## 1.2 Dual-AI Architecture

**AI #1 — Conversational:** live loop, short turns. Routing: Groq → on-device 8B → on-device 3B (thermal/battery fallback).
**AI #2 — Worker:** task queue (WorkManager locally; Cloudflare Queues + Workers AI once the portal exists). Lifecycle: `queued → running (local | cloud) → done | failed → announced`.

## 1.3 Skills Engine

```json
{
  "id": "christian-bot",
  "name": "Christian Bot",
  "trigger_phrases": ["christian bot", "bible script"],
  "inputs": [{ "name": "topic", "type": "voice_string" }],
  "steps": [
    { "type": "llm", "prompt": "Analyze the topic '{topic}' from a biblical perspective..." },
    { "type": "research", "query": "Bible verses about {topic}", "requires": "online" },
    { "type": "llm", "prompt": "Compile a YouTube script: hook, key points, verses (book ch:v), research notes, CTA." },
    { "type": "save_artifact", "format": "markdown", "destination": "r2 + local" }
  ],
  "output": { "spoken_summary": true, "artifact": true }
}
```

Skills are versioned; runs are logged (inputs, outputs, duration, model used).

## 1.4 Memory System

On-device first: SQLite + sqlite-vec; graph of conversations → topics → entities → skills; semantic + graph recall; forget/pin controls. Mirrored to D1 in Iteration 5.

## 1.5 Offline Guarantee

Fully offline: wake word, STT, Kokoro TTS, on-device LLM, all local skills, memory + recall, dashboard. **No feature hard-fails offline — it degrades or defers.**

## 1.6 Extension Interface

```kotlin
interface KateCapability {
  val name: String                    // "calendar", "music", "navigation"
  val intents: List<IntentPattern>    // phrases that route here
  fun handle(intent: ParsedIntent): CapabilityResult
}
```

---

# SYSTEM 2 — KATE PORTAL (Cloudflare-only, Iteration 5)

## 2.1 Stack

| Layer | Cloudflare service |
|---|---|
| Web dashboard | Pages (React/SvelteKit) |
| API + sync | Workers |
| Database | **D1** |
| Heavy AI | Workers AI — `llama-3.3-70b-instruct-fp8-fast`, `bge-base-en-v1.5` embeddings |
| Semantic search | Vectorize |
| Job queue | Queues |
| Artifacts | R2 |
| Live state | Durable Objects |
| Auth | Single-user device-bound token |

## 2.2 Sync Protocol (APK ⇄ D1)

1. Every phone-side change appends to a local `sync_log` (hybrid logical clock timestamps)
2. When online: `POST /sync` → Worker applies events to D1, returns unseen portal-side events
3. Conflicts: last-writer-wins per field; **deletions always win** (a "forget" must stick)
4. Artifacts → R2; D1 holds metadata + keys; APK caches artifacts for offline access

## 2.3 D1 Schema (core tables)

```sql
conversations(id, started_at, context, device);
turns(id, conversation_id, role, text, audio_ms, model_used, latency_ms, rating);
memories(id, type, content, embedding_ref, pinned, source_turn_id, created_at);
graph_nodes(id, kind, label, memory_id);          -- kind: topic|entity|conversation|skill
graph_edges(id, from_node, to_node, relation, weight);
skills(id, name, definition_json, version, created_via, updated_at);
skill_runs(id, skill_id, inputs_json, status, artifact_r2_key, started_at, finished_at);
tasks(id, kind, payload_json, status, ran_on, result_ref, created_at);
sync_log(id, entity, entity_id, op, hlc_timestamp, applied);
```

## 2.4 Portal Dashboard

Full-canvas memory graph with search/filter/edit; answer review table (ratings, latency, routing); skill studio (edit JSON, test-run on Workers AI, version history); live task monitor via Durable Objects; artifact library. Same dark, single-accent, mono-type design language as the APK.

## 2.5 Portal-side AI Duties

Runs `requires: online` skill steps; nightly memory consolidation (summarize the day, recompute graph edges, refresh embeddings); cross-conversation topic mapping at full quality, synced down.
