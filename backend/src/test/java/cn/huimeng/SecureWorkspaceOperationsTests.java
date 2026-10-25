package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureWorkspaceOperationsTests {
  @TempDir Path root;

  boolean linux() {
    return System.getProperty("os.name").equals("Linux")
        && System.getProperty("os.arch").equals("amd64");
  }

  void unavailable() {
    assertThatThrownBy(() -> LinuxDirectoryPrimitives.mkdir(root.resolve("new")))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).status)
        .isEqualTo(503);
    assertThat(Files.exists(root.resolve("new"))).isFalse();
  }

  @Test
  void directoryCreationAndNoReplaceMoveKeepExistingTarget() throws Exception {
    if (!linux()) {
      unavailable();
      return;
    }
    Path directory = root.resolve("文档");
    LinuxDirectoryPrimitives.mkdir(directory);
    assertThat(Files.isDirectory(directory)).isTrue();
    Path original = root.resolve("original"), target = root.resolve("target");
    Files.writeString(original, "original");
    Files.writeString(target, "target");
    assertThatThrownBy(() -> LinuxDirectoryPrimitives.moveNew(original, target))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).status)
        .isEqualTo(409);
    assertThat(Files.readString(original)).isEqualTo("original");
    assertThat(Files.readString(target)).isEqualTo("target");
  }

  @Test
  void recursiveCopyPreservesBytesAndPublishesOnlyCompleteTarget() throws Exception {
    if (!linux()) {
      unavailable();
      return;
    }
    Path source = Files.createDirectories(root.resolve("source/nested")).getParent();
    byte[] data = new byte[100000];
    new java.util.Random(1).nextBytes(data);
    Files.write(source.resolve("nested/图.bin"), data);
    Path target = root.resolve("copied");
    SecureWorkspaceOperations.copy(
        source, target, () -> assertThat(Files.exists(target)).isFalse());
    assertThat(Files.readAllBytes(target.resolve("nested/图.bin"))).isEqualTo(data);
    assertThat(SecureWorkspaceOperations.list(root, ""))
        .noneMatch(entry -> entry.name().startsWith(".huimeng-"));
  }

  @Test
  void copyFailureRemovesOwnedTemporaryTreeWithoutOverwriting() throws Exception {
    if (!linux()) {
      unavailable();
      return;
    }
    Path source = Files.writeString(root.resolve("source"), "source"),
        target = Files.writeString(root.resolve("target"), "target");
    assertThatThrownBy(() -> SecureWorkspaceOperations.copy(source, target, () -> {}))
        .isInstanceOf(ApiException.class);
    assertThat(Files.readString(target)).isEqualTo("target");
    try (var files = Files.list(root)) {
      assertThat(files.count()).isEqualTo(2);
    }
    assertThatThrownBy(
            () ->
                SecureWorkspaceOperations.copy(
                    source,
                    root.resolve("revoked"),
                    () -> {
                      throw ApiException.forbidden();
                    }))
        .isInstanceOf(ApiException.class);
    try (var files = Files.list(root)) {
      assertThat(files.count()).isEqualTo(2);
    }
  }

  @Test
  void recursiveRemovalDoesNotFollowLinksOrImplicitlyClearNonemptyDirectory() throws Exception {
    if (!linux()) {
      unavailable();
      return;
    }
    Path source = Files.createDirectory(root.resolve("source"));
    Files.writeString(source.resolve("a"), "a");
    assertThatThrownBy(() -> SecureWorkspaceOperations.remove(source, false, true))
        .isInstanceOf(DirectoryNotEmptyException.class);
    Path outside = Files.writeString(root.resolve("outside"), "outside");
    Files.createSymbolicLink(source.resolve("link"), outside);
    assertThatThrownBy(() -> SecureWorkspaceOperations.remove(source, true, true))
        .isInstanceOf(ApiException.class);
    assertThat(Files.readString(source.resolve("a"))).isEqualTo("a");
    assertThat(Files.readString(outside)).isEqualTo("outside");
    Files.delete(source.resolve("link"));
    SecureWorkspaceOperations.remove(source, true, true);
    assertThat(Files.exists(source)).isFalse();
  }

  @Test
  void directoryOperationsRejectLinkedAncestorsAndHidePrivateEntries() throws Exception {
    if (!linux()) {
      unavailable();
      return;
    }
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createSymbolicLink(root.resolve("link"), outside);
    assertThatThrownBy(() -> LinuxDirectoryPrimitives.mkdir(root.resolve("link/escaped")))
        .isInstanceOf(ApiException.class);
    assertThat(Files.exists(outside.resolve("escaped"))).isFalse();
    Files.writeString(root.resolve(".huimeng-write-private"), "internal");
    assertThat(SecureWorkspaceOperations.list(root, ""))
        .allMatch(entry -> !entry.name().startsWith(".huimeng-") && !entry.name().equals("link"));
  }
}
