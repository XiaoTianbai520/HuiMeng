package cn.huimeng.dao.entity;

/** 登记模型的数据库记录；JSON 在业务层解释。 */
public record ModelDO(
    String providerId,
    String modelId,
    String label,
    boolean enabled,
    String capabilitiesJson,
    String optionsJson) {}
