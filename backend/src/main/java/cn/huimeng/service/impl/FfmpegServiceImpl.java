package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.FfmpegExecuteReqDTO;
import cn.huimeng.dto.resp.GeneratedMediaRespDTO;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 服务端 FFmpeg 受控转码：预设固定在代码里，输入只能是项目素材，输出写回项目媒体。
 *
 * <p>不接受命令名、滤镜或任意参数；不把绝对路径、命令或 stderr 原文返回给页面。
 */
@Service
public class FfmpegServiceImpl implements FfmpegService {
  private static final Pattern SOURCE =
      Pattern.compile(
          "^\\.huimeng-media/([a-f0-9-]{36})\\.(mp4|mov|webm|mkv|mp3|wav|m4a|png|jpg|jpeg)$");
  private static final long MAX_TIMEOUT_SECONDS = 900;
  private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024 * 1024;
  private static final int MAX_SOURCES = 8;
  private final ProjectService projects;
  private final ProjectMediaService media;
  private final AuditService audit;
  private final ObjectMapper json;
  private final boolean enabled;
  private final String binary;
  private final Map<String, Process> running = new ConcurrentHashMap<>();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private volatile String version;
  private volatile Instant probedAt = Instant.EPOCH;

  /** 固定预设：编码参数不可由调用方指定。 */
  public record Preset(
      String id, String extension, String mimeType, String mediaType, List<String> encoder) {}

  static final Map<String, Preset> PRESETS =
      Map.of(
          "mp4-720p",
          new Preset(
              "mp4-720p",
              "mp4",
              "video/mp4",
              "video",
              List.of(
                  "-c:v",
                  "libx264",
                  "-preset",
                  "veryfast",
                  "-crf",
                  "23",
                  "-vf",
                  "scale=-2:720",
                  "-c:a",
                  "aac",
                  "-b:a",
                  "128k",
                  "-movflags",
                  "+faststart")),
          "mp4-1080p",
          new Preset(
              "mp4-1080p",
              "mp4",
              "video/mp4",
              "video",
              List.of(
                  "-c:v",
                  "libx264",
                  "-preset",
                  "veryfast",
                  "-crf",
                  "22",
                  "-vf",
                  "scale=-2:1080",
                  "-c:a",
                  "aac",
                  "-b:a",
                  "160k",
                  "-movflags",
                  "+faststart")),
          "webm-720p",
          new Preset(
              "webm-720p",
              "webm",
              "video/webm",
              "video",
              List.of(
                  "-c:v",
                  "libvpx-vp9",
                  "-crf",
                  "32",
                  "-b:v",
                  "0",
                  "-vf",
                  "scale=-2:720",
                  "-c:a",
                  "libopus",
                  "-b:a",
                  "96k")),
          "mp3-192k",
          new Preset(
              "mp3-192k",
              "mp3",
              "audio/mpeg",
              "audio",
              List.of("-vn", "-c:a", "libmp3lame", "-b:a", "192k")));

  public FfmpegServiceImpl(
      ProjectService projects,
      ProjectMediaService media,
      AuditService audit,
      ObjectMapper json,
      @Value("${huimeng.ffmpeg.enabled:true}") boolean enabled,
      @Value("${huimeng.ffmpeg.binary:ffmpeg}") String binary) {
    this.projects = projects;
    this.media = media;
    this.audit = audit;
    this.json = json;
    this.enabled = enabled;
    this.binary = binary;
  }

  @Override
  public Map<String, Object> status() {
    String current = probe();
    var result = new LinkedHashMap<String, Object>();
    result.put("available", current != null);
    result.put("version", current == null ? "" : current);
    result.put("running", running.size());
    result.put("presets", new ArrayList<>(new TreeSet<>(PRESETS.keySet())));
    return result;
  }

  /** 探测 ffmpeg 可执行文件；成功结果缓存 60 秒，失败每次重试。 */
  private String probe() {
    if (!enabled) return null;
    if (version != null && Instant.now().isBefore(probedAt.plusSeconds(60))) return version;
    try {
      Process process = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
      String first;
      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        first = reader.readLine();
      }
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0 || first == null) {
        process.destroyForcibly();
        return null;
      }
      version = first.length() > 120 ? first.substring(0, 120) : first;
      probedAt = Instant.now();
      return version;
    } catch (IOException ex) {
      return null;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  @Override
  public void validate(String projectId, FfmpegExecuteReqDTO input) {
    projects.requireMember(projectId, true);
    String method = input.operation().method();
    if (!Set.of("transcode", "concat", "extractAudio").contains(method))
      throw new ApiException(400, "FFMPEG_OPERATION_UNSUPPORTED", "未接通该转码操作");
    preset(input, method);
    sources(input, method);
    if (probe() == null) throw unavailable();
  }

  @Override
  public GeneratedMediaRespDTO execute(
      String projectId, FfmpegExecuteReqDTO input, String requestId, OutputStream output) {
    projects.requireMember(projectId, "cancel".equals(input.operation().method()));
    String method = input.operation().method();
    if (method.equals("prepare")) {
      if (probe() == null) throw unavailable();
      return null;
    }
    if (method.equals("cancel")) {
      cancel(projectId + "/" + input.requestId());
      return null;
    }
    Preset preset = preset(input, method);
    List<String> sources = sources(input, method);
    if (probe() == null) throw unavailable();
    String key = projectId + "/" + input.requestId();
    if (running.containsKey(key)) throw new ApiException(409, "FFMPEG_RUNNING", "该请求正在执行");
    cancelled.remove(key);
    Path workspace = projects.workspaceRoot(projectId, true);
    Path work = workspace.resolve(".ffmpeg").resolve(input.requestId());
    try {
      Files.createDirectories(work);
      List<Path> inputs = new ArrayList<>();
      long total = 0;
      for (String source : sources) {
        String mediaId = mediaId(source);
        var download = media.download(projectId, mediaId, null);
        if (download.status() != 200
            || download.length() <= 0
            || download.length() > MAX_SOURCE_BYTES)
          throw new ApiException(400, "INVALID_ARGUMENT", "素材不可用或超过 2 GiB");
        total += download.length();
        if (total > MAX_SOURCE_BYTES)
          throw new ApiException(413, "SOURCE_TOO_LARGE", "素材总大小超过 2 GiB");
        Path target = work.resolve("in-" + inputs.size() + extension(source));
        try (var stream = download.content()) {
          Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        inputs.add(target);
      }
      Path target = work.resolve("out." + preset.extension());
      List<String> command = new ArrayList<>(List.of(binary, "-hide_banner", "-nostdin", "-y"));
      command.addAll(List.of("-loglevel", "error", "-threads", "2"));
      if ("concat".equals(method)) {
        Path list = work.resolve("list.txt");
        var lines = new StringBuilder();
        for (Path item : inputs) lines.append("file '").append(item.toAbsolutePath()).append("'\n");
        Files.writeString(list, lines.toString(), StandardCharsets.UTF_8);
        command.addAll(List.of("-f", "concat", "-safe", "0", "-i", list.toString()));
      } else {
        command.addAll(List.of("-i", inputs.get(0).toString()));
      }
      command.addAll(preset.encoder());
      command.addAll(List.of("-progress", "pipe:1", "-nostats", target.toString()));
      long duration = durationMs(inputs.get(0));
      run(key, command, duration, output);
      long size = Files.size(target);
      if (size <= 0 || size > MAX_SOURCE_BYTES)
        throw new ApiException(503, "FFMPEG_OUTPUT_INVALID", "转码结果不可用");
      String filename = "transcoded-" + input.requestId() + "." + preset.extension();
      GeneratedMediaRespDTO uploaded;
      try (var stream = Files.newInputStream(target)) {
        var stored = media.upload(projectId, filename, size, stream, requestId);
        uploaded =
            new GeneratedMediaRespDTO(
                ".huimeng-media/" + stored.id() + "." + preset.extension(),
                preset.mimeType(),
                preset.mediaType(),
                preset.id());
      }
      audit.record(
          cn.huimeng.security.RequestContext.actor().id(),
          projectId,
          "FFMPEG_EXECUTE",
          input.requestId(),
          "SUCCESS",
          requestId);
      send(
          output,
          Map.of(
              "event",
              "done",
              "args",
              List.of(
                  Map.of(
                      "path", uploaded.path(),
                      "mimeType", uploaded.mimeType(),
                      "mediaType", uploaded.mediaType(),
                      "preset", uploaded.spec()))));
      return uploaded;
    } catch (ApiException ex) {
      // 流已开始，错误只能以事件告知；正常结束响应，避免客户端读到半截分块。
      send(output, Map.of("event", "error", "args", List.of(Map.of("message", ex.getMessage()))));
      return null;
    } catch (IOException ex) {
      send(output, Map.of("event", "error", "args", List.of(Map.of("message", "转码过程出错，请稍后重试"))));
      return null;
    } finally {
      deleteTree(work);
    }
  }

  private Preset preset(FfmpegExecuteReqDTO input, String method) {
    JsonNode args =
        input.operation().args() == null || input.operation().args().isEmpty()
            ? null
            : input.operation().args().get(0);
    String fallback = "extractAudio".equals(method) ? "mp3-192k" : "mp4-720p";
    String id = args == null ? fallback : args.path("preset").asText(fallback);
    Preset preset = PRESETS.get(id);
    if (preset == null) throw new ApiException(400, "FFMPEG_PRESET_UNSUPPORTED", "不支持该转码预设");
    return preset;
  }

  private List<String> sources(FfmpegExecuteReqDTO input, String method) {
    JsonNode args =
        input.operation().args() == null || input.operation().args().isEmpty()
            ? null
            : input.operation().args().get(0);
    if (args == null) throw new ApiException(400, "FFMPEG_ARGUMENT_INVALID", "缺少素材参数");
    var list = new ArrayList<String>();
    if ("concat".equals(method)) {
      for (var item : args.path("sources")) list.add(item.asText(""));
    } else {
      list.add(args.path("source").asText(""));
    }
    if (list.isEmpty() || list.size() > MAX_SOURCES)
      throw new ApiException(400, "FFMPEG_ARGUMENT_INVALID", "素材数量不合法");
    for (String source : list)
      if (!SOURCE.matcher(source).matches())
        throw new ApiException(400, "FFMPEG_SOURCE_INVALID", "素材必须是项目内的受支持文件");
    if (!Set.of("transcode", "concat", "extractAudio").contains(method))
      throw new ApiException(400, "FFMPEG_OPERATION_UNSUPPORTED", "未接通该转码操作");
    return list;
  }

  private String mediaId(String source) {
    var matcher = SOURCE.matcher(source);
    if (!matcher.matches()) throw new ApiException(400, "FFMPEG_SOURCE_INVALID", "素材路径不合法");
    return matcher.replaceAll("$1");
  }

  private String extension(String source) {
    int dot = source.lastIndexOf('.');
    return dot < 0 ? "" : source.substring(dot);
  }

  /** 用 ffprobe 读取时长；缺失时进度只报时间不报百分比。 */
  private long durationMs(Path input) {
    String probe =
        binary.endsWith("ffmpeg")
            ? binary.substring(0, binary.length() - "ffmpeg".length()) + "ffprobe"
            : "ffprobe";
    try {
      Process process =
          new ProcessBuilder(
                  probe,
                  "-v",
                  "error",
                  "-show_entries",
                  "format=duration",
                  "-of",
                  "default=noprint_wrappers=1:nokey=1",
                  input.toString())
              .redirectErrorStream(true)
              .start();
      String line;
      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        line = reader.readLine();
      }
      if (!process.waitFor(10, TimeUnit.SECONDS) || line == null || line.isBlank()) return 0;
      return (long) (Double.parseDouble(line.trim()) * 1000);
    } catch (IOException | NumberFormatException ex) {
      return 0;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return 0;
    }
  }

  /** 执行并流式上报进度；取消或超时只结束该请求的进程。 */
  private void run(String key, List<String> command, long durationMs, OutputStream output) {
    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      running.put(key, process);
      long deadline = System.currentTimeMillis() + MAX_TIMEOUT_SECONDS * 1000;
      long lastEmit = 0;
      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (System.currentTimeMillis() > deadline) {
            process.destroyForcibly();
            throw new ApiException(504, "FFMPEG_TIMEOUT", "转码超时，已终止");
          }
          if (!line.startsWith("out_time_ms=")) continue;
          long outTime = 0;
          try {
            outTime = Long.parseLong(line.substring("out_time_ms=".length()).trim()) / 1000;
          } catch (NumberFormatException ignored) {
            continue;
          }
          long now = System.currentTimeMillis();
          if (now - lastEmit < 500) continue;
          lastEmit = now;
          var event = new LinkedHashMap<String, Object>();
          event.put("event", "progress");
          var payload = new LinkedHashMap<String, Object>();
          payload.put("outTimeMs", outTime);
          payload.put("percent", durationMs > 0 ? Math.min(100, outTime * 100 / durationMs) : null);
          event.put("args", List.of(payload));
          send(output, event);
        }
      }
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new ApiException(504, "FFMPEG_TIMEOUT", "转码超时，已终止");
      }
      if (process.exitValue() != 0) {
        boolean stopped = cancelled.contains(key);
        throw new ApiException(
            stopped ? 499 : 503,
            stopped ? "FFMPEG_CANCELLED" : "FFMPEG_FAILED",
            stopped ? "转码已取消" : "转码失败，请检查素材后重试");
      }
    } catch (IOException ex) {
      // 取消会关闭子进程输出流，这里按取消处理，不冒充执行器故障。
      if (cancelled.contains(key)) throw new ApiException(499, "FFMPEG_CANCELLED", "转码已取消");
      throw new ApiException(503, "FFMPEG_UNAVAILABLE", "转码服务不可用");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "FFMPEG_FAILED", "转码被中断");
    } finally {
      running.remove(key);
      cancelled.remove(key);
      if (process != null) process.destroyForcibly();
    }
  }

  /** 只写事件帧；序列化留在服务层，控制器不接触 JSON 细节。 */
  private void send(OutputStream output, Map<String, Object> event) {
    try {
      output.write(
          ("data: " + json.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    } catch (IOException ignored) {
      // 页面断开后不再写事件；进程由取消或超时逻辑终止。
    }
  }

  private void cancel(String key) {
    Process process = running.get(key);
    if (process == null) return;
    cancelled.add(key);
    process.destroy();
  }

  private void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 临时目录清理失败不影响已返回的结果。
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "FFMPEG_UNAVAILABLE", "转码服务不可用");
  }
}
