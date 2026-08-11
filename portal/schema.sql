-- Kate Portal D1 schema (spec §2.3) — source-of-truth mirror of the phone.

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,           -- "<device>:<localId>"
  started_at INTEGER,
  context TEXT DEFAULT '',
  device TEXT DEFAULT 'phone'
);

CREATE TABLE IF NOT EXISTS turns (
  id TEXT PRIMARY KEY,
  conversation_id TEXT,
  role TEXT,
  text TEXT,
  audio_ms INTEGER DEFAULT 0,
  model_used TEXT,
  latency_ms INTEGER,
  rating INTEGER DEFAULT 0,
  created_at INTEGER,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS memories (
  id TEXT PRIMARY KEY,
  type TEXT,
  content TEXT,
  embedding_ref TEXT,
  pinned INTEGER DEFAULT 0,
  deleted INTEGER DEFAULT 0,     -- tombstone: deletions always win
  source_turn_id TEXT,
  created_at INTEGER,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS graph_nodes (
  id TEXT PRIMARY KEY,
  kind TEXT,
  label TEXT,
  memory_id TEXT,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS graph_edges (
  id TEXT PRIMARY KEY,
  from_node TEXT,
  to_node TEXT,
  relation TEXT,
  weight REAL DEFAULT 1,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS skills (
  id TEXT PRIMARY KEY,
  name TEXT,
  definition_json TEXT,
  version INTEGER DEFAULT 1,
  created_via TEXT DEFAULT 'voice',
  updated_at INTEGER,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS skill_runs (
  id TEXT PRIMARY KEY,
  skill_id TEXT,
  inputs_json TEXT,
  status TEXT,
  artifact_r2_key TEXT,
  ran_on TEXT DEFAULT 'local',
  started_at INTEGER,
  finished_at INTEGER,
  hlc TEXT
);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  kind TEXT,
  payload_json TEXT,
  status TEXT,
  ran_on TEXT,
  result_ref TEXT,
  created_at INTEGER,
  hlc TEXT
);

-- Event log: everything the phone sends, plus portal-side events the phone
-- hasn't pulled yet (nightly consolidation output).
CREATE TABLE IF NOT EXISTS sync_log (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  entity TEXT,
  entity_id TEXT,
  op TEXT,                       -- upsert | delete
  payload_json TEXT,
  hlc TEXT,
  origin TEXT                    -- device id or 'portal'
);

CREATE INDEX IF NOT EXISTS idx_sync_seq ON sync_log(seq);
CREATE INDEX IF NOT EXISTS idx_turns_conv ON turns(conversation_id);
CREATE INDEX IF NOT EXISTS idx_mem_deleted ON memories(deleted);
