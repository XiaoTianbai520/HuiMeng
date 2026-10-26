package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.common.io.ComfyImageClient;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 显式选择才执行：直接对真实 ComfyUI 跑一次文生图与一次单图编辑，验证固定模板、像素规格与结果下载。
 *
 * <p>不启动 Spring、不连接数据库或对象存储；用于 A08 的真实小样本回归。默认端点 127.0.0.1:8188，可用 -Dhuimeng.comfy.endpoint 覆盖。
 */
class QwenRealImageHarness {
  private static final String ENDPOINT =
      System.getProperty("huimeng.comfy.endpoint", "http://127.0.0.1:8188");
  private static final Path OUT =
      Path.of(System.getProperty("huimeng.real.out", "target/qwen-real"));

  private byte[] await(
      ComfyImageClient client, String project, String run, String promptId, long seconds)
      throws Exception {
    Instant deadline = Instant.now().plusSeconds(seconds);
    while (Instant.now().isBefore(deadline)) {
      var record = client.history(promptId);
      String status = record == null ? "" : record.path("status").path("status_str").asText();
      if (status.equals("success")) return client.output(project, run, record);
      if (status.equals("error")) fail("ComfyUI 报告执行失败：" + record.path("status"));
      Thread.sleep(2000);
    }
    fail("等待 ComfyUI 结果超时");
    return new byte[0];
  }

  private int[] dimensions(byte[] png) throws Exception {
    var image = ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertThat(image).as("输出必须是可解码 PNG").isNotNull();
    return new int[] {image.getWidth(), image.getHeight()};
  }

  @Test
  void realTextToImageThenSingleImageEdit() throws Exception {
    var json = new ObjectMapper();
    var client = new ComfyImageClient(json, true, ENDPOINT);
    client.checkReady();
    Files.createDirectories(OUT);
    String project = "M3RealCheck";
    var report = json.createObjectNode();
    report.put("endpoint", ENDPOINT).put("startedAt", Instant.now().toString());

    var square = ComfyImageClient.resolve(ComfyImageClient.TEXT_MODEL, "1K", "1:1");
    String textRun = UUID.randomUUID().toString();
    Instant textStart = Instant.now();
    String textPrompt =
        client.submit(
            project,
            textRun,
            ComfyImageClient.TEXT_MODEL,
            square,
            "一只白色陶瓷茶杯放在浅色木桌上，柔和晨光，简洁静物摄影，没有文字，没有水印。",
            null);
    byte[] textPng = await(client, project, textRun, textPrompt, 900);
    int[] textSize = dimensions(textPng);
    Files.write(OUT.resolve("text-1k-square.png"), textPng);
    var textNode = report.putObject("textToImage");
    textNode.put("run", textRun).put("promptId", textPrompt).put("spec", square.display());
    textNode.put("width", textSize[0]).put("height", textSize[1]).put("bytes", textPng.length);
    textNode
        .put("seconds", Duration.between(textStart, Instant.now()).toSeconds())
        .put("sha256", sha256(textPng));
    assertThat(textSize[0]).isEqualTo(1024);
    assertThat(textSize[1]).isEqualTo(1024);

    var wide = ComfyImageClient.resolve(ComfyImageClient.EDIT_MODEL, "0.5MP", "16:9");
    String editRun = UUID.randomUUID().toString();
    Instant editStart = Instant.now();
    String editPrompt =
        client.submit(
            project, editRun, ComfyImageClient.EDIT_MODEL, wide, "把茶杯改成蓝色，保留桌面、背景、光线和构图。", textPng);
    byte[] editPng = await(client, project, editRun, editPrompt, 900);
    int[] editSize = dimensions(editPng);
    Files.write(OUT.resolve("edit-05mp-wide.png"), editPng);
    var editNode = report.putObject("imageToImage");
    editNode.put("run", editRun).put("promptId", editPrompt).put("spec", wide.display());
    editNode.put("width", editSize[0]).put("height", editSize[1]).put("bytes", editPng.length);
    editNode
        .put("seconds", Duration.between(editStart, Instant.now()).toSeconds())
        .put("sha256", sha256(editPng));
    assertThat(editSize[0] % 32).isZero();
    assertThat(editSize[1] % 32).isZero();
    assertThat(editSize[0]).isGreaterThan(editSize[1]);

    report.put("finishedAt", Instant.now().toString());
    Files.writeString(OUT.resolve("real-qwen.json"), report.toPrettyString() + "\n");
  }

  private String sha256(byte[] bytes) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
