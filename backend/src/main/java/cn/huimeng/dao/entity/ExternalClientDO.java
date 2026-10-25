package cn.huimeng.dao.entity;

import java.time.Instant;

/** 外部客户端元数据；列表查询不选择令牌哈希。 */
public record ExternalClientDO(
    String id, String name, String projectId, Instant createdAt, Instant revokedAt) {}
