package cn.huimeng.dto.resp;

/** 项目元数据视图；不暴露工作区路径或映射键。 */
public record ProjectRespDTO(String id, String name, String status) {}
