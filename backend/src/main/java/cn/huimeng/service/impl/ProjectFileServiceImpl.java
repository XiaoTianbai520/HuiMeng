package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.service.ProjectFileService;
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

  public ProjectFileServiceImpl(ProjectService projects) {
    this.projects = projects;
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
