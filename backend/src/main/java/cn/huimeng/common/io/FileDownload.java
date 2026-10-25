package cn.huimeng.common.io;

import java.io.InputStream;

/** 二进制响应适配数据；内容由 HTTP 转换器同步消费并关闭，不作 JSON 序列化。 */
public record FileDownload(
    int status, long length, String contentRange, String filename, InputStream content) {
  @Override
  public String toString() {
    return "FileDownload[stream]";
  }
}
