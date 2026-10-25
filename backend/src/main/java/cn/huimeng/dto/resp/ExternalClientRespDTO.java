package cn.huimeng.dto.resp;

import java.time.Instant;
import java.util.List;

/** 外部客户端安全列表视图；不包含令牌或其哈希。 */
public record ExternalClientRespDTO(
    String id,
    String name,
    String projectId,
    Instant createdAt,
    Instant revokedAt,
    List<String> mcpTools,
    List<String> a2aTeams) {}
