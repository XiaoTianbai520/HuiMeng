package cn.huimeng.config;

import cn.huimeng.common.storage.*;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 默认关闭；仅显式配置时使用专用应用凭据，禁止回退到系统默认 AWS 身份。 */
@Configuration
public class ObjectStorageConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "huimeng.storage.enabled", havingValue = "true")
  S3Client mediaS3Client(
      @Value("${huimeng.storage.endpoint}") String endpoint,
      @Value("${huimeng.storage.access-key}") String access,
      @Value("${huimeng.storage.secret-key}") String secret,
      @Value("${huimeng.storage.region:us-east-1}") String region) {
    URI uri = URI.create(endpoint);
    if (!java.util.Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")
        || access.isBlank()
        || secret.isBlank()) throw new IllegalArgumentException("媒体存储配置无效");
    return S3Client.builder()
        .endpointOverride(uri)
        .forcePathStyle(true)
        .region(Region.of(region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(60)))
        .overrideConfiguration(
            c ->
                c.apiCallTimeout(Duration.ofMinutes(10))
                    .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()))
        .build();
  }

  @Bean
  @ConditionalOnProperty(name = "huimeng.storage.enabled", havingValue = "true")
  ObjectStorage objectStorage(S3Client client, @Value("${huimeng.storage.bucket}") String bucket) {
    if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
      throw new IllegalArgumentException("媒体桶配置无效");
    return new S3ObjectStorage(client, bucket);
  }

  @Bean
  @ConditionalOnProperty(
      name = "huimeng.storage.enabled",
      havingValue = "false",
      matchIfMissing = true)
  ObjectStorage disabledObjectStorage() {
    return new ObjectStorage() {
      public void requireAvailable() {
        throw S3ObjectStorage.unavailable();
      }

      public void put(String k, InputStream in, long size) {
        throw S3ObjectStorage.unavailable();
      }

      public StoredObject head(String k) {
        throw S3ObjectStorage.unavailable();
      }

      public InputStream get(String k, String etag, String range) {
        throw S3ObjectStorage.unavailable();
      }

      public void delete(String k) {
        throw S3ObjectStorage.unavailable();
      }
    };
  }
}
