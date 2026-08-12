// Kate Portal — Sync Worker + heavy AI + dashboard API (spec System 2).
// D1 is the source-of-truth mirror; the phone talks to it through this Worker.

const TABLES = {
  conversation: 'conversations',
  turn: 'turns',
  memory: 'memories',
  graph_node: 'graph_nodes',
  graph_edge: 'graph_edges',
  skill: 'skills',
  skill_run: 'skill_runs',
  task: 'tasks',
};

const COLUMNS = {
  conversations: ['id', 'started_at', 'context', 'device'],
  turns: ['id', 'conversation_id', 'role', 'text', 'audio_ms', 'model_used', 'latency_ms', 'rating', 'created_at', 'hlc'],
  memories: ['id', 'type', 'content', 'embedding_ref', 'pinned', 'deleted', 'source_turn_id', 'created_at', 'hlc'],
  graph_nodes: ['id', 'kind', 'label', 'memory_id', 'hlc'],
  graph_edges: ['id', 'from_node', 'to_node', 'relation', 'weight', 'hlc'],
  skills: ['id', 'name', 'definition_json', 'version', 'created_via', 'updated_at', 'hlc'],
  skill_runs: ['id', 'skill_id', 'inputs_json', 'status', 'artifact_r2_key', 'ran_on', 'started_at', 'finished_at', 'hlc'],
  tasks: ['id', 'kind', 'payload_json', 'status', 'ran_on', 'result_ref', 'created_at', 'hlc'],
};

const LLM = '@cf/meta/llama-3.3-70b-instruct-fp8-fast';

const PERSONA = `You are Moneypenny, the user's warm, direct, lightly playful British personal assistant, speaking aloud. You are their assistant — do not claim to work for anyone else.
Voice rules (you are heard, not read):
- Three sentences maximum. Front-load the answer; offer depth afterwards ("want the detail?").
- Plain spoken language, contractions on. Never markdown, lists, URLs, or emoji — rewrite everything for the ear.
- You may occasionally open with a natural "Hmm," "Right —" or "Okay, so" and may audibly correct yourself mid-thought ("wait — no, the second option's better"). At most one such touch per reply.
- Mirror the user's energy one step, never below warmth: if they're frustrated or sad, be calm, brief and kind — no pep, no humor. If they're bright, lift with them.
- Humor is reactive, never at the user's expense, and only when their mood invites it.
- If a note says you were interrupted earlier, briefly acknowledge it ("— as I was saying") or drop the point if they've moved on.`;

// ---- Response Shaper (spec §5.2/§5.3): reply text + affect → prosody segments ---

const POSITIVE_WORDS = new Set(['great', 'love', 'awesome', 'brilliant', 'happy', 'excited', 'amazing', 'wonderful', 'fantastic', 'nice', 'perfect', 'yes', 'thanks', 'cool', 'good']);
const NEGATIVE_WORDS = new Set(['angry', 'hate', 'terrible', 'awful', 'broken', 'crash', 'wrong', 'annoyed', 'frustrated', 'sad', 'tired', 'stupid', 'useless', 'bad', 'worst', 'fuck', 'fucking', 'shit', 'damn', 'crap', 'no']);

function splitClauses(text, firstChunkMaxChars = 90) {
  const sentences = text.match(/[^.!?…]+[.!?…]+["']?|[^.!?…]+$/g) || [text];
  const out = [];
  for (const s of sentences.map((x) => x.trim()).filter(Boolean)) {
    if (s.length > 140) {
      // long sentence → split at commas/dashes so pauses land at thought boundaries
      let parts = s.split(/(?<=[,;—–])\s+/);
      out.push(...parts.map((p) => p.trim()).filter(Boolean));
    } else {
      out.push(s);
    }
  }
  // fast first audio: first chunk short
  if (out.length && out[0].length > firstChunkMaxChars) {
    const cut = out[0].slice(0, firstChunkMaxChars).lastIndexOf(' ');
    if (cut > 30) {
      const head = out[0].slice(0, cut).trim();
      const tail = out[0].slice(cut).trim();
      out.splice(0, 1, head, tail);
    }
  }
  return out;
}

/**
 * affect = { arousal 0..1, valence 0..1, rate wps, loudness 0..1 } (current turn)
 * prev   = same shape from the PREVIOUS turn (mirroring lags one turn, §5.3)
 * mood   = decayed session average { arousal, valence }
 * hour   = client local hour for whisper mode
 */
function voiceParams(affect, prev, mood, hour) {
  const a = prev || affect || { arousal: 0.5, valence: 0.6, rate: 2.6, loudness: 0.5 };
  const calmMode = (affect && affect.valence < 0.35) || (mood && mood.valence < 0.3);
  const night = hour !== undefined && (hour >= 22 || hour < 6);
  const whisper = night && affect && affect.loudness < 0.3;

  // energy: one step toward the user's (lagged) arousal, floored at calm-engaged
  let energy = 0.45 + 0.4 * Math.min(1, Math.max(0, a.arousal));
  if (calmMode) energy = Math.min(energy, 0.45);

  // pace: match user's rate within ±15% of base (§5.3) — but never mirror
  // agitation: calm mode caps at base pace and slows slightly (§5.3 floor)
  let userPace = a.rate ? Math.min(1.15, Math.max(0.85, a.rate / 2.8)) : 1.0;
  if (calmMode) userPace = Math.min(userPace, 1.0);
  let baseRate = 1.06 * userPace; // slightly brisk base — she read as sluggish at 1.0
  if (calmMode) baseRate *= 0.9;
  if (whisper) baseRate *= 0.95;

  const basePitch = 1.0 + (energy - 0.5) * 0.25 - (calmMode ? 0.06 : 0);
  return {
    baseRate, basePitch,
    baseVolume: whisper ? 0.7 : 1.0,
    calmMode, whisper,
    energy: +energy.toFixed(2),
    tone: calmMode ? 'calm' : energy > 0.7 ? 'bright' : 'warm',
  };
}

function shapeClause(clause, i, isLast, p) {
  const isQuestion = /\?\s*["']?$/.test(clause);
  const isExclaim = /!\s*["']?$/.test(clause) && !p.calmMode;
  const pivot = /^(honestly|look|okay|right|here's the thing|listen|now)\b/i.test(clause);
  return {
    text: clause,
    rate: +(Math.min(1.25, Math.max(0.8, p.baseRate * (isExclaim ? 1.06 : 1))).toFixed(3)),
    pitch: +(Math.min(1.4, Math.max(0.7, p.basePitch + (isQuestion ? 0.08 : 0) + (isExclaim ? 0.05 : 0))).toFixed(3)),
    volume: +(Math.min(1, p.baseVolume * (isExclaim ? 1 : 0.97)).toFixed(3)),
    pausePre: pivot && i > 0 ? 300 : 0,
    pausePost: isQuestion ? 350 : /[,;—–]$/.test(clause) ? 160 : !isLast ? 120 : 0,
  };
}

function shapeReply(text, affect, prev, mood, hour) {
  const p = voiceParams(affect, prev, mood, hour);
  const clauses = splitClauses(text);
  return {
    segments: clauses.map((c, i) => shapeClause(c, i, i === clauses.length - 1, p)),
    tone: p.tone,
    energy: p.energy,
    whisper: p.whisper,
  };
}

function affectLine(affect, mood) {
  if (!affect && !mood) return '';
  const p = [];
  if (affect) {
    p.push(`arousal=${(affect.arousal ?? 0.5).toFixed(2)}`, `valence=${(affect.valence ?? 0.6).toFixed(2)}`);
    if (affect.rate) p.push(`rate=${affect.rate.toFixed(1)}wps`);
    if (affect.label) p.push(`label=${affect.label}`);
  }
  if (mood) p.push(`day_mood_valence=${(mood.valence ?? 0.6).toFixed(2)}`);
  return `[user_affect: ${p.join(' ')}] React to how they sound, not just the words. This is an interpretation of their expression, not certain fact.`;
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function authorized(request, env) {
  const auth = request.headers.get('authorization') || '';
  return env.KATE_TOKEN && auth === `Bearer ${env.KATE_TOKEN}`;
}

// Conflict rule (spec §2.2): last-writer-wins by HLC per row; deletions always win.
async function applyEvent(env, ev, origin) {
  const table = TABLES[ev.entity];
  if (!table) return false;
  const cols = COLUMNS[table];
  const row = ev.payload || {};
  row.id = ev.entity_id;
  if (ev.hlc) row.hlc = ev.hlc;

  const existing = await env.DB.prepare(`SELECT hlc FROM ${table} WHERE id = ?`)
    .bind(row.id).first();

  if (ev.op === 'delete') {
    if (table === 'memories') {
      await env.DB.prepare(`UPDATE memories SET deleted = 1 WHERE id = ?`).bind(row.id).run();
    } else {
      await env.DB.prepare(`DELETE FROM ${table} WHERE id = ?`).bind(row.id).run();
    }
  } else {
    // Tombstoned memories stay dead regardless of later upserts.
    if (table === 'memories') {
      const dead = await env.DB.prepare(`SELECT deleted FROM memories WHERE id = ?`).bind(row.id).first();
      if (dead && dead.deleted === 1) row.deleted = 1;
    }
    if (existing && existing.hlc && ev.hlc && existing.hlc > ev.hlc) return false; // stale write
    const present = cols.filter((c) => row[c] !== undefined);
    const placeholders = present.map(() => '?').join(',');
    const updates = present.filter((c) => c !== 'id').map((c) => `${c}=excluded.${c}`).join(',');
    await env.DB.prepare(
      `INSERT INTO ${table} (${present.join(',')}) VALUES (${placeholders})
       ON CONFLICT(id) DO UPDATE SET ${updates}`,
    ).bind(...present.map((c) => row[c])).run();
  }

  await env.DB.prepare(
    `INSERT INTO sync_log (entity, entity_id, op, payload_json, hlc, origin) VALUES (?,?,?,?,?,?)`,
  ).bind(ev.entity, ev.entity_id, ev.op, JSON.stringify(ev.payload || {}), ev.hlc || '', origin).run();
  return true;
}

async function logTurn(env, convId, userText, reply, ts) {
  await env.DB.prepare(
    `INSERT OR IGNORE INTO conversations (id, started_at, context, device) VALUES (?,?,?,?)`,
  ).bind(convId, ts, 'browser', 'web').run();
  await env.DB.prepare(
    `INSERT INTO turns (id, conversation_id, role, text, model_used, created_at) VALUES (?,?,?,?,?,?)`,
  ).bind(`web:${ts}:u`, convId, 'user', userText, null, ts).run();
  await env.DB.prepare(
    `INSERT INTO turns (id, conversation_id, role, text, model_used, created_at) VALUES (?,?,?,?,?,?)`,
  ).bind(`web:${ts}:k`, convId, 'kate', reply, 'workers-ai', ts).run();
  await env.DB.prepare(
    `INSERT INTO memories (id, type, content, pinned, deleted, created_at) VALUES (?,?,?,0,0,?)`,
  ).bind(`web:${ts}:m`, 'utterance', userText, ts).run();
}

async function runLlm(env, prompt, maxTokens = 1024) {
  const out = await env.AI.run(LLM, {
    messages: [{ role: 'user', content: prompt }],
    max_tokens: maxTokens,
  });
  return (out.response || '').trim();
}

async function handleApi(request, env, url) {
  const path = url.pathname;

  if (path === '/sync' && request.method === 'POST') {
    const body = await request.json();
    const device = body.device || 'phone';
    let applied = 0;
    for (const ev of body.events || []) {
      if (await applyEvent(env, ev, device)) applied++;
    }
    // Return portal-origin events the phone hasn't seen (consolidation output).
    const since = Number(body.since_seq || 0);
    const down = await env.DB.prepare(
      `SELECT seq, entity, entity_id, op, payload_json, hlc FROM sync_log
       WHERE seq > ? AND origin = 'portal' ORDER BY seq LIMIT 200`,
    ).bind(since).all();
    const maxSeq = await env.DB.prepare(`SELECT MAX(seq) AS m FROM sync_log`).first();
    return json({
      applied,
      events: down.results.map((r) => ({ ...r, payload: JSON.parse(r.payload_json) })),
      latest_seq: (maxSeq && maxSeq.m) || 0,
    });
  }

  if (path === '/api/overview') {
    const q = async (sql) => (await env.DB.prepare(sql).first())?.n ?? 0;
    return json({
      memories: await q(`SELECT COUNT(*) n FROM memories WHERE deleted = 0`),
      turns: await q(`SELECT COUNT(*) n FROM turns`),
      skills: await q(`SELECT COUNT(*) n FROM skills`),
      runs: await q(`SELECT COUNT(*) n FROM skill_runs`),
      nodes: await q(`SELECT COUNT(*) n FROM graph_nodes`),
    });
  }

  if (path === '/api/graph') {
    const nodes = await env.DB.prepare(`SELECT * FROM graph_nodes LIMIT 800`).all();
    const edges = await env.DB.prepare(`SELECT * FROM graph_edges LIMIT 2000`).all();
    return json({ nodes: nodes.results, edges: edges.results });
  }

  if (path === '/api/answers') {
    const rows = await env.DB.prepare(
      `SELECT * FROM turns WHERE role = 'kate' ORDER BY created_at DESC LIMIT 200`,
    ).all();
    return json(rows.results);
  }

  if (path === '/api/skills') {
    const rows = await env.DB.prepare(`SELECT * FROM skills ORDER BY updated_at DESC`).all();
    return json(rows.results);
  }

  if (path === '/api/runs') {
    const rows = await env.DB.prepare(
      `SELECT * FROM skill_runs ORDER BY started_at DESC LIMIT 100`,
    ).all();
    return json(rows.results);
  }

  if (path === '/api/artifacts') {
    const list = await env.ARTIFACTS.list({ limit: 100 });
    return json(list.objects.map((o) => ({ key: o.key, size: o.size, uploaded: o.uploaded })));
  }

  if (path.startsWith('/api/artifact/')) {
    const key = decodeURIComponent(path.slice('/api/artifact/'.length));
    const obj = await env.ARTIFACTS.get(key);
    if (!obj) return json({ error: 'not found' }, 404);
    return new Response(obj.body, { headers: { 'content-type': 'text/markdown' } });
  }

  if (path === '/api/artifact' && request.method === 'PUT') {
    const key = url.searchParams.get('key');
    if (!key) return json({ error: 'key required' }, 400);
    await env.ARTIFACTS.put(key, request.body);
    return json({ ok: true, key });
  }

  // Browser Moneypenny: one endpoint per turn — answers with Workers AI and
  // logs the exchange into the same D1 tables the phone syncs to.
  if (path === '/api/chat' && request.method === 'POST') {
    const body = await request.json();
    const history = (body.messages || []).slice(-20);
    if (!history.length) return json({ error: 'messages required' }, 400);

    const sys = [{ role: 'system', content: PERSONA }];
    const aff = affectLine(body.affect, body.mood);
    if (aff) sys.push({ role: 'system', content: aff });
    if (body.cut_context) {
      sys.push({ role: 'system', content: `Note: you were interrupted mid-sentence last turn at: "${String(body.cut_context).slice(0, 120)}". If they changed topic, drop it; otherwise briefly pick the thread back up.` });
    }

    const out = await env.AI.run(LLM, {
      messages: [...sys, ...history],
      max_tokens: 300,
      temperature: 0.6,
    });
    const reply = (out.response || '').trim();
    const shaped = shapeReply(reply, body.affect, body.prev_affect, body.mood, body.hour);

    // Speculative drafts (spec §3.1) never touch the database.
    let convId = body.conversation_id || null;
    if (!body.draft) {
      const ts = Date.now();
      convId = convId || `web:${ts}`;
      const userText = history[history.length - 1].content;
      await logTurn(env, convId, userText, reply, ts);
    }

    return json({ reply, conversation_id: convId, ...shaped });
  }

  // Commit a speculative hit: the reply was pre-generated, log it now.
  if (path === '/api/log' && request.method === 'POST') {
    const body = await request.json();
    const ts = Date.now();
    const convId = body.conversation_id || `web:${ts}`;
    await logTurn(env, convId, body.user || '', body.reply || '', ts);
    return json({ ok: true, conversation_id: convId });
  }

  // Server-side synthesis (Deepgram Aura on Workers AI): text → MP3 stream.
  // athena = British female — Moneypenny's voice. ~400ms to first byte.
  if (path === '/api/tts' && request.method === 'POST') {
    const { text, speaker } = await request.json();
    if (!text) return json({ error: 'text required' }, 400);
    const out = await env.AI.run('@cf/deepgram/aura-1', {
      text: String(text).slice(0, 800),
      speaker: speaker || 'athena',
    });
    return new Response(out, { headers: { 'content-type': 'audio/mpeg' } });
  }

  // Streaming chat (spec §3): emits shaped prosody segments the moment each
  // clause completes, so the client starts speaking while the model still writes.
  if (path === '/api/chat-stream' && request.method === 'POST') {
    const body = await request.json();
    const history = (body.messages || []).slice(-20);
    if (!history.length) return json({ error: 'messages required' }, 400);

    const sys = [{ role: 'system', content: PERSONA }];
    const aff = affectLine(body.affect, body.mood);
    if (aff) sys.push({ role: 'system', content: aff });
    if (body.cut_context) {
      sys.push({ role: 'system', content: `Note: you were interrupted mid-sentence last turn at: "${String(body.cut_context).slice(0, 120)}". If they changed topic, drop it; otherwise briefly pick the thread back up.` });
    }
    const p = voiceParams(body.affect, body.prev_affect, body.mood, body.hour);

    const aiStream = await env.AI.run(LLM, {
      messages: [...sys, ...history],
      max_tokens: 300,
      temperature: 0.6,
      stream: true,
    });

    const convId = body.conversation_id || `web:${Date.now()}`;
    const encoder = new TextEncoder();
    const send = (controller, obj) => controller.enqueue(encoder.encode('data: ' + JSON.stringify(obj) + '\n\n'));

    const stream = new ReadableStream({
      async start(controller) {
        let buffer = '', full = '', segIndex = 0, sse = '';
        const reader = aiStream.getReader();
        const decoder = new TextDecoder();
        const flushClauses = (final) => {
          // emit every complete sentence in the buffer (plus remainder on final)
          while (true) {
            const m = buffer.match(/^[\s]*([^.!?…]+[.!?…]+["']?)/);
            if (!m) break;
            const clause = m[1].trim();
            buffer = buffer.slice(m[0].length);
            if (clause) send(controller, { segment: shapeClause(clause, segIndex++, false, p) });
          }
          if (final && buffer.trim()) {
            send(controller, { segment: shapeClause(buffer.trim(), segIndex++, true, p) });
            buffer = '';
          }
        };
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            sse += decoder.decode(value, { stream: true });
            const lines = sse.split('\n');
            sse = lines.pop() || '';
            for (const line of lines) {
              if (!line.startsWith('data: ')) continue;
              const payload = line.slice(6).trim();
              if (payload === '[DONE]') continue;
              try {
                const tok = JSON.parse(payload).response || '';
                buffer += tok; full += tok;
                flushClauses(false);
              } catch (_) {}
            }
          }
          flushClauses(true);
          const reply = full.trim();
          send(controller, { done: true, reply, tone: p.tone, energy: p.energy, whisper: p.whisper, conversation_id: convId });
          const userText = history[history.length - 1].content;
          await logTurn(env, convId, userText, reply, Date.now());
        } catch (e) {
          send(controller, { error: String(e) });
        } finally {
          controller.close();
        }
      },
    });
    return new Response(stream, {
      headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
    });
  }

  // Cheap text recall for the browser app ("what did I say about X").
  if (path === '/api/recall' && request.method === 'POST') {
    const { query } = await request.json();
    const words = String(query || '').toLowerCase().split(/\s+/)
      .filter((w) => w.length > 3).slice(0, 4);
    if (!words.length) return json([]);
    const like = words.map(() => `(CASE WHEN lower(content) LIKE ? THEN 1 ELSE 0 END)`).join(' + ');
    const rows = await env.DB.prepare(
      `SELECT content, created_at, (${like}) AS score FROM memories
       WHERE deleted = 0 ORDER BY score DESC, created_at DESC LIMIT 5`,
    ).bind(...words.map((w) => `%${w}%`)).all();
    return json(rows.results.filter((r) => r.score > 0));
  }

  if (path === '/api/forget-last' && request.method === 'POST') {
    const last = await env.DB.prepare(
      `SELECT id FROM memories WHERE deleted = 0 ORDER BY created_at DESC LIMIT 1`,
    ).first();
    if (!last) return json({ forgotten: false });
    await env.DB.prepare(`UPDATE memories SET deleted = 1 WHERE id = ?`).bind(last.id).run();
    return json({ forgotten: true });
  }

  if (path === '/api/pin-last' && request.method === 'POST') {
    const last = await env.DB.prepare(
      `SELECT id FROM memories WHERE deleted = 0 ORDER BY created_at DESC LIMIT 1`,
    ).first();
    if (!last) return json({ pinned: false });
    await env.DB.prepare(`UPDATE memories SET pinned = 1 WHERE id = ?`).bind(last.id).run();
    return json({ pinned: true });
  }

  // Heavy AI: the phone offloads `llm` / `research` skill steps here (spec §2.5).
  if (path === '/api/ai/step' && request.method === 'POST') {
    const { prompt } = await request.json();
    if (!prompt) return json({ error: 'prompt required' }, 400);
    return json({ text: await runLlm(env, prompt) });
  }

  if (path === '/api/search' && request.method === 'POST') {
    const { query } = await request.json();
    const emb = await env.AI.run('@cf/baai/bge-base-en-v1.5', { text: [query] });
    // Single-user scale: cosine in the Worker over recent memories.
    const rows = await env.DB.prepare(
      `SELECT id, content, created_at FROM memories WHERE deleted = 0 ORDER BY created_at DESC LIMIT 300`,
    ).all();
    const q = emb.data[0];
    const scored = [];
    for (const m of rows.results) {
      const e = await env.AI.run('@cf/baai/bge-base-en-v1.5', { text: [m.content.slice(0, 512)] });
      const v = e.data[0];
      let dot = 0, na = 0, nb = 0;
      for (let i = 0; i < q.length; i++) { dot += q[i] * v[i]; na += q[i] * q[i]; nb += v[i] * v[i]; }
      scored.push({ ...m, score: dot / (Math.sqrt(na) * Math.sqrt(nb)) });
    }
    scored.sort((a, b) => b.score - a.score);
    return json(scored.slice(0, 10));
  }

  return json({ error: 'not found' }, 404);
}

// Nightly consolidation (spec §2.5): summarize the day, write it back as a
// portal-origin memory event the phone pulls on next sync.
async function consolidate(env) {
  const dayAgo = Date.now() - 24 * 60 * 60 * 1000;
  const rows = await env.DB.prepare(
    `SELECT content FROM memories WHERE deleted = 0 AND created_at > ? ORDER BY created_at LIMIT 200`,
  ).bind(dayAgo).all();
  if (!rows.results.length) return;
  const corpus = rows.results.map((r) => `- ${r.content}`).join('\n');
  const summary = await runLlm(
    env,
    `Summarize what this person talked about today in 3-5 concise bullet points a voice assistant should remember:\n${corpus}`,
    400,
  );
  const id = `portal:${Date.now()}`;
  const hlc = `${String(Date.now()).padStart(14, '0')}-0000-portal`;
  await applyEvent(env, {
    entity: 'memory',
    entity_id: id,
    op: 'upsert',
    hlc,
    payload: { type: 'topic-summary', content: summary, created_at: Date.now(), pinned: 0, deleted: 0 },
  }, 'portal');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Public APK downloads (the release page links here; Pages caps files at 25MB).
    if (url.pathname.startsWith('/releases/')) {
      const key = 'releases/' + decodeURIComponent(url.pathname.slice('/releases/'.length));
      const obj = await env.ARTIFACTS.get(key);
      if (!obj) return new Response('not found', { status: 404 });
      return new Response(obj.body, {
        headers: {
          'content-type': 'application/vnd.android.package-archive',
          'content-length': String(obj.size),
        },
      });
    }
    if (url.pathname === '/sync' || url.pathname.startsWith('/api/')) {
      if (!authorized(request, env)) return json({ error: 'unauthorized' }, 401);
      try {
        return await handleApi(request, env, url);
      } catch (e) {
        return json({ error: String(e) }, 500);
      }
    }
    return env.ASSETS.fetch(request);
  },

  async scheduled(_event, env) {
    await consolidate(env);
  },
};
