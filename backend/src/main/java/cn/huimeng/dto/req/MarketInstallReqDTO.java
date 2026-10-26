package cn.huimeng.dto.req;

/** 市场安装：来源地址与摘要都由管理员显式给出，服务端再按允许清单校验。 */
public record MarketInstallReqDTO(
    String pluginId,
    String type,
    String name,
    String version,
    String url,
    String sha256,
    boolean force) {}
