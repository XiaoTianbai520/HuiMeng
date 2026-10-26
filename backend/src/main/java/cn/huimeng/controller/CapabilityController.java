package cn.huimeng.controller;

import cn.huimeng.dto.resp.ApiRespDTO;
import cn.huimeng.dto.resp.CapabilityDTOs;
import cn.huimeng.service.CapabilityService;
import cn.huimeng.service.McpControlService;
import cn.huimeng.service.McpToolService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 成员可见的能力目录与脚本分发。
 *
 * <p>节点脚本由页面在同源加载并注册为 remote-&lt;name&gt;；工具界面按安装摘要取回。目录只发布已登记内容，秘密配置不下发。
 */
@RestController
public class CapabilityController {
  private static final MediaType JAVASCRIPT =
      new MediaType("text", "javascript", StandardCharsets.UTF_8);
  private final CapabilityService capabilities;
  private final McpControlService control;
  private final McpToolService mcpTools;

  public CapabilityController(
      CapabilityService capabilities, McpControlService control, McpToolService mcpTools) {
    this.capabilities = capabilities;
    this.control = control;
    this.mcpTools = mcpTools;
  }

  /** 已安装节点；未安装时返回空列表，页面据此保持内置菜单。 */
  @GetMapping("/api/v1/capabilities/nodes/get")
  public ApiRespDTO<List<CapabilityDTOs.NodeItem>> nodes() {
    return ApiRespDTO.ok(capabilities.nodes());
  }

  /** 节点脚本正文；按 JavaScript 下发，禁止浏览器猜测类型。 */
  @GetMapping("/api/v1/capabilities/nodes/files")
  public ResponseEntity<byte[]> nodeScript(@RequestParam String name) {
    return script(capabilities.nodeScript(name));
  }

  /** 内置画布工具与已安装工具的统一目录。 */
  @GetMapping("/api/v1/capabilities/tools/get")
  public ApiRespDTO<CapabilityDTOs.ToolCatalog> tools() {
    return ApiRespDTO.ok(capabilities.tools());
  }

  /** 已启用的工具界面脚本清单；空列表让页面使用内置卡片。 */
  @GetMapping("/api/v1/capabilities/tools/renderers")
  public ApiRespDTO<List<CapabilityDTOs.RendererItem>> renderers() {
    return ApiRespDTO.ok(capabilities.renderers());
  }

  /** 工具界面脚本；版本必须等于安装摘要。 */
  @GetMapping("/api/v1/capabilities/tools/client")
  public ResponseEntity<byte[]> toolClient(
      @RequestParam String name, @RequestParam String version) {
    return script(capabilities.toolClient(name, version));
  }

  /** 外部协议状态：只公布协议版本、连接数与工具总数，不含凭证或项目标识。 */
  @GetMapping("/api/v1/capabilities/mcp/status")
  public ApiRespDTO<Map<String, Object>> mcpStatus() {
    return ApiRespDTO.ok(
        Map.of(
            "enabled",
            true,
            "protocolVersion",
            "2025-06-18",
            "connections",
            control.connections(),
            "tools",
            mcpTools.totalTools()));
  }

  @GetMapping("/api/v1/capabilities/mcp/skill")
  public ApiRespDTO<Map<String, Object>> mcpSkills() {
    return ApiRespDTO.ok(Map.of("skills", capabilities.skills()));
  }

  @GetMapping("/api/v1/projects/{projectId}/creative/agent/skills")
  public ApiRespDTO<List<CapabilityDTOs.SkillItem>> skills(@PathVariable String projectId) {
    return ApiRespDTO.ok(capabilities.agentSkills(projectId));
  }

  private static ResponseEntity<byte[]> script(CapabilityDTOs.Script script) {
    return ResponseEntity.ok()
        .contentType(JAVASCRIPT)
        .cacheControl(CacheControl.noCache())
        .header("X-Content-Type-Options", "nosniff")
        .eTag("\"" + script.etag() + "\"")
        .body(script.content());
  }
}
