package cn.huimeng.common.io;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 单 Spring 项目写入协调；保存、复制、移动、删除与建目录共用，避免命名空间交错。 */
public final class WorkspaceMutationCoordinator {
  private static final ReentrantLock[] LOCKS = new ReentrantLock[256];

  static {
    Arrays.setAll(LOCKS, index -> new ReentrantLock());
  }

  private WorkspaceMutationCoordinator() {}

  public static <T> T mutate(String project, Supplier<T> operation) {
    var lock = LOCKS[Math.floorMod(project.hashCode(), LOCKS.length)];
    lock.lock();
    try {
      return operation.get();
    } finally {
      lock.unlock();
    }
  }
}
