package cn.huimeng.dao.entity;

import java.time.Instant;

/** A2A 任务归属与终态；session 文件用于取消项目 Agent 运行。 */
public record A2aTaskDO(
    String id,
    String projectId,
    String clientId,
    String team,
    String contextId,
    String sessionFile,
    String state,
    String prompt,
    String resultText,
    String error,
    Instant createdAt,
    Instant updatedAt) {}
