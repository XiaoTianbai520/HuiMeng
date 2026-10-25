package cn.huimeng.dto.resp;

import java.time.Instant;
import java.util.List;

/** 团队管理配置的安全导出，仅含明确允许的状态与模型批准元数据。 */
public record ConfigurationExportRespDTO(
    int version,
    Instant exportedAt,
    List<ProviderStatusRespDTO> providers,
    boolean marketConfigured,
    List<ExportedModelRespDTO> models) {}
