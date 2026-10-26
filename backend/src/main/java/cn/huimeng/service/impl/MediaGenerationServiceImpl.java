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
  private static final String PROVIDER = "qwenImage21Local";
  private static final long REFERENCE_LIMIT = 16L * 1024 * 1024;
  private static final long DEADLINE_SECONDS = 1800;
  private static final java.util.regex.Pattern MEDIA_PATH =
      java.util.regex.Pattern.compile("^\\.huimeng-media/([a-f0-9-]{36})\\.png$");
  private final GenerationRunMapper runs;
  private final ProjectService projects;
  private final ModelAuthorizationService authorization;
  private final ProjectMediaService media;
  private final UserService users;
  private final ObjectStorage storage;
  private final ComfyImageClient comfy;
  private final ObjectMapper json;
  private final AuditService audit;
  private final TransactionTemplate transactions;
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
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
      ObjectMapper json,
      AuditService audit,
      PlatformTransactionManager manager) {
    this.runs = runs;
    this.projects = projects;
    this.authorization = authorization;
    this.media = media;
    this.users = users;
    this.storage = storage;
    this.comfy = comfy;
    this.json = json;
    this.audit = audit;
    transactions = new TransactionTemplate(manager);
  }

  private RunRespDTO response(GenerationRunDO run) {
    return new RunRespDTO(
        run.id(),
        run.projectId(),
        "image",
        run.state(),
        run.capacityHeld(),
        run.createdAt(),
        run.endedAt());
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

  @Override
  public GenerationSubmission generate(
      String project, GenerateImageReqDTO input, String key, String requestId) {
    authorization.requireApproved(project, input.providerId(), input.modelId());
    if (key == null || key.isBlank() || key.length() > 128) throw ApiException.invalid();
    if (!"image".equals(input.mediaType()) || !PROVIDER.equals(input.providerId()))
      throw ComfyImageClient.unfamiliarModel();
    ComfyImageClient.Spec spec = spec(input);
    boolean edit = ComfyImageClient.EDIT_MODEL.equals(input.modelId());
    String reference = referenceId(input, edit);
    // 参考图归属与大小在占位前校验，避免无效请求占用名额或触达执行器。
    if (!reference.isEmpty()) media.requireReference(project, reference);
    Actor owner = RequestContext.actor();
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(
                          json.writeValueAsBytes(
                              new GenerateImageReqDTO(
                                  input.providerId(),
                                  input.modelId(),
                                  "image",
                                  input.prompt(),
                                  spec.size(),
                                  spec.ratio(),
                                  reference.isEmpty()
                                      ? null
                                      : List.of(
                                          new GenerateImageMediaDTO(
                                              ".huimeng-media/" + reference + ".png",
                                              "image/png"))))));
    } catch (Exception ex) {
      throw new IllegalStateException("Request hashing unavailable");
    }
    var known = runs.idempotent(project, owner.id(), key);
    if (known != null) {
      if (!known.requestHash().equals(hash))
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该幂等键已用于不同请求");
      return new GenerationSubmission(response(known), true, null);
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
              runs.insert(project, id, owner.id(), key, hash);
              audit.record(owner.id(), project, "MEDIA_GENERATE", id, "ADMITTED", requestId);
              return runs.find(project, id);
            });
    if (!run.id().equals(id)) return new GenerationSubmission(response(run), true, null);
    try {
      // 名额先由数据库原子占用；执行器不可达时立刻释放，第 6 个请求不会到达供应商。
      comfy.requireAvailable();
      storage.requireAvailable();
      comfy.checkReady();
    } catch (RuntimeException ex) {
      var current = runs.find(project, id);
      if (current != null && current.capacityHeld()) runs.finish(project, id, "FAILED", false);
      throw ex;
    }
    var stream = new ResponseBodyEmitter(16 * 60 * 1000L);
    try {
      workers.execute(() -> execute(project, id, owner, input, spec, reference, requestId, stream));
    } catch (RejectedExecutionException ex) {
      runs.finish(project, id, "FAILED", false);
      throw ComfyImageClient.unavailable();
    }
    return new GenerationSubmission(response(run), false, stream);
  }

  private void send(ResponseBodyEmitter stream, Object value) {
    try {
      stream.send(value, MediaType.APPLICATION_JSON);
    } catch (Exception ignored) {
    }
  }

  private void bind(Actor owner) {
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
      String id,
      Actor owner,
      GenerateImageReqDTO input,
      ComfyImageClient.Spec spec,
      String referenceId,
      String requestId,
      ResponseBodyEmitter stream) {
    boolean remoteAttempted = false, confirmedTerminal = false;
    try {
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
      String promptId = comfy.submit(project, id, input.modelId(), spec, input.prompt(), reference);
      runs.reference(project, id, promptId);
      Instant deadline = Instant.now().plusSeconds(DEADLINE_SECONDS);
      com.fasterxml.jackson.databind.JsonNode record = null;
      while (Instant.now().isBefore(deadline)) {
        send(stream, " ");
        record = comfy.history(promptId);
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
      if (!confirmedTerminal) throw ComfyImageClient.unavailable();
      if (runs.find(project, id).state().equals("CANCELLING")) {
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      if (record == null || !record.path("status").path("status_str").asText().equals("success"))
        throw ComfyImageClient.unavailable();
      bind(owner);
      authorization.requireApproved(project, input.providerId(), input.modelId());
      byte[] bytes = comfy.output(project, id, record);
      var result =
          media.upload(
              project,
              "generated-" + id + ".png",
              bytes.length,
              new ByteArrayInputStream(bytes),
              requestId);
      if (runs.succeed(project, id) != 1) {
        media.discardGenerated(project, result.id(), owner.id(), requestId);
        runs.finish(project, id, "CANCELLED", false);
        throw ApiException.forbidden();
      }
      send(
          stream,
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
      if (current != null && current.capacityHeld())
        runs.finish(
            project,
            id,
            remoteAttempted && !confirmedTerminal ? "LOST" : "FAILED",
            remoteAttempted && !confirmedTerminal);
      send(stream, new MediaResultRespDTO(503, List.of(), "生成未完成，请查询运行状态后重试"));
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      SecurityContextHolder.clearContext();
      try {
        stream.complete();
      } catch (RuntimeException ignored) {
      }
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
    runs.markLostAfterRestart();
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void reconcileLost() {
    for (var run : runs.lost()) {
      try {
        var record = comfy.history(run.upstreamRef());
        if (terminal(record)) runs.finish(run.projectId(), run.id(), "LOST", false);
      } catch (RuntimeException ignored) {
      }
    }
  }

  @PreDestroy
  public void shutdown() {
    workers.shutdownNow();
  }
}
