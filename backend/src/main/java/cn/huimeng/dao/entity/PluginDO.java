package cn.huimeng.dao.entity;

import java.time.Instant;

/** 已安装能力登记：类型+标识唯一，保存摘要、来源、配置与原始内容，用于导出与回归比对。 */
public record PluginDO(
    String id,
    String type,
    String name,
    String displayName,
    String version,
    String sha256,
    String source,
    String sourceUrl,
    boolean enabled,
    String configJson,
    String manifestJson,
    byte[] content,
    String installedBy,
    Instant createdAt,
    Instant updatedAt) {}
