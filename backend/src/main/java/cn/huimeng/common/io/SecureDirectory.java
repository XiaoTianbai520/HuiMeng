package cn.huimeng.common.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 从文件系统根逐层无链接打开；持有的目录句柄不随路径替换而改指向。 */
public final class SecureDirectory implements AutoCloseable {
  public final SecureDirectoryStream<Path> stream;
  private final List<DirectoryStream<Path>> opened;

  private SecureDirectory(SecureDirectoryStream<Path> stream, List<DirectoryStream<Path>> opened) {
    this.stream = stream;
    this.opened = opened;
  }

  public static SecureDirectory open(Path directory) throws IOException {
    Path path = directory.toAbsolutePath().normalize();
    var opened = new ArrayList<DirectoryStream<Path>>();
    try {
      var root = Files.newDirectoryStream(path.getRoot());
      opened.add(root);
      if (!(root instanceof SecureDirectoryStream<Path> current))
        throw new UnsupportedOperationException("Secure directory handles unavailable");
      for (Path segment : path) {
        current = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
        opened.add(current);
      }
      return new SecureDirectory(current, opened);
    } catch (IOException | RuntimeException ex) {
      for (int i = opened.size() - 1; i >= 0; i--) opened.get(i).close();
      throw ex;
    }
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (int i = opened.size() - 1; i >= 0; i--) {
      try {
        opened.get(i).close();
      } catch (IOException ex) {
        failure = ex;
      }
    }
    if (failure != null) throw failure;
  }
}
