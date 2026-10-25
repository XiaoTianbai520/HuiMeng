package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.exception.RevisionConflictException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** 单 Spring 写入边界：同锁校验修订，同目录安全句柄创建临时文件并原子替换。 */
public final class SecureFileWriter {
  private static final ReentrantLock[] LOCKS = new ReentrantLock[256];

  static {
    Arrays.setAll(LOCKS, index -> new ReentrantLock());
  }

  private SecureFileWriter() {}

  public static String revision(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static String write(
      Path path, byte[] bytes, String expected, boolean exclusive, Runnable beforeCommit)
      throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    ReentrantLock lock = LOCKS[Math.floorMod(absolute.hashCode(), LOCKS.length)];
    lock.lock();
    List<DirectoryStream<Path>> opened = new ArrayList<>();
    try {
      DirectoryStream<Path> root = Files.newDirectoryStream(absolute.getRoot());
      opened.add(root);
      if (!(root instanceof SecureDirectoryStream<Path> parent))
        throw new UnsupportedOperationException("Secure directory handles unavailable");
      for (int i = 0; i < absolute.getNameCount() - 1; i++) {
        parent = parent.newDirectoryStream(absolute.getName(i), LinkOption.NOFOLLOW_LINKS);
        opened.add(parent);
      }
      Path name = absolute.getFileName();
      String current = currentRevision(parent, name);
      if (exclusive ? current != null : !Objects.equals(expected, current))
        throw new RevisionConflictException(current);
      Path temporary = Path.of(".huimeng-write-" + UUID.randomUUID() + ".tmp");
      boolean created = false;
      try {
        try (var channel =
            parent.newByteChannel(
                temporary,
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {
          created = true;
          ByteBuffer data = ByteBuffer.wrap(bytes);
          while (data.hasRemaining()) channel.write(data);
          if (channel instanceof FileChannel file) file.force(true);
        }
        beforeCommit.run();
        // JDK 定义为原子文件系统移动；不支持时失败，绝不回退成删除旧文件再复制。
        parent.move(temporary, parent, name);
        created = false;
        return revision(bytes);
      } finally {
        if (created) parent.deleteFile(temporary);
      }
    } finally {
      try {
        IOException failure = null;
        for (int i = opened.size() - 1; i >= 0; i--) {
          try {
            opened.get(i).close();
          } catch (IOException ex) {
            failure = ex;
          }
        }
        if (failure != null) throw failure;
      } finally {
        lock.unlock();
      }
    }
  }

  private static String currentRevision(SecureDirectoryStream<Path> parent, Path name)
      throws IOException {
    try {
      var attributes =
          parent
              .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
      if (!attributes.isRegularFile() || attributes.isSymbolicLink())
        throw new ApiException(400, "PATH_NOT_ALLOWED", "保存目标必须是普通文件");
      if (attributes.size() > 8L * 1024 * 1024)
        throw new ApiException(413, "INVALID_ARGUMENT", "文件超过本批保存上限");
      try (var channel =
          parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        ByteBuffer data = ByteBuffer.allocate(8 * 1024 * 1024 + 1);
        while (data.hasRemaining() && channel.read(data) >= 0) {}
        if (data.position() > 8 * 1024 * 1024)
          throw new ApiException(413, "INVALID_ARGUMENT", "文件超过本批保存上限");
        return revision(Arrays.copyOf(data.array(), data.position()));
      }
    } catch (NoSuchFileException ex) {
      return null;
    }
  }
}
