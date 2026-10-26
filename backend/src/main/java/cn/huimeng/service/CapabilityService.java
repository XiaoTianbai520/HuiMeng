package cn.huimeng.service;

import cn.huimeng.dto.resp.CapabilityDTOs;
import java.util.List;

/** 成员可见的能力目录与脚本分发；只读取已登记且已启用的能力。 */
public interface CapabilityService {
  /** 已安装节点（含停用项，页面据 enabled 过滤）。 */
  List<CapabilityDTOs.NodeItem> nodes();

  /** 节点脚本；仅在已安装、已启用且类型匹配时返回。 */
  CapabilityDTOs.Script nodeScript(String name);

  /** 内置画布工具与已安装工具的统一目录。 */
  CapabilityDTOs.ToolCatalog tools();

  /** 已启用的工具界面脚本清单。 */
  List<CapabilityDTOs.RendererItem> renderers();

  /** 工具界面脚本正文；版本必须与安装摘要一致。 */
  CapabilityDTOs.Script toolClient(String name, String version);

  /** 全局技能包目录。 */
  List<CapabilityDTOs.SkillItem> skills();

  /** 项目内可见的技能包目录；先校验项目成员身份。 */
  List<CapabilityDTOs.SkillItem> agentSkills(String projectId);
}
