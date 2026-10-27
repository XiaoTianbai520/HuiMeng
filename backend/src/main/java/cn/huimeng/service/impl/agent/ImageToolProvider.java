package cn.huimeng.service.impl.agent;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.GenerateImageMediaDTO;
import cn.huimeng.dto.req.GenerateImageReqDTO;
import cn.huimeng.service.MediaGenerationService;
import cn.huimeng.service.impl.AgentToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 内部生图：Agent 直接占一个项目名额并在服务端执行，不需要页面参与。 */
@Component
@Order(20)
public class ImageToolProvider implements ToolProvider {
  private static final String PROVIDER = "qwenImage21Local";
  private static final String TEXT_MODEL = "qwenImage21Text";
  private static final String EDIT_MODEL = "qwenImage21Edit";

  private final AgentToolCatalog catalog;
  private final MediaGenerationService media;
  private final ObjectMapper json;

  public ImageToolProvider(
      AgentToolCatalog catalog, MediaGenerationService media, ObjectMapper json) {
    this.catalog = catalog;
    this.media = media;
    this.json = json;
  }

  @Override
  public String id() {
    return "image";
  }

  @Override
  public List<Map<String, Object>> definitions(ToolSession session) {
    return catalog.definition(AgentToolCatalog.GENERATE_IMAGE);
  }

  @Override
  public boolean owns(String name) {
    return AgentToolCatalog.GENERATE_IMAGE.equals(name);
  }

  @Override
  public JsonNode invoke(String name, String callId, JsonNode args, ToolSession session) {
    var generated =
        media.generateInternal(
            session.projectId(), imageRequest(args), "agent-" + callId, "agent-" + callId);
    var result = json.createObjectNode();
    result.put("path", generated.path());
    result.put("mimeType", generated.mimeType());
    result.put("spec", generated.spec());
    return result;
  }

  private GenerateImageReqDTO imageRequest(JsonNode args) {
    String prompt = args.path("prompt").asText("");
    if (prompt.isBlank() || prompt.length() > 8000) throw ApiException.invalid();
    boolean edit = "edit".equals(args.path("model").asText("text"));
    String reference = args.path("referencePath").asText("");
    List<GenerateImageMediaDTO> images = null;
    if (edit) {
      if (!reference.matches("^\\.huimeng-media/[a-f0-9-]{36}\\.png$"))
        throw new ApiException(400, "INVALID_ARGUMENT", "单图编辑必须给出项目内的 PNG 参考图路径");
      images = List.of(new GenerateImageMediaDTO(reference, "image/png"));
    }
    return new GenerateImageReqDTO(
        PROVIDER,
        edit ? EDIT_MODEL : TEXT_MODEL,
        "image",
        prompt,
        args.path("size").asText(null),
        args.path("ratio").asText(null),
        images,
        null);
  }
}
