package cn.huimeng.dto.resp;

import java.util.List;

/** directory 是项目 ID，条目只含相对路径；保留旧列表响应层次。 */
public record FileListRespDTO(int code, Data data, String message) {
  public record Data(String directory, boolean empty, List<Entry> entries) {}

  public record Entry(String name, String path, String type) {}
}
