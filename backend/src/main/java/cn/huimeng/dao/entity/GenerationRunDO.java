package cn.huimeng.dao.entity;

import java.time.Instant;

/** 持久化名额和幂等信息；仅内部使用。 */
public record GenerationRunDO(
    String id,
    String projectId,
    String actorId,
    String kind,
    String state,
    boolean capacityHeld,
    String requestHash,
    String upstreamRef,
    String failureReason,
    Instant createdAt,
    Instant endedAt) {}
