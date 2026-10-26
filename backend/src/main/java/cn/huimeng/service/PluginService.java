package cn.huimeng.service;

import cn.huimeng.dto.resp.PluginDTOs;
import cn.huimeng.dto.resp.PluginRespDTO;
import java.util.List;
import java.util.Map;

/** 能力登记与插件市场：安装前校验来源/摘要/归档路径，配置中的秘密字段只写不读。 */
public interface PluginService {
  List<PluginRespDTO> installed();

  /** 从上传文件安装；同名同版本同摘要幂等，版本变化需 force 明确覆盖。 */
  PluginRespDTO installFile(
      String type, String fileName, byte[] content, boolean force, String requestId);

  /**
   * 从市场安装：只接受目录条目标识，地址与摘要由服务端登记表回查，从不采信客户端给的 url/sha256。
   *
   * <p>首次成功下载即记录获批摘要，之后同一制品必须与之一致。
   */
  PluginRespDTO installMarket(String pluginId, boolean force, String requestId);

  PluginRespDTO config(
      String type, String name, java.util.Map<String, Object> config, String requestId);

  PluginRespDTO enabled(String type, String name, boolean enabled, String requestId);

  /** 导出原始内容；文档型能力打包为 zip。 */
  Export export(String type, String name);

  void uninstall(String type, String name, String requestId);

  /** 文档型能力的文本内容（技能、Agent 包）。 */
  Map<String, String> documents(String type, String name);

  PluginRespDTO saveDocument(
      String type, String name, String path, String content, String requestId);

  PluginRespDTO createDocument(String type, String name, String path, String requestId);

  PluginRespDTO moveDocument(
      String type, String name, String path, String target, String requestId);

  PluginRespDTO orderDocuments(String type, String name, List<String> order, String requestId);

  /** 市场目录：团队未配置凭据时明确拒绝，上游故障时保留已安装能力可用。 */
  PluginDTOs.MarketPage search(int page, int limit, String type, String keyword);

  boolean toggleCollection(String pluginId);

  record Export(String fileName, String contentType, byte[] content) {}
}
