package cn.huimeng.controller;

import cn.huimeng.dto.req.PersonalizationReqDTO;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.DocumentService;
import cn.huimeng.service.PreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 仅操作会话账号偏好；团队级说明文档单独走 personalization 子路径。 */
@RestController
@RequestMapping("/api/v1/preferences")
public class PreferencesController {
  private final PreferencesService preferences;
  private final DocumentService documents;

  public PreferencesController(PreferencesService preferences, DocumentService documents) {
    this.preferences = preferences;
    this.documents = documents;
  }

  /** 读取会话账号的个人偏好。 */
  @GetMapping
  public JsonNode get() {
    return preferences.get();
  }

  /** 校验并保存个人偏好；不接收共享秘密。 */
  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void save(@RequestBody JsonNode input) {
    preferences.save(input);
  }

  /** 读取个性化说明或本地记忆；按内容摘要返回修订号。 */
  @GetMapping("/personalization")
  public ApiRespDTO<PluginDTOs.Document> personalization(@RequestParam String document) {
    var stored = documents.read(document);
    return ApiRespDTO.ok(new PluginDTOs.Document(stored.content(), stored.revision()));
  }

  /** 保存个性化说明；修订号不匹配时返回 409，页面提示重新加载。 */
  @PutMapping("/personalization")
  public ApiRespDTO<PluginDTOs.Document> savePersonalization(
      @RequestBody PersonalizationReqDTO input, HttpServletRequest request) {
    var saved =
        documents.save(
            input.document(), input.content(), input.revision(), RequestContext.requestId(request));
    return ApiRespDTO.ok(new PluginDTOs.Document(saved.content(), saved.revision()));
  }
}
