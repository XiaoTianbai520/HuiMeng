-- A2A task ownership is bound to one external client and one project; no user row is required.
CREATE TABLE a2a_tasks (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(128) NOT NULL,
  client_id VARCHAR(128) NOT NULL,
  team VARCHAR(128) NOT NULL,
  context_id VARCHAR(64) NOT NULL,
  session_file VARCHAR(128),
  state VARCHAR(32) NOT NULL,
  prompt TEXT NOT NULL,
  result_text TEXT,
  error VARCHAR(1024),
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX a2a_task_scope ON a2a_tasks(client_id, project_id, created_at);
