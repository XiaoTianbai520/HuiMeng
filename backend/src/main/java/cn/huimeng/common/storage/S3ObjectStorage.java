package cn.huimeng.common.storage;

import cn.huimeng.common.exception.ApiException;
import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** 私有桶读写；不生成绕开 Spring 授权的公开或预签名 URL。 */
public final class S3ObjectStorage implements ObjectStorage {
  private final S3Client client;
  private final String bucket;

  public S3ObjectStorage(S3Client client, String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, InputStream content, long length) {
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("application/octet-stream")
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromInputStream(content, length));
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  @Override
  public StoredObject head(String key) {
    try {
      var value = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return new StoredObject(value.contentLength(), value.eTag());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  @Override
  public InputStream get(String key, String etag, String range) {
    try {
      return client.getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).ifMatch(etag).range(range).build());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  @Override
  public void delete(String key) {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  public static ApiException unavailable() {
    return new ApiException(503, "EXECUTOR_UNAVAILABLE", "媒体存储暂不可用");
  }
}
