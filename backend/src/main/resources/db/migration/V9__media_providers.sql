-- 自定义媒体供应商登记：只保存来源与声明出来的模型清单，执行仍走内置供应商。
CREATE TABLE media_providers (
  file_name VARCHAR(72) PRIMARY KEY,
  display_name VARCHAR(200) NOT NULL,
  version VARCHAR(32) NOT NULL,
  source MEDIUMTEXT NOT NULL,
  models_json TEXT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
