-- 内置节点包与市场制品的实际体积远超 MySQL BLOB/TEXT 的 64 KiB 上限：
-- 8 个内置节点产物单个 263 KiB–1.7 MiB，最大市场工具 3.8 MiB；技能正文与 Agent 文档也可能超过 64 KiB。
-- H2 的 BLOB/TEXT 没有该上限，因此既有测试无法暴露，只有真实 MySQL 上登记内置节点时才会失败。
ALTER TABLE plugins MODIFY COLUMN content MEDIUMBLOB;
ALTER TABLE plugins MODIFY COLUMN manifest_json MEDIUMTEXT;
ALTER TABLE plugin_documents MODIFY COLUMN content MEDIUMTEXT;
ALTER TABLE app_documents MODIFY COLUMN content MEDIUMTEXT;
