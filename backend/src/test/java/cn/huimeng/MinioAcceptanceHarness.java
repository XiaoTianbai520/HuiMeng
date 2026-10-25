package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.storage.S3ObjectStorage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** 显式选择才连接专用开发桶；不读 root 身份，不碰团队媒体。 */
@EnabledIfEnvironmentVariable(named = "HM_MINIO_ACCEPTANCE", matches = "true")
class MinioAcceptanceHarness {
  @Test
  void dedicatedIdentitySupportsPrivateObjectsAndRejectsOtherScopes() throws Exception {
    Properties settings = new Properties();
    try (Reader reader =
        Files.newBufferedReader(Path.of(".runtime/object-storage/application.properties"))) {
      settings.load(reader);
    }
    assertThat(settings.getProperty("huimeng.storage.bucket")).isEqualTo("huimeng-dev-media");
    String bucket = settings.getProperty("huimeng.storage.bucket");
    String key = "projects/validation-" + UUID.randomUUID() + "/media/" + UUID.randomUUID();
    List<String> checks = new ArrayList<>();
    try (S3Client client =
        S3Client.builder()
            .endpointOverride(java.net.URI.create(settings.getProperty("huimeng.storage.endpoint")))
            .forcePathStyle(true)
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        settings.getProperty("huimeng.storage.access-key"),
                        settings.getProperty("huimeng.storage.secret-key"))))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()) {
      var storage = new S3ObjectStorage(client, bucket);
      byte[] bytes =
          "HuiMeng synthetic MinIO verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try {
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length);
        var object = storage.head(key);
        assertThat(object.size()).isEqualTo(bytes.length);
        checks.add("dedicatedPutAndHead");
        try (var stream = storage.get(key, object.etag(), null)) {
          assertThat(stream.readAllBytes()).isEqualTo(bytes);
        }
        checks.add("dedicatedGet");
        try (var stream = storage.get(key, object.etag(), "bytes=0-6")) {
          assertThat(stream.readAllBytes()).isEqualTo(Arrays.copyOfRange(bytes, 0, 7));
        }
        checks.add("singleRange");
        assertThatThrownBy(
                () ->
                    client.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*").build(),
                        software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes)))
            .isInstanceOfSatisfying(
                S3Exception.class, ex -> assertThat(ex.statusCode()).isEqualTo(412));
        checks.add("immutableCreateCondition");
        assertThatThrownBy(
                () -> client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()))
            .isInstanceOfSatisfying(
                S3Exception.class, ex -> assertThat(ex.statusCode()).isEqualTo(403));
        checks.add("bucketEnumerationDenied");
        assertThatThrownBy(
                () ->
                    client.headObject(
                        HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key("outside-project-prefix")
                            .build()))
            .isInstanceOfSatisfying(
                S3Exception.class, ex -> assertThat(ex.statusCode()).isEqualTo(403));
        checks.add("outsidePrefixDenied");
        assertThatThrownBy(
                () ->
                    client.headObject(
                        HeadObjectRequest.builder()
                            .bucket("huimeng-unrelated-denied")
                            .key("projects/probe")
                            .build()))
            .isInstanceOfSatisfying(
                S3Exception.class, ex -> assertThat(ex.statusCode()).isEqualTo(403));
        checks.add("otherBucketDenied");
        var url =
            java.net
                .URI
                .create(settings.getProperty("huimeng.storage.endpoint") + "/" + bucket + "/" + key)
                .toURL();
        var connection = (java.net.HttpURLConnection) url.openConnection();
        assertThat(connection.getResponseCode()).isEqualTo(403);
        connection.disconnect();
        checks.add("anonymousReadDenied");
      } finally {
        storage.delete(key);
      }
      checks.add("exactProbeObjectRemoved");
    }
    Files.writeString(
        Path.of("target/minio-policy-result.json"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of("passed", true, "checks", checks, "credentialValuesRecorded", false)));
  }
}
