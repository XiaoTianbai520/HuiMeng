package cn.huimeng.controller;

import cn.huimeng.dto.resp.*;
import cn.huimeng.service.PreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 仅操作会话账号偏好；动态 JSON 结构由服务层显式校验。 */
@RestController
@RequestMapping("/api/v1/preferences")
public class PreferencesController {
  private final PreferencesService preferences;

  public PreferencesController(PreferencesService preferences) {
    this.preferences = preferences;
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
}
