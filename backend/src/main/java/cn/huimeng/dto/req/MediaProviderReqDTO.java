package cn.huimeng.dto.req;

import java.util.List;

/** 自定义媒体供应商的写入请求：add 用 source，save 用 models，delete/models 用 fileName。 */
public record MediaProviderReqDTO(String fileName, String source, List<Model> models) {

  /** 保存时提交的模型清单；只取 id/label/type，其余声明以登记时解析到的为准。 */
  public record Model(String id, String label, String type) {}
}
