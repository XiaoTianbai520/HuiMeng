package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 项目页面连接与调用等待。页面连接不是业务真相来源：服务端只转发请求并等待页面校验后的结果， 超时或断开都让外部调用显式失败，不伪造画布写入成功。 */
@Service
public class McpControlServiceImpl implements McpControlService {
  private static final long KEEPALIVE_MS = 20_000;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();
  private final ScheduledExecutorService keepalive =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "mcp-control-keepalive");
            thread.setDaemon(true);
            return thread;
          });

  private record Result(JsonNode value, String error) {}

  private static final class Connection {
    private final String projectId;
    private final String connectionId;
    private final SseEmitter emitter;
    private final Map<String, CompletableFuture<Result>> pending = new ConcurrentHashMap<>();
    private volatile JsonNode state;
    private volatile int revision = -1;
    private volatile boolean closed;

    private Connection(String projectId, String connectionId, SseEmitter emitter) {
      this.projectId = projectId;
      this.connectionId = connectionId;
      this.emitter = emitter;
    }

    private void send(Map<String, Object> payload) {
      synchronized (emitter) {
        if (closed) return;
        try {
          emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
          closed = true;
        }
      }
    }

    private void close() {
      synchronized (emitter) {
        if (closed) return;
        closed = true;
        try {
          emitter.complete();
        } catch (RuntimeException ignored) {
        }
      }
      pending.values().forEach(future -> future.complete(new Result(null, "项目页面已断开")));
      pending.clear();
    }
  }

  @Override
  public SseEmitter connect(String projectId, String connectionId) {
    if (connectionId == null || !connectionId.matches("[A-Za-z0-9-]{8,64}"))
      throw ApiException.invalid();
    SseEmitter emitter = new SseEmitter(0L);
    Connection connection = new Connection(projectId, connectionId, emitter);
    Connection previous = connections.put(projectId, connection);
    if (previous != null) previous.close();
    ScheduledFuture<?> ticker =
        keepalive.scheduleAtFixedRate(
            () -> {
              synchronized (emitter) {
                if (connection.closed) return;
                try {
                  emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException ex) {
                  connection.closed = true;
                  remove(projectId, connection);
                }
              }
            },
            KEEPALIVE_MS,
            KEEPALIVE_MS,
            TimeUnit.MILLISECONDS);
    emitter.onCompletion(
        () -> {
          ticker.cancel(false);
          remove(projectId, connection);
        });
    emitter.onTimeout(
        () -> {
          ticker.cancel(false);
          remove(projectId, connection);
        });
    emitter.onError(
        error -> {
          ticker.cancel(false);
          remove(projectId, connection);
        });
    return emitter;
  }

  private void remove(String projectId, Connection connection) {
    connections.remove(projectId, connection);
    connection.close();
  }

  @Override
  public void state(String projectId, String connectionId, Integer revision, JsonNode state) {
    Connection connection = require(projectId, connectionId);
    if (revision != null && revision <= connection.revision) return;
    if (revision != null) connection.revision = revision;
    connection.state = state;
  }

  @Override
  public void result(
      String projectId, String connectionId, String callId, JsonNode result, String error) {
    Connection connection = require(projectId, connectionId);
    var future = connection.pending.remove(callId);
    if (future == null) return;
    if (error != null && !error.isBlank()) future.complete(new Result(null, truncate(error)));
    else future.complete(new Result(result, null));
  }

  @Override
  public JsonNode call(String projectId, String name, JsonNode args, long timeoutSeconds) {
    Connection connection = connections.get(projectId);
    if (connection == null || connection.closed)
      throw new ApiException(503, "MCP_PAGE_NOT_CONNECTED", "该项目没有已连接的页面，请先在工作区打开项目并保持页面在线");
    String callId = UUID.randomUUID().toString();
    var future = new CompletableFuture<Result>();
    connection.pending.put(callId, future);
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("type", "call");
    payload.put("callId", callId);
    payload.put("name", name);
    payload.put(
        "args",
        args == null
            ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : args);
    payload.put("directory", projectId);
    connection.send(payload);
    try {
      Result result = future.get(timeoutSeconds, TimeUnit.SECONDS);
      if (result.error() != null)
        throw new ApiException(502, "MCP_CALL_FAILED", "页面执行失败：" + result.error());
      return result.value();
    } catch (TimeoutException ex) {
      connection.pending.remove(callId);
      connection.send(Map.of("type", "cancel", "callId", callId));
      throw new ApiException(504, "MCP_CALL_TIMEOUT", "页面在限定时间内没有返回结果");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      connection.pending.remove(callId);
      throw new ApiException(503, "MCP_CALL_INTERRUPTED", "调用被中断");
    } catch (ExecutionException ex) {
      connection.pending.remove(callId);
      throw new ApiException(502, "MCP_CALL_FAILED", "页面执行失败");
    }
  }

  @Override
  public JsonNode state(String projectId) {
    Connection connection = connections.get(projectId);
    return connection == null ? null : connection.state;
  }

  @Override
  public int connections() {
    return (int) connections.values().stream().filter(connection -> !connection.closed).count();
  }

  private Connection require(String projectId, String connectionId) {
    Connection connection = connections.get(projectId);
    if (connection == null || connection.closed || !connection.connectionId.equals(connectionId))
      throw new ApiException(404, "MCP_CONNECTION_NOT_FOUND", "控制连接已失效，请重新连接");
    return connection;
  }

  private static String truncate(String value) {
    return value.length() <= 2000 ? value : value.substring(0, 2000);
  }
}
