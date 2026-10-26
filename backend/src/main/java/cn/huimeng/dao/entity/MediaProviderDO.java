package cn.huimeng.dao.entity;

import java.time.Instant;

/** 自定义媒体供应商：来源与声明出来的模型清单。执行不在本表范围，生成仍走内置供应商。 */
public record MediaProviderDO(
    String fileName,
    String displayName,
    String version,
    String source,
    String modelsJson,
    String sha256,
    Instant createdAt,
    Instant updatedAt) {}
