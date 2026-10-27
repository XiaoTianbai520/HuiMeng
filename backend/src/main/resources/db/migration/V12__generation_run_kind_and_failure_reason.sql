-- kind 曾写死为 'image'，视频运行也被记成 image，按类型统计会失真；这里补受控失败原因列。
-- failure_reason 只存固定词表里的代码（如 MODEL_TIMEOUT），不存异常文本，避免把堆栈或上游细节带到客户端。
ALTER TABLE generation_runs ADD COLUMN failure_reason VARCHAR(48);

-- 历史数据只能从成功产物反推：视频产物文件名固定为 generated-<runId>.mp4。
-- 失败的视频运行没有产物，无法可靠还原，保持原值；kind 不参与状态机，只影响统计口径。
UPDATE generation_runs SET kind='video'
WHERE kind='image'
  AND EXISTS (
    SELECT 1 FROM project_media m
    WHERE m.filename = CONCAT('generated-', generation_runs.id, '.mp4')
  );
