package cn.huimeng.service.impl;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.common.io.*;
import cn.huimeng.common.storage.ObjectStorage;
import cn.huimeng.dao.entity.GenerationRunDO;
import cn.huimeng.dao.mapper.GenerationRunMapper;
import cn.huimeng.dto.req.GenerateImageMediaDTO;
import cn.huimeng.dto.req.GenerateImageReqDTO;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** 每项目原子五名额、持久幂等与本人运行；接通现用 Qwen 文生图与单图编辑两模型。 */
@Service
public class MediaGenerationServiceImpl implements MediaGenerationService {
  private static final Logger LOG = LoggerFactory.getLogger(MediaGenerationServiceImpl.class);
  private static final String PROVIDER = "qwenImage21Local";
  private static final long REFERENCE_LIMIT = 16L * 1024 * 1024;
  private static final long QUEUE_POLL_MILLIS = 1000L;
  private static final java.util.regex.Pattern MEDIA_PATH =
      java.util.regex.Pattern.compile("^\\.huimeng-media/([a-f0-9-]{36})\\.png$");
  private final GenerationRunMapper runs;
  private final ProjectService projects;
  private final ModelAuthorizationService authorization;
  private final ProjectMediaService media;
  private final UserService users;
  private final ObjectStorage storage;
  private final ComfyImageClient comfy;
  private final ComfyVideoClient videos;
  private final ObjectMapper json;
  private final AuditService audit;
  private final TransactionTemplate transactions;
  private final MediaSerialGate gate;

  /** 单次生成的硬上限，不是单次 HTTP 调用超时；视频默认 90 分钟，避免 15 秒视频被 30 分钟掐断。 */
  private final long imageDeadlineSeconds;

  private final long videoDeadlineSeconds;

  /** 排队上限：执行器长期卡住时不能让后来者无限等待。 */
  private final long queueTimeoutSeconds;

  /** 流式 HTTP 连接的总时长，覆盖「排队 + 生成」；否则前端会在生成中途被断开。 */
  private final long streamTimeoutMillis;

  /** 8 个等待线程 + 32 个排队位：请求先被受理再按 FIFO 等执行器，而不是第 9 个直接 503。 真正的并发控制由 {@link MediaSerialGate} 负责。 */
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "huimeng-media");
            t.setDaemon(true);
            return t;
          });

  public MediaGenerationServiceImpl(
      GenerationRunMapper runs,
      ProjectService projects,
      ModelAuthorizationService authorization,
      ProjectMediaService media,
      UserService users,
      ObjectStorage storage,
      ComfyImageClient comfy,
      ComfyVideoClient videos,
      ObjectMapper json,
      AuditService audit,
      PlatformTransactionManager manager,
      MediaSerialGate gate,
      @Value("${huimeng.comfy.image-deadline-seconds:1800}") long imageDeadlineSeconds,
      @Value("${huimeng.comfy.video-deadline-seconds:5400}") long videoDeadlineSeconds,
      @Value("${huimeng.comfy.queue-timeout-seconds:3600}") long queueTimeoutSeconds,
      @Value("${huimeng.comfy.stream-timeout-millis:10800000}") long streamTimeoutMillis) {
    this.runs = runs;
    this.projects = projects;
    this.authorization = authorization;
    this.media = media;
    this.users = users;
    this.storage = storage;
    this.comfy = comfy;
    this.videos = videos;
    this.json = json;
    this.audit = audit;
    transactions = new TransactionTemplate(manager);
    this.gate = gate;
    this.imageDeadlineSeconds = imageDeadlineSeconds;
    this.videoDeadlineSeconds = videoDeadlineSeconds;
    this.queueTimeoutSeconds = queueTimeoutSeconds;
    this.streamTimeoutMillis = streamTimeoutMillis;
  }

  private RunRespDTO response(GenerationRunDO run) {
    return new RunRespDTO(
        run.id(),
        run.projectId(),
        run.kind(),
        run.state(),
        run.failureReason(),
        run.capacityHeld(),
        run.createdAt(),
        run.endedAt());
  }

  /** 失败原因只落固定词表代码，前端按前缀分类展示。不落异常文本，避免把上游地址、桶名或堆栈带出去。 */
  private static String reasonOf(Throwable ex) {
    if (ex instanceof StorageFailure) return "EXECUTION_STORAGE_UNAVAILABLE";
    if (ex instanceof ApiException api)
      return switch (api.code) {
        case "INVALID_ARGUMENT",
            "UNSUPPORTED_MODEL",
            "UNSUPPORTED_MEDIA_TYPE",
            "NOT_FOUND" -> "USER_INVALID_REQUEST";
        case "CAPABILITY_FORBIDDEN", "PROJECT_FORBIDDEN" -> "USER_NOT_PERMITTED";
        case "PROJECT_CAPACITY_EXCEEDED", "RATE_LIMITED" -> "USER_CAPACITY_EXCEEDED";
        case "MODEL_TIMEOUT" -> "MODEL_TIMEOUT";
        case "EXECUTOR_UNAVAILABLE" -> "MODEL_UNAVAILABLE";
        case "STORAGE_UNAVAILABLE" -> "EXECUTION_STORAGE_UNAVAILABLE";
        default -> "EXECUTION_FAILED";
      };
    if (ex instanceof InterruptedException) return "EXECUTION_INTERRUPTED";
    return "EXECUTION_INTERNAL";
  }

  /** 产物入库失败属于执行侧问题，与「模型没出图」区分开；保留原始异常供日志定位。 */
  private static final class StorageFailure extends RuntimeException {
    StorageFailure(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  private ProjectMediaRespDTO upload(
      String project, String filename, byte[] bytes, String requestId) {
    try {
      return media.upload(
          project, filename, bytes.length, new ByteArrayInputStream(bytes), requestId);
    } catch (RuntimeException ex) {
      throw new StorageFailure(ex);
    }
  }

  /** 服务端实际提交的规格；省略 size/ratio 等价于 1K、1:1，绝不静默沿用页面遗留值。 */
  private ComfyImageClient.Spec spec(GenerateImageReqDTO input) {
    return ComfyImageClient.resolve(input.modelId(), input.size(), input.ratio());
  }

  /** 参考图只认真实项目媒体；文生图不得夹带参考图，图生图必须恰好一张。 */
  private String referenceId(GenerateImageReqDTO input, boolean edit) {
    var images = input.images();
    int count = images == null ? 0 : images.size();
    if (!edit) {
      if (count != 0) throw new ApiException(400, "INVALID_ARGUMENT", "文生图不接受参考图，请改用单图编辑模型");
      return "";
    }
    if (count != 1) throw new ApiException(400, "INVALID_ARGUMENT", "单图编辑必须选择一张项目图片");
    var image = images.get(0);
    if (image == null
        || !"image/png".equals(image.mimeType())
        || image.path() == null
        || !MEDIA_PATH.matcher(image.path()).matches())
      throw new ApiException(400, "INVALID_ARGUMENT", "参考图必须是项目内的 PNG 素材");
    return MEDIA_PATH.matcher(image.path()).replaceAll("$1");
  }

  private record Admission(
      String id,
      GenerationRunDO run,
      Actor owner,
      ComfyImageClient.Spec spec,
      String reference,
      ComfyVideoClient.Spec videoSpec,
      List<String> videoReferences,
      boolean replay) {}

  private String hash(
      GenerateImageReqDTO input,
      String size,
      String ratio,
      List<String> references,
      Integer duration) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      json.writeValueAsBytes(
                          new GenerateImageReqDTO(
                              input.providerId(),
                              input.modelId(),
                              input.mediaType(),
                              input.prompt(),
                              size,
                              ratio,
                              references.isEmpty()
                                  ? null
                                  : references.stream().map(this::mediaRef).toList(),
                              duration))));
    } catch (Exception ex) {
      throw new IllegalStateException("Request hashing unavailable");
    }
  }

  private GenerateImageMediaDTO mediaRef(String id) {
    return new GenerateImageMediaDTO(".huimeng-media/" + id + ".png", "image/png");
  }

  /** 视频参考图按模式决定数量与顺序：i2v/l2v 一张，fl2v 首帧+尾帧，r2v 1–9 张。 */
  private List<String> videoReferenceIds(GenerateImageReqDTO input, String mode) {
    var images = input.images() == null ? List.<GenerateImageMediaDTO>of() : input.images();
    int expectedMin =
        switch (mode) {
          case "t2v" -> 0;
          case "i2v", "l2v" -> 1;
          case "fl2v" -> 2;
          default -> 1;
        };
    int expectedMax = mode.equals("r2v") ? 9 : expectedMin;
    if (images.size() < expectedMin || images.size() > expectedMax)
      throw new ApiException(400, "INVALID_ARGUMENT", "该模式参考图数量不符");
    var ids = new ArrayList<String>();
    for (var image : images) {
      if (image == null
          || !"image/png".equals(image.mimeType())
          || image.path() == null
          || !MEDIA_PATH.matcher(image.path()).matches())
        throw new ApiException(400, "INVALID_ARGUMENT", "参考图必须是项目内的 PNG 素材");
      ids.add(MEDIA_PATH.matcher(image.path()).replaceAll("$1"));
    }
    return List.copyOf(ids);
  }

  /** 校验、幂等与名额占位；不触达执行器，第 6 个与无效请求都停在供应商之前。 */
  private Admission admit(String project, GenerateImageReqDTO input, String key, String requestId) {
    authorization.requireApproved(project, input.providerId(), input.modelId());
    if (key == null || key.isBlank() || key.length() > 128) throw ApiException.invalid();
    // 1.0 没有音频供应商：明确拒绝，不把它当图片处理，也不占用名额或触达执行器。
    if ("audio".equals(input.mediaType()))
      throw new ApiException(409, "MEDIA_AUDIO_UNSUPPORTED", "音频生成尚未配置供应商，1.0 不提供独立音频成功流");
    boolean video = "video".equals(input.mediaType());
    ComfyImageClient.Spec spec = null;
    ComfyVideoClient.Spec videoSpec = null;
    String reference = "";
    List<String> videoReferences = List.of();
    if (video) {
      if (!ComfyVideoClient.PROVIDER.equals(input.providerId())
          || !videos.supports(input.modelId())) throw ComfyVideoClient.unfamiliarModel();
      videoSpec =
          ComfyVideoClient.resolve(input.modelId(), input.duration(), input.size(), input.ratio());
      videoReferences = videoReferenceIds(input, videoSpec.mode());
      for (String id : videoReferences) media.requireReference(project, id);
    } else {
      if (!"image".equals(input.mediaType()) || !PROVIDER.equals(input.providerId()))
        throw ComfyImageClient.unfamiliarModel();
      spec = spec(input);
      boolean edit = ComfyImageClient.EDIT_MODEL.equals(input.modelId());
      reference = referenceId(input, edit);
      // 参考图归属与大小在占位前校验，避免无效请求占用名额或触达执行器。
      if (!reference.isEmpty()) media.requireReference(project, reference);
    }
    Actor owner = RequestContext.actor();
    String hash =
        hash(
            input,
            video ? videoSpec.resolution() : spec.size(),
            video ? videoSpec.ratio() : spec.ratio(),
            video ? videoReferences : (reference.isEmpty() ? List.of() : List.of(reference)),
            video ? videoSpec.duration() : null);
    var known = runs.idempotent(project, owner.id(), key);
    if (known != null) {
      if (!known.requestHash().equals(hash))
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该幂等键已用于不同请求");
      return new Admission(
          known.id(), known, owner, spec, reference, videoSpec, videoReferences, true);
    }
    String id = UUID.randomUUID().toString();
    GenerationRunDO run =
        transactions.execute(
            tx -> {
              runs.lockProject(project);
              var old = runs.idempotent(project, owner.id(), key);
              if (old != null) {
                if (!old.requestHash().equals(hash))
                  throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该幂等键已用于不同请求");
                return old;
              }
              if (runs.held(project) >= 5)
                throw new ApiException(429, "PROJECT_CAPACITY_EXCEEDED", "当前项目已有五个活跃生成任务");
              runs.insert(project, id, video ? "video" : "image", owner.id(), key, hash);
              audit.record(owner.id(), project, "MEDIA_GENERATE", id, "ADMITTED", requestId);
              return runs.find(project, id);
            });
    return new Admission(
        run.id(), run, owner, spec, reference, videoSpec, videoReferences, !run.id().equals(id));
  }

  /** 名额已在库中占用后才探测执行器；不可达立即释放，不留下待核查占位。 */
  private void requireExecutor(String project, String id, boolean video) {
    probe(
        project,
        id,
        "MODEL_UNAVAILABLE",
        () -> {
          if (video) videos.requireAvailable();
          else comfy.requireAvailable();
        });
    probe(project, id, "EXECUTION_STORAGE_UNAVAILABLE", storage::requireAvailable);
    probe(
        project,
        id,
        "MODEL_UNAVAILABLE",
        () -> {
          if (video) videos.checkReady();
          else comfy.checkReady();
        });
  }

  private void probe(String project, String id, String reason, Runnable check) {
    try {
      check.run();
    } catch (RuntimeException ex) {
      release(project, id, reason);
      throw ex;
    }
  }

  private void release(String project, String id, String reason) {
    var current = runs.find(project, id);
    if (current != null && current.capacityHeld())
      runs.finish(project, id, "FAILED", false, reason);
  }

  private record QueueLease(boolean acquired, long waitedMillis) {
    static final QueueLease REJECTED = new QueueLease(false, 0);
  }

  /**
   * 单卡执行器一次只跑一个任务：调用线程在这里按票号排队，同时每秒给流写一次心跳。
   *
   * <p>排队不算失败，也不释放名额；用户取消或排队超过上限才结束。取得执行权后由调用方在 finally 里 {@link
   * MediaSerialGate#release()}，确保异常路径不会把队列永久堵死。
   */
  private QueueLease awaitExecutor(String project, String id, Consumer<Object> sink) {
    long ticket = gate.enter();
    long started = System.nanoTime();
    boolean leased = false;
    try {
      while (!(leased = gate.tryTurn(ticket))) {
        var current = runs.find(project, id);
        // 排队期间只应是 RESERVED；一旦被取消/被外部收敛，立即让出票号，不再占用执行器。
        if (current == null || !"RESERVED".equals(current.state())) {
          if (current != null) runs.finish(project, id, "CANCELLED", false);
          sink.accept(new MediaResultRespDTO(409, List.of(), "任务已结束，不再执行"));
          return QueueLease.REJECTED;
        }
        long waitedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
        if (waitedSeconds >= queueTimeoutSeconds) {
          runs.finish(project, id, "FAILED", false, "EXECUTION_BUSY");
          LOG.warn(
              "媒体任务排队超时 project={} run={} waitedSeconds={} queueDepth={}",
              project,
              id,
              waitedSeconds,
              gate.waiting());
          sink.accept(new MediaResultRespDTO(503, List.of(), "排队超时，请稍后重试"));
          return QueueLease.REJECTED;
        }
        sink.accept(" ");
        Thread.sleep(QUEUE_POLL_MILLIS);
      }
      return new QueueLease(true, (System.nanoTime() - started) / 1_000_000L);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      runs.finish(project, id, "FAILED", false, "EXECUTION_INTERRUPTED");
      sink.accept(new MediaResultRespDTO(503, List.of(), "服务正在停止，任务已中断"));
      return QueueLease.REJECTED;
    } catch (RuntimeException ex) {
      runs.finish(project, id, "FAILED", false, reasonOf(ex));
      throw ex;
    } finally {
      if (!leased) gate.abandon(ticket);
    }
  }

  @Override
  public GenerationSubmission generate(
      String project, GenerateImageReqDTO input, String key, String requestId) {
    Admission admission = admit(project, input, key, requestId);
    if (admission.replay()) return new GenerationSubmission(response(admission.run()), true, null);
    String id = admission.id();
    requireExecutor(project, id, admission.videoSpec() != null);
    // 连接要覆盖「排队 + 生成」全程；16 分钟会让 30 分钟以上的视频在前端提前断开。
    var stream = new ResponseBodyEmitter(streamTimeoutMillis);
    try {
      workers.execute(
          () -> {
            try {
              execute(project, admission, input, requestId, value -> send(stream, value));
            } finally {
              try {
                stream.complete();
              } catch (RuntimeException ignored) {
              }
            }
          });
    } catch (RejectedExecutionException ex) {
      release(project, id, "EXECUTION_BUSY");
      throw ComfyImageClient.unavailable();
    }
    return new GenerationSubmission(response(admission.run()), false, stream);
  }

  @Override
  public RunRespDTO start(String project, GenerateImageReqDTO input, String key, String requestId) {
    Admission admission = admit(project, input, key, requestId);
    if (admission.replay()) return response(admission.run());
    String id = admission.id();
    requireExecutor(project, id, admission.videoSpec() != null);
    try {
      workers.execute(() -> execute(project, admission, input, requestId, value -> {}));
    } catch (RejectedExecutionException ex) {
      release(project, id, "EXECUTION_BUSY");
      throw ComfyImageClient.unavailable();
    }
    return response(runs.find(project, id));
  }

  /** Agent 内部生图：同一名额语义、同步返回结果；文本会话本身不占名额，因此不会重复计数。 */
  @Override
  public GeneratedMediaRespDTO generateInternal(
      String project, GenerateImageReqDTO input, String key, String requestId) {
    Admission admission = admit(project, input, key, requestId);
    if (admission.replay()) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该工具调用已执行过，请勿重复提交");
    requireExecutor(project, admission.id(), admission.videoSpec() != null);
    var collected = new java.util.concurrent.atomic.AtomicReference<MediaResultRespDTO>();
    execute(
        project,
        admission,
        input,
        requestId,
        value -> {
          if (value instanceof MediaResultRespDTO result) collected.set(result);
        });
    var result = collected.get();
    if (result == null || result.code() != 200 || result.data().isEmpty())
      throw new ApiException(
          503, "GENERATION_FAILED", result == null ? "生成未完成，请稍后重试" : result.message());
    return result.data().get(0);
  }

  /** 视频执行：提交、轮询、下载、入库；取消与超时都走同一套终态收敛。 */
  private void executeVideo(
      String project,
      Admission admission,
      GenerateImageReqDTO input,
      String requestId,
      java.util.function.Consumer<Object> sink) {
    String id = admission.id();
    ComfyVideoClient.Spec spec = admission.videoSpec();
    Actor owner = admission.owner();
    var lease = awaitExecutor(project, id, sink);
    if (!lease.acquired()) return;
    // 内部生图/生视频跑在 Agent 或 MCP 的线程上，结束时还原调用方身份，避免后续步骤丢失登录态。
    var previousAuth = SecurityContextHolder.getContext().getAuthentication();
    var previousExternal = cn.huimeng.security.ExternalCallContext.current();
    boolean remoteAttempted = false, confirmedTerminal = false;
    String promptId = null;
    try {
      if (lease.waitedMillis() >= 1000)
        LOG.info(
            "视频任务开始执行 project={} run={} waitedMs={} queueDepth={}",
            project,
            id,
            lease.waitedMillis(),
            gate.waiting());
      bind(owner);
      authorization.requireApproved(project, input.providerId(), input.modelId());
      if (runs.running(project, id) != 1) {
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      var references = new ArrayList<byte[]>();
      for (String referenceId : admission.videoReferences())
        references.add(reference(project, referenceId));
      runs.reference(project, id, id);
      remoteAttempted = true;
      LOG.info("视频提交执行器 project={} run={} spec={}", project, id, spec.display());
      promptId = videos.submit(project, id, spec, input.prompt(), references);
      runs.reference(project, id, promptId);
      // 总时限只约束「已提交之后」的生成，排队等待由 queueTimeoutSeconds 单独约束。
      Instant deadline = Instant.now().plusSeconds(videoDeadlineSeconds);
      com.fasterxml.jackson.databind.JsonNode record = null;
      int pollFailures = 0;
      while (Instant.now().isBefore(deadline)) {
        sink.accept(" ");
        try {
          record = videos.history(promptId);
          pollFailures = 0;
        } catch (RuntimeException ex) {
          // 单次读超时/网络抖动不代表任务失败；连续三次才判定执行器不可用。
          if (++pollFailures >= 3) throw ex;
          Thread.sleep(2000);
          continue;
        }
        if (record != null && record.path("status").path("completed").asBoolean(false)) {
          confirmedTerminal = true;
          break;
        }
        if (record != null && "error".equals(record.path("status").path("status_str").asText()))
          throw ComfyVideoClient.unavailable();
        if (runs.find(project, id).state().equals("CANCELLING")) {
          videos.cancel(promptId);
          confirmedTerminal = true;
          runs.finish(project, id, "CANCELLED", false);
          throw ApiException.forbidden();
        }
        Thread.sleep(2000);
      }
      if (!confirmedTerminal) {
        videos.cancel(promptId);
        throw new ApiException(504, "MODEL_TIMEOUT", "生成超时");
      }
      if (runs.find(project, id).state().equals("CANCELLING")) {
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      bind(owner);
      authorization.requireApproved(project, input.providerId(), input.modelId());
      byte[] bytes = videos.output(promptId, record);
      var result = upload(project, "generated-" + id + ".mp4", bytes, requestId);
      if (runs.succeed(project, id) != 1) {
        media.discardGenerated(project, result.id(), owner.id(), requestId);
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      sink.accept(
          new MediaResultRespDTO(
              200,
              List.of(
                  new GeneratedMediaRespDTO(
                      ".huimeng-media/" + result.id() + ".mp4",
                      "video/mp4",
                      "video",
                      spec.display())),
              "success"));
    } catch (Exception ex) {
      cancelQuietly(promptId);
      var current = runs.find(project, id);
      // 用户主动取消不是失败，别把它记成故障，否则失败率统计会失真。
      if (current == null || !"CANCELLED".equals(current.state()))
        LOG.warn(
            "视频生成失败 project={} run={} remoteAttempted={} confirmed={} error={}: {}",
            project,
            id,
            remoteAttempted,
            confirmedTerminal,
            ex.getClass().getSimpleName(),
            ex.getMessage());
      boolean held = current != null && current.capacityHeld();
      String reason = reasonOf(ex);
      if (remoteAttempted && !confirmedTerminal) {
        runs.finish(project, id, "LOST", held, reason);
        sink.accept(new MediaResultRespDTO(503, List.of(), "生成未完成，请查询运行状态后重试"));
      } else if (held) {
        runs.finish(project, id, "FAILED", false, reason);
        sink.accept(new MediaResultRespDTO(503, List.of(), "生成失败，请稍后重试"));
      }
    } finally {
      if (previousAuth == null) SecurityContextHolder.clearContext();
      else SecurityContextHolder.getContext().setAuthentication(previousAuth);
      if (previousExternal == null) cn.huimeng.security.ExternalCallContext.clear();
      else cn.huimeng.security.ExternalCallContext.bind(previousExternal);
      gate.release();
    }
  }

  private void cancelQuietly(String promptId) {
    if (promptId != null) videos.cancel(promptId);
  }

  private void send(ResponseBodyEmitter stream, Object value) {
    try {
      stream.send(value, MediaType.APPLICATION_JSON);
    } catch (Exception ignored) {
    }
  }

  private void bind(Actor owner) {
    if ("EXTERNAL".equals(owner.role())) {
      // 外部客户端不查员工表；项目范围在调用线程已由 ExternalCallContext 限定，这里只恢复主体。
      SecurityContextHolder.getContext()
          .setAuthentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
      return;
    }
    Actor current = users.findActor(owner.id());
    if (current == null
        || !current.status().equals("ACTIVE")
        || current.authVersion() != owner.authVersion()) throw ApiException.forbidden();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(current, null, List.of()));
  }

  /** 参考图只从本项目媒体读取；不存在、越权或过大都按业务错误处理。 */
  private byte[] reference(String project, String mediaId) {
    var download = media.download(project, mediaId, null);
    if (download.status() != 200 || download.length() <= 0 || download.length() > REFERENCE_LIMIT)
      throw new ApiException(400, "INVALID_ARGUMENT", "参考图不可用或超过 16 MiB");
    try (var input = download.content()) {
      byte[] bytes = input.readNBytes((int) REFERENCE_LIMIT + 1);
      if (bytes.length > REFERENCE_LIMIT)
        throw new ApiException(400, "INVALID_ARGUMENT", "参考图超过 16 MiB");
      return bytes;
    } catch (IOException ex) {
      throw new ApiException(400, "INVALID_ARGUMENT", "参考图读取失败");
    }
  }

  private void execute(
      String project,
      Admission admission,
      GenerateImageReqDTO input,
      String requestId,
      java.util.function.Consumer<Object> sink) {
    Actor owner = admission.owner();
    var previousAuth = SecurityContextHolder.getContext().getAuthentication();
    var previousExternal = cn.huimeng.security.ExternalCallContext.current();
    if ("EXTERNAL".equals(owner.role()))
      cn.huimeng.security.ExternalCallContext.bind(
          new cn.huimeng.security.ExternalActor(owner.id(), project));
    if (admission.videoSpec() != null) {
      executeVideo(project, admission, input, requestId, sink);
      return;
    }
    String id = admission.id(), referenceId = admission.reference();
    ComfyImageClient.Spec spec = admission.spec();
    var lease = awaitExecutor(project, id, sink);
    if (!lease.acquired()) return;
    boolean remoteAttempted = false, confirmedTerminal = false;
    String promptId = null;
    try {
      if (lease.waitedMillis() >= 1000)
        LOG.info(
            "图片任务开始执行 project={} run={} waitedMs={} queueDepth={}",
            project,
            id,
            lease.waitedMillis(),
            gate.waiting());
      bind(owner);
      authorization.requireApproved(project, input.providerId(), input.modelId());
      if (runs.running(project, id) != 1) {
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      byte[] reference = referenceId.isEmpty() ? null : reference(project, referenceId);
      // 先写入本运行 ID 作为临时引用，提交失败进入 LOST 时仍有可核查参照；成功后再换成执行器任务 ID。
      runs.reference(project, id, id);
      remoteAttempted = true;
      LOG.info("图片提交执行器 project={} run={} spec={}", project, id, spec.display());
      promptId = comfy.submit(project, id, input.modelId(), spec, input.prompt(), reference);
      runs.reference(project, id, promptId);
      Instant deadline = Instant.now().plusSeconds(imageDeadlineSeconds);
      com.fasterxml.jackson.databind.JsonNode record = null;
      int pollFailures = 0;
      while (Instant.now().isBefore(deadline)) {
        sink.accept(" ");
        try {
          record = comfy.history(promptId);
          pollFailures = 0;
        } catch (RuntimeException ex) {
          // 单次读超时/网络抖动不代表任务失败；连续三次才判定执行器不可用。
          if (++pollFailures >= 3) throw ex;
          Thread.sleep(1000);
          continue;
        }
        if (terminal(record)) {
          confirmedTerminal = true;
          break;
        }
        if (runs.find(project, id).state().equals("CANCELLING")) {
          boolean dispatched = comfy.cancel(promptId);
          if (dispatched && !comfy.active(promptId)) {
            confirmedTerminal = true;
            runs.finish(project, id, "CANCELLED", false);
            throw ApiException.forbidden();
          }
        }
        Thread.sleep(1000);
      }
      if (!confirmedTerminal) throw new ApiException(504, "MODEL_TIMEOUT", "生成超时");
      if (runs.find(project, id).state().equals("CANCELLING")) {
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      if (record == null || !record.path("status").path("status_str").asText().equals("success"))
        throw ComfyImageClient.unavailable();
      bind(owner);
      authorization.requireApproved(project, input.providerId(), input.modelId());
      byte[] bytes = comfy.output(project, id, record);
      var result = upload(project, "generated-" + id + ".png", bytes, requestId);
      if (runs.succeed(project, id) != 1) {
        media.discardGenerated(project, result.id(), owner.id(), requestId);
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      sink.accept(
          new MediaResultRespDTO(
              200,
              List.of(
                  new GeneratedMediaRespDTO(
                      ".huimeng-media/" + result.id() + ".png",
                      "image/png",
                      "image",
                      spec.display())),
              "success"));
    } catch (Exception ex) {
      var current = runs.find(project, id);
      // 超时或读失败时上游可能还在跑：主动撤掉，避免它继续占着单卡拖慢下一个排队任务。
      if (remoteAttempted && !confirmedTerminal && promptId != null) {
        try {
          comfy.cancel(promptId);
        } catch (RuntimeException ignored) {
        }
      }
      // 用户主动取消不是失败，别把它记成故障，否则失败率统计会失真。
      if (current == null || !"CANCELLED".equals(current.state()))
        LOG.warn(
            "图片生成失败 project={} run={} remoteAttempted={} confirmed={} error={}: {}",
            project,
            id,
            remoteAttempted,
            confirmedTerminal,
            ex.getClass().getSimpleName(),
            ex.getMessage());
      if (current != null && current.capacityHeld())
        runs.finish(
            project,
            id,
            remoteAttempted && !confirmedTerminal ? "LOST" : "FAILED",
            remoteAttempted && !confirmedTerminal,
            reasonOf(ex));
      sink.accept(new MediaResultRespDTO(503, List.of(), "生成未完成，请查询运行状态后重试"));
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      if (previousAuth == null) SecurityContextHolder.clearContext();
      else SecurityContextHolder.getContext().setAuthentication(previousAuth);
      if (previousExternal == null) cn.huimeng.security.ExternalCallContext.clear();
      else cn.huimeng.security.ExternalCallContext.bind(previousExternal);
      gate.release();
    }
  }

  private boolean terminal(com.fasterxml.jackson.databind.JsonNode record) {
    return record != null
        && Set.of("success", "error").contains(record.path("status").path("status_str").asText());
  }

  private GenerationRunDO owned(String project, String id) {
    projects.requireMember(project, false);
    var run = runs.find(project, id);
    if (run == null || !run.actorId().equals(RequestContext.actor().id()))
      throw new ApiException(404, "NOT_FOUND", "运行不可用");
    return run;
  }

  @Override
  public RunRespDTO get(String project, String id) {
    return response(owned(project, id));
  }

  @Override
  public RunRespDTO cancel(String project, String id) {
    owned(project, id);
    runs.cancel(project, id);
    return response(runs.find(project, id));
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverAfterRestart() {
    LOG.info(
        "媒体执行配置 imageDeadline={}s videoDeadline={}s queueTimeout={}s streamTimeout={}ms",
        imageDeadlineSeconds,
        videoDeadlineSeconds,
        queueTimeoutSeconds,
        streamTimeoutMillis);
    runs.markLostAfterRestart();
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void reconcileLost() {
    for (var run : runs.lost()) {
      try {
        // 图片与视频共用同一个 ComfyUI 端点，但按类型走各自客户端，避免端点将来分开时查错服务。
        var record =
            "video".equals(run.kind())
                ? videos.history(run.upstreamRef())
                : comfy.history(run.upstreamRef());
        if (terminal(record)) runs.finish(run.projectId(), run.id(), "LOST", false);
      } catch (RuntimeException ignored) {
      }
    }
    // 上游被重建或任务被清空时查询永远拿不到终态，超过半小时直接释放，避免名额被永久占用。
    for (var run : runs.lostOlderThan(30)) runs.finish(run.projectId(), run.id(), "LOST", false);
  }

  @PreDestroy
  public void shutdown() {
    workers.shutdownNow();
  }
}
