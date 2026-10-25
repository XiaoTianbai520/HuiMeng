package cn.huimeng.common.exception;

/** 仅公开当前字节修订摘要，绝不携带文件内容或服务器路径。 */
public final class RevisionConflictException extends ApiException {
  public final String currentRevision;

  public RevisionConflictException(String currentRevision) {
    super(409, "REVISION_CONFLICT", "文件已变化，请重新读取后处理；本次内容未保存");
    this.currentRevision = currentRevision;
  }
}
