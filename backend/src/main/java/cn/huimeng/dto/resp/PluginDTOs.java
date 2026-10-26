package cn.huimeng.dto.resp;

import java.util.List;
import java.util.Map;

/** 插件市场、技能/Agent 文档与媒体供应商的响应模型；控制器只返回这些显式结构。 */
public final class PluginDTOs {
  private PluginDTOs() {}

  public record Named(String name) {}

  public record Installed(List<PluginRespDTO> items) {}

  public record Skilled(String mainPath, List<String> files) {}

  public record Document(String content, String revision) {}

  public record AgentFile(String path, String content) {}

  public record AgentFiles(List<AgentFile> files, String readme) {}

  public record MarketItem(
      String id,
      String type,
      String name,
      String displayName,
      String author,
      String description,
      String version,
      String fileName,
      boolean isCollected,
      boolean installable,
      Map<String, Object> manifest) {}

  public record MarketPage(List<MarketItem> items, int total, boolean configured) {}

  public record Collected(boolean collected) {}

  public record MediaProvider(
      String id,
      String label,
      String apiUrl,
      boolean hasKey,
      List<String> models,
      boolean builtin) {}
}
