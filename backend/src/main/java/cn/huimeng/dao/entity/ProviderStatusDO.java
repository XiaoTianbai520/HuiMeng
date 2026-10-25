package cn.huimeng.dao.entity;

import java.time.Instant;

/** 只查询供应商配置元数据，不加载密文。 */
public record ProviderStatusDO(String providerId, Instant updatedAt) {}
