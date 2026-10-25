package cn.huimeng.dto.resp;

/** 沿用兼容文件操作成功包；没有物理路径或内部句柄。 */
public record FileOperationRespDTO(int code, Object data, String message) {
  public static FileOperationRespDTO success() {
    return new FileOperationRespDTO(200, null, "success");
  }
}
