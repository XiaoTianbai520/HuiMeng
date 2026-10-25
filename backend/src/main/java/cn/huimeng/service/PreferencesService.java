package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import com.fasterxml.jackson.databind.JsonNode;

/** 账号隔离的个人偏好；共享密钥和供应商配置不能写入此存储。 */
public interface PreferencesService {

  /** 只读取当前会话账号的偏好。 */
  JsonNode get();

  /** 校验结构、大小、嵌套深度和敏感字段后保存。 */
  void save(JsonNode input);
}
