package cn.huimeng.service.impl;

import cn.huimeng.dao.mapper.ProjectMapper;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 成员重新授权后签发短期、请求绑定的内部身份；只服务于当前就绪探针。 */
@Service
public class InternalIdentityServiceImpl implements InternalIdentityService {
  private final byte[] key;
  private final ObjectMapper json;
  private final ProjectMapper projectMapper;
  private final ProjectService projects;

  public InternalIdentityServiceImpl(
      @Value("${huimeng.internal-key-file}") String file,
      ObjectMapper json,
      ProjectMapper projectMapper,
      ProjectService projects)
      throws Exception {
    key = Base64.getDecoder().decode(Files.readString(Path.of(file)).strip());
    if (key.length != 32) throw new IllegalStateException("Internal key must be 256 bits");
    this.json = json;
    this.projectMapper = projectMapper;
    this.projects = projects;
  }

  @Override
  public String issueReadyProbe(String projectId, String requestId) {
    projects.requireMember(projectId, false);
    String workspaceKey = projectMapper.findById(projectId).workspaceKey();
    try {
      long now = Instant.now().getEpochSecond();
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("v", 1);
      context.put("iat", now);
      context.put("exp", now + 20);
      context.put("nonce", UUID.randomUUID().toString());
      context.put("actorId", RequestContext.actor().id());
      context.put("projectId", projectId);
      context.put("workspaceKey", workspaceKey);
      context.put("requestId", requestId);
      context.put("method", "GET");
      context.put("path", "/internal/ready");
      context.put(
          "bodySha256",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])));
      String payload =
          Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(context));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return payload
          + "."
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception ex) {
      throw new IllegalStateException("Internal identity unavailable");
    }
  }
}
