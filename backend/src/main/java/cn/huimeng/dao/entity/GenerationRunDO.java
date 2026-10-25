package cn.huimeng.dao.entity;

import java.time.Instant;

/** 持久化名额和幂等信息；仅内部使用。 */
public record GenerationRunDO(
    String id,
    String projectId,
    String actorId,
    String state,
    boolean capacityHeld,
    String requestHash,
    String upstreamRef,
    Instant createdAt,
    Instant endedAt) {}
