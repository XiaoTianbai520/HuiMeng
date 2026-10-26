package cn.huimeng.controller;

import cn.huimeng.dto.resp.ApiRespDTO;
import cn.huimeng.service.AgentService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * 成员可见的能力目录。本批只发布随部署内置的条目：远端节点包与工具界面尚未随服务一起分发， 因此返回空列表而不是 404，页面据此降级为内置展示。
 */
@RestController
public class CapabilityController {
  private final AgentService agents;

  public CapabilityController(AgentService agents) {
    this.agents = agents;
  }

  /** 远端节点包为空：内置节点的分发在后续批次随镜像一起提供。 */
  @GetMapping("/api/v1/capabilities/nodes/get")
  public ApiRespDTO<List<Map<String, Object>>> nodes() {
    return ApiRespDTO.ok(List.of());
  }

  /** 内置工具只通过服务端目录驱动模型，页面没有独立的工具安装项。 */
  @GetMapping("/api/v1/capabilities/tools/get")
  public ApiRespDTO<Map<String, Object>> tools() {
    return ApiRespDTO.ok(
        Map.of(
            "tools",
            List.of(
                Map.of(
                    "name",
                    "canvasOperations",
                    "label",
                    "画布操作",
                    "description",
                    "由页面执行的画布读取与控制工具",
                    "enabled",
                    true)),
            "canManage",
            false));
  }

  /** 没有定制工具界面：返回空列表让页面使用内置卡片，避免把缺项当成加载失败。 */
  @GetMapping("/api/v1/capabilities/tools/renderers")
  public ApiRespDTO<List<Map<String, Object>>> renderers() {
    return ApiRespDTO.ok(List.of());
  }

  @GetMapping("/api/v1/projects/{projectId}/creative/agent/skills")
  public ApiRespDTO<List<Map<String, Object>>> skills(@PathVariable String projectId) {
    // 技能包随插件市场安装（M3-08）；未安装时按空列表返回。
    agents.list(projectId);
    return ApiRespDTO.ok(List.of());
  }
}
