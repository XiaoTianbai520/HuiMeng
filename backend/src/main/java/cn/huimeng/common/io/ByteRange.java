package cn.huimeng.common.io;

/** 单一 HTTP 字节范围；空值表示完整响应，无效范围按契约返回 416。 */
public record ByteRange(long start, long length, boolean partial, boolean valid) {
  public static ByteRange parse(String header, long size) {
    if (header == null) return new ByteRange(0, size, false, true);
    var matcher = java.util.regex.Pattern.compile("bytes=([0-9]*)-([0-9]*)").matcher(header);
    if (!matcher.matches() || size == 0) return new ByteRange(0, 0, true, false);
    try {
      String first = matcher.group(1), last = matcher.group(2);
      if (first.isEmpty()) {
        if (last.isEmpty()) return new ByteRange(0, 0, true, false);
        long suffix = Long.parseLong(last);
        if (suffix < 1) return new ByteRange(0, 0, true, false);
        long length = Math.min(size, suffix);
        return new ByteRange(size - length, length, true, true);
      }
      long start = Long.parseLong(first);
      long end = last.isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(last));
      if (start >= size || end < start) return new ByteRange(0, 0, true, false);
      return new ByteRange(start, end - start + 1, true, true);
    } catch (NumberFormatException ex) {
      return new ByteRange(0, 0, true, false);
    }
  }
}
