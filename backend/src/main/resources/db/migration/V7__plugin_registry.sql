-- 已安装能力登记、文档型插件内容与市场收藏。
CREATE TABLE plugins (
  id VARCHAR(200) PRIMARY KEY,
  type VARCHAR(16) NOT NULL,
  name VARCHAR(128) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  version VARCHAR(64) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_url VARCHAR(1024),
  enabled BOOLEAN NOT NULL,
  config_json TEXT,
  manifest_json TEXT NOT NULL,
  content BLOB,
  installed_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT plugins_type_name UNIQUE (type, name)
);
CREATE INDEX plugins_type ON plugins(type, name);

CREATE TABLE plugin_documents (
  plugin_id VARCHAR(200) NOT NULL,
  path VARCHAR(512) NOT NULL,
  content TEXT NOT NULL,
  PRIMARY KEY (plugin_id, path)
);

CREATE TABLE market_collections (
  plugin_id VARCHAR(128) PRIMARY KEY,
  created_at TIMESTAMP(6) NOT NULL
);

-- 团队级文档（个性化说明、本地记忆、A2A 团队设置）。
CREATE TABLE app_documents (
  name VARCHAR(64) PRIMARY KEY,
  content TEXT NOT NULL,
  revision VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
