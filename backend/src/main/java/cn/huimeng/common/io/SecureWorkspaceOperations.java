package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 受控目录树操作；仅普通文件/目录，限制递归深度、项目数与复制总字节。 */
public final class SecureWorkspaceOperations {
  private SecureWorkspaceOperations() {}

  public record Entry(String name, String path, String type) {}

  private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    var value =
        directory
            .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
    if (value.isSymbolicLink() || !value.isRegularFile() && !value.isDirectory())
      throw new ApiException(400, "PATH_NOT_ALLOWED", "不支持链接或特殊文件");
    return value;
  }

  public static boolean publicName(String name) {
    return !name.startsWith(".huimeng-")
        && !name.equals(".")
        && !name.equals("..")
        && !name.isBlank()
        && name.chars().noneMatch(c -> c < 32)
        && !name.contains(":")
        && !name.contains("%")
        && !name.contains("\\");
  }

  public static List<Entry> list(Path path, String relative) throws IOException {
    var result = new ArrayList<Entry>();
    try (var directory = SecureDirectory.open(path)) {
      int seen = 0;
      for (Path entry : directory.stream) {
        if (++seen > 5000) throw limit();
        String name = entry.getFileName().toString();
        if (!publicName(name)) continue;
        BasicFileAttributes info;
        try {
          info = attributes(directory.stream, Path.of(name));
        } catch (NoSuchFileException | ApiException ex) {
          continue;
        }
        result.add(
            new Entry(
                name,
                relative.isEmpty() ? name : relative + "/" + name,
                info.isDirectory() ? "directory" : "file"));
      }
    }
    result.sort(Comparator.comparing(Entry::type).thenComparing(Entry::name));
    return result;
  }

  private static final class Budget {
    int entries;
    long bytes;

    void add(long size, int depth) {
      bytes += size;
      if (++entries > 10000 || depth > 64 || bytes > 512L * 1024 * 1024) throw limit();
    }
  }

  private static ApiException limit() {
    return new ApiException(413, "INVALID_ARGUMENT", "文件操作超过本批数量、深度或大小上限");
  }

  public static void copy(Path source, Path target, Runnable beforeCommit) throws IOException {
    Path temporary = target.resolveSibling(".huimeng-copy-" + UUID.randomUUID());
    boolean created = false;
    try {
      try (var directory = SecureDirectory.open(source.getParent())) {
        var info = attributes(directory.stream, source.getFileName());
        if (info.isDirectory()) LinuxDirectoryPrimitives.mkdir(temporary);
        else createEmpty(temporary);
        created = true;
        copyInto(source, temporary, info, new Budget(), 0);
      }
      beforeCommit.run();
      LinuxDirectoryPrimitives.moveNew(temporary, target);
      created = false;
    } finally {
      if (created) remove(temporary, true, false);
    }
  }

  private static void createEmpty(Path target) throws IOException {
    try (var directory = SecureDirectory.open(target.getParent());
        var ignored =
            directory.stream.newByteChannel(
                target.getFileName(),
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {}
  }

  private static void copyInto(
      Path source, Path target, BasicFileAttributes info, Budget budget, int depth)
      throws IOException {
    budget.add(info.isRegularFile() ? info.size() : 0, depth);
    if (info.isDirectory()) {
      try (var from = SecureDirectory.open(source)) {
        for (Path child : from.stream) {
          String name = child.getFileName().toString();
          if (!publicName(name))
            throw new ApiException(409, "FILE_OPERATION_INCOMPLETE", "目录包含受控临时项，请先核对后重试");
          var childInfo = attributes(from.stream, Path.of(name));
          Path destination = target.resolve(name);
          if (childInfo.isDirectory()) LinuxDirectoryPrimitives.mkdir(destination);
          else createEmpty(destination);
          copyInto(source.resolve(name), destination, childInfo, budget, depth + 1);
        }
      }
    } else {
      try (var from = SecureDirectory.open(source.getParent());
          var to = SecureDirectory.open(target.getParent());
          var input =
              from.stream.newByteChannel(
                  source.getFileName(),
                  Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          var output =
              to.stream.newByteChannel(
                  target.getFileName(),
                  Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long copied = 0;
        while (input.read(buffer) >= 0) {
          copied += buffer.position();
          if (copied > info.size()) throw new IOException("Source changed during copy");
          buffer.flip();
          while (buffer.hasRemaining()) output.write(buffer);
          buffer.clear();
        }
        if (copied != info.size()) throw new IOException("Source changed during copy");
        if (output instanceof FileChannel file) file.force(true);
      }
    }
  }

  public static void rename(Path source, Path target, Runnable beforeCommit) throws IOException {
    try (var directory = SecureDirectory.open(source.getParent())) {
      attributes(directory.stream, source.getFileName());
      beforeCommit.run();
      LinuxDirectoryPrimitives.moveNew(source, target);
    }
  }

  public static void remove(Path target, boolean recursive, boolean preflight) throws IOException {
    try (var directory = SecureDirectory.open(target.getParent())) {
      var info = attributes(directory.stream, target.getFileName());
      if (info.isDirectory()) {
        if (preflight) scan(target, new Budget(), 0);
        if (recursive) {
          try (var childDirectory = SecureDirectory.open(target)) {
            for (Path child : childDirectory.stream)
              remove(target.resolve(child.getFileName()), true, false);
          }
        }
        directory.stream.deleteDirectory(target.getFileName());
      } else directory.stream.deleteFile(target.getFileName());
    }
  }

  private static void scan(Path path, Budget budget, int depth) throws IOException {
    budget.add(0, depth);
    try (var directory = SecureDirectory.open(path)) {
      for (Path child : directory.stream) {
        String name = child.getFileName().toString();
        if (!publicName(name))
          throw new ApiException(409, "FILE_OPERATION_INCOMPLETE", "目录包含受控临时项，请先核对后重试");
        var info = attributes(directory.stream, Path.of(name));
        if (info.isDirectory()) scan(path.resolve(name), budget, depth + 1);
        else budget.add(0, depth + 1);
      }
    }
  }
}
