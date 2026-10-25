package cn.huimeng.dto.resp;

import java.time.Instant;

/** 成员可见的媒体元数据；不含桶、对象键、端点或凭据。 */
public record ProjectMediaRespDTO(
    String id,
    String projectId,
    String filename,
    long byteSize,
    String sha256,
    Instant createdAt) {}
