package cn.huimeng.common.io;

import cn.huimeng.common.exception.ApiException;
import com.sun.jna.*;
import java.nio.file.Path;
import java.util.*;

/** Linux x86_64 仅补 JDK 缺少的 mkdirat/禁止覆盖 renameat2；从根 dirfd 无链接遍历。 */
public final class LinuxDirectoryPrimitives {
  private static final int DIRECTORY_FLAGS = 0x10000 | 0x20000 | 0x80000;

  private LinuxDirectoryPrimitives() {}

  private interface LibC extends Library {
    int open(String path, int flags);

    int openat(int directory, String name, int flags);

    int close(int descriptor);

    int mkdirat(int directory, String name, int mode);

    int renameat2(int fromDirectory, String fromName, int toDirectory, String toName, int flags);
  }

  private static final class Holder {
    static final LibC LIB =
        Native.load("c", LibC.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
  }

  private static LibC library() {
    if (!System.getProperty("os.name").equals("Linux")
        || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) throw unavailable();
    try {
      return Holder.LIB;
    } catch (LinkageError ex) {
      throw unavailable();
    }
  }

  private static final class Parent implements AutoCloseable {
    final LibC library;
    final int descriptor;

    Parent(LibC library, int descriptor) {
      this.library = library;
      this.descriptor = descriptor;
    }

    @Override
    public void close() {
      library.close(descriptor);
    }
  }

  private static Parent parent(Path path, LibC library) {
    Path directory = path.toAbsolutePath().normalize().getParent();
    int descriptor = library.open("/", DIRECTORY_FLAGS);
    if (descriptor < 0) throw error();
    try {
      for (Path segment : directory) {
        int next = library.openat(descriptor, segment.toString(), DIRECTORY_FLAGS);
        if (next < 0) throw error();
        library.close(descriptor);
        descriptor = next;
      }
      return new Parent(library, descriptor);
    } catch (RuntimeException ex) {
      library.close(descriptor);
      throw ex;
    }
  }

  public static void mkdir(Path target) {
    LibC library = library();
    try (var directory = parent(target, library)) {
      if (library.mkdirat(directory.descriptor, target.getFileName().toString(), 0700) != 0)
        throw error();
    }
  }

  public static void moveNew(Path source, Path target) {
    LibC library = library();
    try (var from = parent(source, library);
        var to = parent(target, library)) {
      try {
        if (library.renameat2(
                from.descriptor,
                source.getFileName().toString(),
                to.descriptor,
                target.getFileName().toString(),
                1)
            != 0) throw error();
      } catch (LinkageError ex) {
        throw unavailable();
      }
    }
  }

  private static ApiException error() {
    return switch (Native.getLastError()) {
      case 17 -> new ApiException(409, "FILE_ALREADY_EXISTS", "目标已存在，请换一个名称");
      case 2 -> new ApiException(404, "NOT_FOUND", "文件或父目录不可用");
      case 20, 40 -> new ApiException(400, "PATH_NOT_ALLOWED", "目录路径不被允许");
      default -> unavailable();
    };
  }

  private static ApiException unavailable() {
    return new ApiException(503, "EXECUTOR_UNAVAILABLE", "当前平台不支持此安全目录操作");
  }
}
