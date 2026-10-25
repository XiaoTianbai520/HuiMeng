package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.RevisionConflictException;
import cn.huimeng.common.io.SecureFileWriter;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureFileWriterTests {
  @TempDir Path root;

  boolean supported() throws Exception {
    try (var directory = Files.newDirectoryStream(root)) {
      return directory instanceof SecureDirectoryStream<?>;
    }
  }

  @Test
  void unsupportedProviderNeverFallsBackToPathBasedWriting() throws Exception {
    Path target = root.resolve("file.txt");
    if (!supported()) {
      assertThatThrownBy(() -> SecureFileWriter.write(target, new byte[0], null, true, () -> {}))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(Files.exists(target)).isFalse();
    } else {
      SecureFileWriter.write(target, new byte[0], null, true, () -> {});
      assertThat(Files.readAllBytes(target)).isEmpty();
    }
  }

  @Test
  void commitRefusalKeepsOldBytesAndCleansTemporaryFile() throws Exception {
    Path target = root.resolve("file.txt");
    Files.writeString(target, "old");
    if (supported()) {
      assertThatThrownBy(
              () ->
                  SecureFileWriter.write(
                      target,
                      new byte[] {1},
                      SecureFileWriter.revision("old".getBytes()),
                      false,
                      () -> {
                        throw new IllegalStateException("revoked");
                      }))
          .isInstanceOf(IllegalStateException.class);
    } else {
      assertThatThrownBy(() -> SecureFileWriter.write(target, new byte[] {1}, null, true, () -> {}))
          .isInstanceOf(UnsupportedOperationException.class);
    }
    assertThat(Files.readString(target)).isEqualTo("old");
    try (var files = Files.list(root)) {
      assertThat(files.count()).isEqualTo(1);
    }
  }

  @Test
  void concurrentCompareAndSwapAdmitsOnlyOneWriter() throws Exception {
    Path target = root.resolve("file.txt");
    Files.writeString(target, "old");
    String expected = SecureFileWriter.revision("old".getBytes());
    if (!supported()) {
      assertThatThrownBy(
              () -> SecureFileWriter.write(target, new byte[0], expected, false, () -> {}))
          .isInstanceOf(UnsupportedOperationException.class);
      return;
    }
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var jobs = new java.util.ArrayList<Future<Boolean>>();
      for (int index = 0; index < 2; index++) {
        byte[] bytes = new byte[] {(byte) index};
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    SecureFileWriter.write(target, bytes, expected, false, () -> {});
                    return true;
                  } catch (RevisionConflictException ex) {
                    return false;
                  }
                }));
      }
      start.countDown();
      assertThat(jobs.get(0).get(10, TimeUnit.SECONDS) ^ jobs.get(1).get(10, TimeUnit.SECONDS))
          .isTrue();
      assertThat(Files.size(target)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
