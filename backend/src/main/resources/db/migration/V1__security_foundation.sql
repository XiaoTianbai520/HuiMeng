CREATE TABLE bootstrap_lock (
  id INT PRIMARY KEY
);
INSERT INTO bootstrap_lock(id) VALUES (1);

CREATE TABLE users (
  id VARCHAR(128) PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
  platform_role VARCHAR(16) NOT NULL CHECK (platform_role IN ('ADMIN','USER')),
  auth_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE projects (
  id VARCHAR(128) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  workspace_key VARCHAR(128) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_by VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  FOREIGN KEY(created_by) REFERENCES users(id)
);
CREATE TABLE project_members (
  project_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  joined_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(project_id,user_id),
  FOREIGN KEY(project_id) REFERENCES projects(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE provider_secrets (
  provider_id VARCHAR(128) PRIMARY KEY,
  encrypted_value TEXT NOT NULL,
  key_version INT NOT NULL,
  updated_by VARCHAR(128) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  FOREIGN KEY(updated_by) REFERENCES users(id)
);
CREATE TABLE approved_models (
  provider_id VARCHAR(128) NOT NULL,
  model_id VARCHAR(128) NOT NULL,
  label VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL,
  capabilities TEXT NOT NULL,
  options_json TEXT NOT NULL,
  PRIMARY KEY(provider_id,model_id)
);
CREATE TABLE external_clients (
  id VARCHAR(128) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  project_id VARCHAR(128) NOT NULL,
  secret_hash VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
  created_by VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  revoked_at TIMESTAMP(6),
  FOREIGN KEY(project_id) REFERENCES projects(id),
  FOREIGN KEY(created_by) REFERENCES users(id)
);
CREATE TABLE external_grants (
  client_id VARCHAR(128) NOT NULL,
  project_id VARCHAR(128) NOT NULL,
  capability_type VARCHAR(16) NOT NULL CHECK (capability_type IN ('MCP','A2A')),
  capability_name VARCHAR(128) NOT NULL,
  PRIMARY KEY(client_id,capability_type,capability_name),
  FOREIGN KEY(client_id) REFERENCES external_clients(id),
  FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE generation_runs (
  id VARCHAR(128) PRIMARY KEY,
  project_id VARCHAR(128) NOT NULL,
  actor_type VARCHAR(16) NOT NULL,
  actor_id VARCHAR(128) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  capacity_held BOOLEAN NOT NULL DEFAULT TRUE,
  operation VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  upstream_ref VARCHAR(255),
  started_at TIMESTAMP(6) NOT NULL,
  ended_at TIMESTAMP(6),
  lease_expires_at TIMESTAMP(6),
  FOREIGN KEY(project_id) REFERENCES projects(id),
  UNIQUE(project_id,actor_type,actor_id,operation,idempotency_key)
);
CREATE INDEX runs_capacity ON generation_runs(project_id,capacity_held);
CREATE TABLE audit_events (
  id VARCHAR(128) PRIMARY KEY,
  actor_type VARCHAR(16) NOT NULL,
  actor_id VARCHAR(128),
  project_id VARCHAR(128),
  action VARCHAR(64) NOT NULL,
  target VARCHAR(128),
  result VARCHAR(32) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX audit_time ON audit_events(created_at,id);
CREATE INDEX audit_actor ON audit_events(actor_id,created_at);
CREATE TABLE user_preferences (
  user_id VARCHAR(128) PRIMARY KEY,
  preferences_json TEXT NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id)
);
