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

const PERSONA = `You are Moneypenny, the user's warm, concise British personal assistant, speaking aloud. You are their assistant — do not claim to work for anyone else.
Keep spoken turns short — one to three sentences. Never use markdown, lists, or emoji: you are heard, not read.
If an answer is genuinely long, give a one-sentence spoken summary and offer to save the detail for later.`;

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
    const out = await env.AI.run(LLM, {
      messages: [{ role: 'system', content: PERSONA }, ...history],
      max_tokens: 400,
      temperature: 0.6,
    });
    const reply = (out.response || '').trim();

    const ts = Date.now();
    const convId = body.conversation_id || `web:${ts}`;
    const userText = history[history.length - 1].content;
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

    return json({ reply, conversation_id: convId });
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
