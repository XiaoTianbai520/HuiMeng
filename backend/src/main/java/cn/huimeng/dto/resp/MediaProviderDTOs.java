package cn.huimeng.dto.resp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 自定义媒体供应商的管理面返回：只给声明出来的元数据，不下发来源文本。 */
public final class MediaProviderDTOs {

  /**
   * 一个已登记的供应商。
   *
   * @param executable 是否可用于生成。当前固定 false：本平台没有执行供应商代码的运行时， 面板据此禁用「生成」，避免把「已登记」误当成「可用」。
   */
  public record Provider(
      String fileName,
      String displayName,
      String version,
      String sha256,
      boolean executable,
      List<Model> models) {}

  /** 供应商声明的模型；保留原始声明以便后续执行器复用。 */
  public record Model(String id, String label, String type, JsonNode declaration) {}

  private MediaProviderDTOs() {}
}
