package cn.huimeng.dto.resp;

import java.util.List;
import java.util.Map;

/** 成员可见的能力目录：只暴露标识、展示名、脚本地址与非秘密配置，不返回原始安装包。 */
public final class CapabilityDTOs {
  private CapabilityDTOs() {}

  /** 节点条目；{@code url} 指向同源脚本，页面加载后注册为 remote-&lt;name&gt;。 */
  public record NodeItem(
      String name, String displayName, String url, boolean enabled, Map<String, Object> config) {}

  /** 工具条目；内置工具与已安装工具共用同一展示结构。 */
  public record ToolItem(String name, String label, String description, boolean enabled) {}

  public record ToolCatalog(List<ToolItem> tools, boolean canManage) {}

  /** 工具界面脚本；{@code tools} 声明该脚本导出的组件名。 */
  public record RendererItem(String name, List<String> tools, String url) {}

  public record SkillItem(String name, String displayName, String version) {}

  /** 脚本正文与内容摘要；摘要同时作为缓存版本。 */
  public record Script(byte[] content, String etag) {}
}
