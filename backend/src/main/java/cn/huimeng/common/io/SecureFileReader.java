package cn.huimeng.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 从文件系统根句柄逐层打开，拒绝每一级符号链接；不以路径预检替代实际打开约束。 */
public final class SecureFileReader {
  private SecureFileReader() {}

  public static OpenFile open(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    List<DirectoryStream<Path>> directories = new ArrayList<>();
    SeekableByteChannel channel = null;
    try {
      DirectoryStream<Path> root = Files.newDirectoryStream(absolute.getRoot());
      directories.add(root);
      if (!(root instanceof SecureDirectoryStream<Path> current)) {
        throw new UnsupportedOperationException("Secure directory handles unavailable");
      }
      for (int index = 0; index < absolute.getNameCount() - 1; index++) {
        current = current.newDirectoryStream(absolute.getName(index), LinkOption.NOFOLLOW_LINKS);
        directories.add(current);
      }
      Path name = absolute.getFileName();
      var attributes =
          current
              .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
      if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
        throw new IOException("Not a regular file");
      }
      channel =
          current.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
      return new OpenFile(channel, directories, channel.size());
    } catch (IOException | RuntimeException ex) {
      try {
        if (channel != null) channel.close();
      } finally {
        closeDirectories(directories);
      }
      throw ex;
    }
  }

  private static void closeDirectories(List<DirectoryStream<Path>> directories) throws IOException {
    IOException failure = null;
    List<DirectoryStream<Path>> reversed = new ArrayList<>(directories);
    Collections.reverse(reversed);
    for (var directory : reversed) {
      try {
        directory.close();
      } catch (IOException ex) {
        failure = ex;
      }
    }
    if (failure != null) throw failure;
  }

  /** 请求私有的文件/目录句柄；不会进入 JSON 响应或共享缓存。 */
  public static final class OpenFile extends InputStream {
    private final SeekableByteChannel channel;
    private final List<DirectoryStream<Path>> directories;
    private final long size;
    private long remaining;
    private boolean closed;

    private OpenFile(
        SeekableByteChannel channel, List<DirectoryStream<Path>> directories, long size) {
      this.channel = channel;
      this.directories = directories;
      this.size = size;
      remaining = size;
    }

    public long size() {
      return size;
    }

    public void range(long offset, long length) throws IOException {
      channel.position(offset);
      remaining = length;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      if (remaining == 0) return -1;
      int count = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, remaining)));
      if (count < 0) throw new IOException("File changed during read");
      remaining -= count;
      return count;
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        channel.close();
      } finally {
        closeDirectories(directories);
      }
    }
  }
}
