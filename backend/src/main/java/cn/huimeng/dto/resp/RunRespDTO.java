package cn.huimeng.dto.resp;

import java.time.Instant;

/** 发起人可见的运行状态；无提示词、上游标识和物理定位。 */
public record RunRespDTO(
    String id,
    String projectId,
    String kind,
    String state,
    boolean capacityHeld,
    Instant createdAt,
    Instant endedAt) {}
