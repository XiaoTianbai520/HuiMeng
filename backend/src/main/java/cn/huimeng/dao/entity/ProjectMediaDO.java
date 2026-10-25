package cn.huimeng.dao.entity;

import java.time.Instant;

/** 内部对象键不允许直接序列化为 HTTP 响应。 */
public record ProjectMediaDO(
    String id,
    String projectId,
    String objectKey,
    String filename,
    long byteSize,
    String sha256,
    String state,
    String createdBy,
    Instant createdAt) {}
