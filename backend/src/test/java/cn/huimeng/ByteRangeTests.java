package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.io.ByteRange;
import org.junit.jupiter.api.Test;

/** 覆盖真实下载的后缀、截断和无效范围，防止误返完整文件或错误成功状态。 */
class ByteRangeTests {
  @Test
  void fullOpenEndedSuffixAndClampedRanges() {
    assertThat(ByteRange.parse(null, 10)).isEqualTo(new ByteRange(0, 10, false, true));
    assertThat(ByteRange.parse("bytes=2-4", 10)).isEqualTo(new ByteRange(2, 3, true, true));
    assertThat(ByteRange.parse("bytes=8-", 10)).isEqualTo(new ByteRange(8, 2, true, true));
    assertThat(ByteRange.parse("bytes=-3", 10)).isEqualTo(new ByteRange(7, 3, true, true));
    assertThat(ByteRange.parse("bytes=8-99", 10)).isEqualTo(new ByteRange(8, 2, true, true));
  }

  @Test
  void malformedOverflowMultiRangeAndOutsideOffsetsAreUnsatisfiable() {
    for (String range :
        new String[] {
          "bytes=-",
          "bytes=-0",
          "bytes=3-2",
          "bytes=10-",
          "bytes=0-1,3-4",
          "bytes=999999999999999999999999-"
        }) assertThat(ByteRange.parse(range, 10).valid()).as(range).isFalse();
    assertThat(ByteRange.parse("bytes=0-", 0).valid()).isFalse();
  }
}
