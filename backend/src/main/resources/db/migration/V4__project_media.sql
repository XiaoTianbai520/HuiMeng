-- Immutable media objects; workspace documents retain their original file format.
CREATE TABLE project_media (
  id VARCHAR(128) PRIMARY KEY,
  project_id VARCHAR(128) NOT NULL,
  object_key VARCHAR(512) NOT NULL UNIQUE,
  filename VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
  sha256 VARCHAR(64),
  state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','READY','FAILED','DELETING')),
  created_by VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  FOREIGN KEY(project_id) REFERENCES projects(id),
  FOREIGN KEY(created_by) REFERENCES users(id)
);
CREATE INDEX media_project_order ON project_media(project_id,created_at,id);
