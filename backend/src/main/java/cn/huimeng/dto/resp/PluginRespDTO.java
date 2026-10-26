package cn.huimeng.dto.resp;

import java.util.List;
import java.util.Map;

/** 已安装能力的安全视图：不含原始内容与秘密字段，只给页面展示与操作所需字段。 */
public record PluginRespDTO(
    String type,
    String name,
    String displayName,
    String author,
    String description,
    String version,
    boolean enabled,
    String source,
    boolean canConfigure,
    boolean canManage,
    boolean isCollected,
    boolean installable,
    String kind,
    Map<String, Object> manifest,
    Map<String, Object> config,
    List<String> documents,
    String loadError) {}
