package cn.huimeng.dto.req;

import java.util.Map;

/** 能力配置；秘密字段按字段名识别，写入后不再回显。 */
public record PluginConfigReqDTO(Map<String, Object> config) {}
