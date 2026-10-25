package cn.huimeng.security;

import cn.huimeng.common.exception.ApiException;
import java.util.*;

/** Bounded per-actor write throttling supplements the independent login limiter. */
public final class OperationLimiter {
  private record Window(long minute, int count) {}

  private final Map<String, Window> windows = new HashMap<>();

  public synchronized void check(String actorId, long now) {
    long minute = now / 60;
    windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
    Window window = windows.getOrDefault(actorId, new Window(minute, 0));
    if (window.count() >= 60 || windows.size() >= 10000)
      throw new ApiException(429, "RATE_LIMITED", "操作过于频繁，请稍后重试");
    windows.put(actorId, new Window(minute, window.count() + 1));
  }
}
