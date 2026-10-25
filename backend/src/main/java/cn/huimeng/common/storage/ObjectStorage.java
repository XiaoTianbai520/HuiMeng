package cn.huimeng.common.storage;

import java.io.InputStream;

/** S3 兼容存储边界；键仅由服务端生成，任何底层错误均转换为固定业务错误。 */
public interface ObjectStorage {
  default void requireAvailable() {}

  void put(String key, InputStream content, long length);

  StoredObject head(String key);

  InputStream get(String key, String etag, String range);

  void delete(String key);

  record StoredObject(long size, String etag) {}
}
