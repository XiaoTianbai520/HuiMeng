package cn.huimeng.dao.entity;

import java.time.Instant;

/** 市场目录条目：安装时按 {@code plugin_id} 回查，地址与已获批摘要都以此为准。 */
public record MarketCatalogDO(
    String pluginId,
    String type,
    String name,
    String displayName,
    String version,
    String fileName,
    String artifactUrl,
    String sha256,
    Instant updatedAt) {}
