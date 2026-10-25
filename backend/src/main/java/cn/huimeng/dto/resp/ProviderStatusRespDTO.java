package cn.huimeng.dto.resp;

import java.time.Instant;

/** 供应商密钥配置状态；不暴露密文或明文。 */
public record ProviderStatusRespDTO(String providerId, boolean configured, Instant updatedAt) {}
