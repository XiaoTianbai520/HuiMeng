-- 市场目录缓存与获批制品清单：安装只认这里登记的地址与摘要，不接受客户端提供的 url/sha256。
CREATE TABLE market_catalog (
  plugin_id VARCHAR(128) PRIMARY KEY,
  type VARCHAR(16) NOT NULL,
  name VARCHAR(128) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  version VARCHAR(64) NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  artifact_url VARCHAR(1024) NOT NULL,
  sha256 CHAR(64),
  updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX market_catalog_type ON market_catalog(type);
