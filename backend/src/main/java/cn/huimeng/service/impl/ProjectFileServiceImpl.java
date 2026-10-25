package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.*;
import cn.huimeng.dto.req.FileWriteReqDTO;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.ProjectFileService;
import cn.huimeng.service.ProjectMediaService;
import cn.huimeng.service.ProjectService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** 成员授权后仍以目录句柄逐层打开；不支持该能力的平台明确拒绝，不降级为路径预检。 */
@Service
public class ProjectFileServiceImpl implements ProjectFileService {
  private static final long MAX_SNAPSHOT = 8L * 1024 * 1024;
  private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
  private final ProjectService projects;
  private final ProjectMediaService media;
  private final AuditService audit;

  public ProjectFileServiceImpl(
      ProjectService projects, ProjectMediaService media, AuditService audit) {
    this.projects = projects;
    this.media = media;
    this.audit = audit;
  }

  @Override
  public WriteResultRespDTO write(
      String projectId, FileWriteReqDTO input, java.io.InputStream content, String requestId) {
    Path target = projects.resolve(projectId, input.path(), true);
    if (input.path().length() > 2048
        || java.util.Arrays.stream(input.path().split("/")).anyMatch(p -> p.startsWith(".huimeng-"))
        || input.exclusive() == (input.expectedRevision() != null)
        || input.expectedRevision() != null && !input.expectedRevision().matches("[a-f0-9]{64}")
        || input.encoding() != null
            && !java.util.Set.of("utf-8", "utf-8-bom", "utf-16le", "utf-16be")
                .contains(input.encoding())
        || input.byteLength() < 0) throw ApiException.invalid();
    if (input.byteLength() > MAX_SNAPSHOT)
      throw new ApiException(413, "INVALID_ARGUMENT", "文件超过本批保存上限");
    try {
      byte[] bytes = content.readNBytes((int) input.byteLength() + 1);
      if (bytes.length != input.byteLength()) throw ApiException.invalid();
      if (input.encoding() != null) {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        Charset charset =
            switch (input.encoding()) {
              case "utf-16le" -> StandardCharsets.UTF_16LE;
              case "utf-16be" -> StandardCharsets.UTF_16BE;
              default -> StandardCharsets.UTF_8;
            };
        byte[] bom =
            switch (input.encoding()) {
              case "utf-8-bom" -> new byte[] {(byte) 239, (byte) 187, (byte) 191};
              case "utf-16le" -> new byte[] {(byte) 255, (byte) 254};
              case "utf-16be" -> new byte[] {(byte) 254, (byte) 255};
              default -> new byte[0];
            };
        ByteBuffer encoded =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text));
        bytes = new byte[bom.length + encoded.remaining()];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        encoded.get(bytes, bom.length, encoded.remaining());
      }
      if (bytes.length > MAX_SNAPSHOT)
        throw new ApiException(413, "INVALID_ARGUMENT", "编码后文件超过本批保存上限");
      String actorId = RequestContext.actor().id();
      String revision =
          SecureFileWriter.write(
              target,
              bytes,
              input.expectedRevision(),
              input.exclusive(),
              () -> {
                projects.requireMember(projectId, true);
                audit.record(actorId, projectId, "FILE_WRITE", null, "ATTEMPT", requestId);
              });
      audit.record(actorId, projectId, "FILE_WRITE", revision, "SUCCESS", requestId);
      return new WriteResultRespDTO(200, new WriteResultRespDTO.Revision(revision), "success");
    } catch (CharacterCodingException ex) {
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "写入正文不是有效 UTF-8 文本");
    } catch (UnsupportedOperationException | AtomicMoveNotSupportedException ex) {
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "当前文件系统不支持安全原子保存");
    } catch (NoSuchFileException ex) {
      throw new ApiException(404, "NOT_FOUND", "父目录不可用");
    } catch (IOException ex) {
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "文件保存未确认，请重新读取后核对");
    }
  }

  private SecureFileReader.OpenFile open(
      String projectId, String relative, Long maxBytes, long cap) {
    Path path = projects.resolve(projectId, relative, false);
    if (relative.length() > 2048 || maxBytes != null && (maxBytes < 1 || maxBytes > cap))
      throw ApiException.invalid();
    SecureFileReader.OpenFile file = null;
    try {
      file = SecureFileReader.open(path);
      if (file.size() > (maxBytes == null ? cap : maxBytes)) {
        file.close();
        throw new ApiException(400, "INVALID_ARGUMENT", "文件超过本次读取大小限制");
      }
      return file;
    } catch (UnsupportedOperationException ex) {
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "当前文件系统不支持安全目录句柄");
    } catch (NoSuchFileException ex) {
      throw new ApiException(404, "NOT_FOUND", "文件不可用");
    } catch (IOException ex) {
      if (file != null)
        try {
          file.close();
        } catch (IOException ignored) {
        }
      throw new ApiException(400, "PATH_NOT_ALLOWED", "文件路径不被允许");
    }
  }

  @Override
  public SnapshotResultRespDTO snapshot(
      String projectId, String path, Long maxBytes, String range) {
    if (path != null && path.startsWith(".huimeng-media/")) {
      projects.requireMember(projectId, false);
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "媒体不提供文本快照");
    }
    if (range != null) throw ApiException.invalid();
    try (var file = open(projectId, path, maxBytes, MAX_SNAPSHOT)) {
      byte[] bytes = file.readAllBytes();
      int offset = 0;
      String encoding = "utf-8";
      Charset charset = StandardCharsets.UTF_8;
      if (bytes.length >= 3
          && (bytes[0] & 255) == 239
          && (bytes[1] & 255) == 187
          && (bytes[2] & 255) == 191) {
        offset = 3;
        encoding = "utf-8-bom";
      } else if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) {
        offset = 2;
        encoding = "utf-16le";
        charset = StandardCharsets.UTF_16LE;
      } else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) {
        offset = 2;
        encoding = "utf-16be";
        charset = StandardCharsets.UTF_16BE;
      }
      String text =
          charset
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
              .toString();
      String revision =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return new SnapshotResultRespDTO(
          200, new SnapshotRespDTO(text, encoding, revision), "success");
    } catch (CharacterCodingException ex) {
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "文件不是支持的文本编码");
    } catch (IOException ex) {
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "文件读取未完成");
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  @Override
  public FileDownload download(String projectId, String path, Long maxBytes, String range) {
    if (path != null && path.startsWith(".huimeng-media/")) {
      projects.requireMember(projectId, false);
      var matcher =
          java.util.regex.Pattern.compile("^\\.huimeng-media/([a-f0-9-]{36})\\.png$").matcher(path);
      if (!matcher.matches()) throw new ApiException(400, "PATH_NOT_ALLOWED", "媒体路径无效");
      if (maxBytes != null) throw ApiException.invalid();
      return media.download(projectId, matcher.group(1), range);
    }
    var file = open(projectId, path, maxBytes, MAX_DOWNLOAD);
    try {
      var requested = ByteRange.parse(range, file.size());
      String filename = Path.of(path).getFileName().toString();
      if (!requested.valid()) {
        long size = file.size();
        file.close();
        return new FileDownload(
            416, 0, "bytes */" + size, filename, java.io.InputStream.nullInputStream());
      }
      file.range(requested.start(), requested.length());
      String contentRange =
          requested.partial()
              ? "bytes "
                  + requested.start()
                  + "-"
                  + (requested.start() + requested.length() - 1)
                  + "/"
                  + file.size()
              : null;
      return new FileDownload(
          requested.partial() ? 206 : 200, requested.length(), contentRange, filename, file);
    } catch (IOException | RuntimeException ex) {
      try {
        file.close();
      } catch (IOException ignored) {
      }
      if (ex instanceof ApiException api) throw api;
      throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "文件读取未完成");
    }
  }
}
