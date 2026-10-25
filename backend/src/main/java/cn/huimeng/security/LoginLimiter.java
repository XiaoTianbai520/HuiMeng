package cn.huimeng.security;

import cn.huimeng.common.exception.ApiException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded fixed-window throttling per network peer and username; forwarded headers are ignored. */
@Component
public class LoginLimiter {
  private record Window(long start, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final Clock clock;

  public LoginLimiter() {
    this(Clock.systemUTC());
  }

  public LoginLimiter(Clock clock) {
    this.clock = clock;
  }

  public synchronized void check(String ip, String username) {
    long now = clock.instant().getEpochSecond();
    windows.entrySet().removeIf(e -> now - e.getValue().start() >= 900);
    if (windows.size() >= 10000) throw limited();
    increment("ip:" + ip, 100, now);
    increment("user:" + username, 10, now);
  }

  private void increment(String key, int limit, long now) {
    Window window = windows.getOrDefault(key, new Window(now, 0));
    if (window.count() >= limit) throw limited();
    windows.put(key, new Window(window.start(), window.count() + 1));
  }

  public synchronized void success(String username) {
    windows.remove("user:" + username);
  }

  private ApiException limited() {
    return new ApiException(429, "RATE_LIMITED", "登录尝试过多，请稍后重试");
  }
}
