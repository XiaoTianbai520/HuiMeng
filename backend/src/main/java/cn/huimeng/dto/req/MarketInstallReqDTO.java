package cn.huimeng.dto.req;

/** 市场安装：客户端只给目录标识，下载地址与获批摘要都由服务端登记表决定。 */
public record MarketInstallReqDTO(String pluginId, boolean force) {}
