package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.*;
import cn.huimeng.common.storage.*;
import cn.huimeng.dao.entity.ProjectMediaDO;
import cn.huimeng.dao.mapper.ProjectMediaMapper;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.*;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 先登记 PENDING 再上传；仅 READY 可见，跨 DB/S3 失败保留可核对状态。 */
@Service
public class ProjectMediaServiceImpl implements ProjectMediaService {
  private static final long MAX_UPLOAD = 512L * 1024 * 1024;
  private final ProjectService projects;
  private final ProjectMediaMapper media;
  private final ObjectStorage storage;
  private final AuditService audit;
  private final TransactionTemplate transactions;

  public ProjectMediaServiceImpl(
      ProjectService projects,
      ProjectMediaMapper media,
      ObjectStorage storage,
      AuditService audit,
      PlatformTransactionManager manager) {
    this.projects = projects;
    this.media = media;
    this.storage = storage;
    this.audit = audit;
    transactions = new TransactionTemplate(manager);
  }

  private ProjectMediaRespDTO response(ProjectMediaDO value) {
    return new ProjectMediaRespDTO(
        value.id(),
        value.projectId(),
        value.filename(),
        value.byteSize(),
        value.sha256(),
        value.createdAt());
  }

  @Override
  public ProjectMediaRespDTO upload(
      String project, String filename, long length, InputStream content, String requestId) {
    projects.requireMember(project, true);
    if (filename == null
        || filename.isBlank()
        || filename.length() > 255
        || filename.equals(".")
        || filename.equals("..")
        || filename.chars().anyMatch(c -> c < 32 || c == 127 || "/\\:%".indexOf(c) >= 0))
      throw ApiException.invalid();
    if (length < 0) throw new ApiException(400, "INVALID_ARGUMENT", "上传必须提供 Content-Length");
    if (length > MAX_UPLOAD) throw new ApiException(413, "UPLOAD_TOO_LARGE", "媒体超过上传大小限制");
    String id = UUID.randomUUID().toString(), key = "projects/" + project + "/media/" + id;
    String actor = RequestContext.actor().id();
    Instant createdAt = Instant.now();
    media.insert(id, project, key, filename, length, actor, createdAt);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      var input = new CountedDigestInputStream(content, digest);
      storage.put(key, input, length);
      // HTTP Content-Length 限定输入；SDK 完整消费后才可以发布记录。
      if (input.count != length || input.read() != -1) throw ApiException.invalid();
      projects.requireMember(project, true);
      String hash = HexFormat.of().formatHex(digest.digest());
      transactions.executeWithoutResult(
          tx -> {
            if (media.ready(project, id, hash) != 1) throw S3ObjectStorage.unavailable();
            audit.record(actor, project, "MEDIA_UPLOAD", id, "SUCCESS", requestId);
          });
      return new ProjectMediaRespDTO(id, project, filename, length, hash, createdAt);
    } catch (Exception ex) {
      // 远端超时也可能已写入；始终尝试精确键补偿，禁止清桶或批量删除。
      try {
        storage.delete(key);
      } catch (RuntimeException ignored) {
      }
      media.fail(project, id);
      if (ex instanceof ApiException api) throw api;
      throw S3ObjectStorage.unavailable();
    }
  }

  private static final class CountedDigestInputStream extends DigestInputStream {
    private long count;

    private CountedDigestInputStream(InputStream in, MessageDigest digest) {
      super(in, digest);
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value != -1) count++;
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int size = super.read(buffer, offset, length);
      if (size > 0) count += size;
      return size;
    }

    @Override
    public boolean markSupported() {
      return false;
    }

    @Override
    public synchronized void reset() throws IOException {
      throw new IOException("Upload stream is not rewindable");
    }
  }

  @Override
  public PageRespDTO<ProjectMediaRespDTO> list(String project, String cursor, int limit) {
    projects.requireMember(project, false);
    var page = media.list(project, cursor, limit);
    return new PageRespDTO<>(page.items().stream().map(this::response).toList(), page.nextCursor());
  }

  @Override
  public FileDownload download(String project, String id, String range) {
    projects.requireMember(project, false);
    ProjectMediaDO value = media.find(project, id);
    if (value == null || !value.state().equals("READY"))
      throw new ApiException(404, "NOT_FOUND", "媒体不可用");
    var object = storage.head(value.objectKey());
    if (object.size() != value.byteSize()) throw S3ObjectStorage.unavailable();
    var requested = ByteRange.parse(range, object.size());
    if (!requested.valid())
      return new FileDownload(
          416, 0, "bytes */" + object.size(), value.filename(), InputStream.nullInputStream());
    String contentRange =
        requested.partial()
            ? "bytes "
                + requested.start()
                + "-"
                + (requested.start() + requested.length() - 1)
                + "/"
                + object.size()
            : null;
    String storageRange =
        requested.partial()
            ? "bytes=" + requested.start() + "-" + (requested.start() + requested.length() - 1)
            : null;
    return new FileDownload(
        requested.partial() ? 206 : 200,
        requested.length(),
        contentRange,
        value.filename(),
        storage.get(value.objectKey(), object.etag(), storageRange));
  }

  @Override
  public void delete(String project, String id, String requestId) {
    projects.requireMember(project, true);
    ProjectMediaDO value = media.find(project, id);
    if (value == null) throw new ApiException(404, "NOT_FOUND", "媒体不可用");
    if (media.deleting(project, id) != 1)
      throw new ApiException(409, "REVISION_CONFLICT", "媒体尚在上传");
    storage.delete(value.objectKey());
    transactions.executeWithoutResult(
        tx -> {
          media.remove(project, id);
          audit.record(
              RequestContext.actor().id(), project, "MEDIA_DELETE", id, "SUCCESS", requestId);
        });
  }

  @Override
  public void discardGenerated(String project, String id, String creator, String requestId) {
    var value = media.find(project, id);
    if (value == null || !value.createdBy().equals(creator)) throw ApiException.forbidden();
    if (media.deleting(project, id) != 1) throw S3ObjectStorage.unavailable();
    storage.delete(value.objectKey());
    transactions.executeWithoutResult(
        tx -> {
          media.remove(project, id);
          audit.record(creator, project, "MEDIA_DISCARD", id, "SUCCESS", requestId);
        });
  }
}
