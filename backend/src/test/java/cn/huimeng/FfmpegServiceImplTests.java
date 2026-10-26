package cn.huimeng;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.huimeng.common.exception.ApiException;
import cn.huimeng.dto.req.FfmpegExecuteReqDTO;
import cn.huimeng.dto.req.FfmpegOperationDTO;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.ProjectMediaService;
import cn.huimeng.service.ProjectService;
import cn.huimeng.service.impl.FfmpegServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.*;

/** 只验证参数与预设边界；不启动 ffmpeg，真实转码由显式验收脚本执行。 */
class FfmpegServiceImplTests {
  private final ObjectMapper json = new ObjectMapper();
  private FfmpegServiceImpl ffmpeg;

  @BeforeEach
  void setUp() {
    ffmpeg =
        new FfmpegServiceImpl(
            mock(ProjectService.class), mock(ProjectMediaService.class), mock(AuditService.class),
            json, false, "ffmpeg");
  }

  private FfmpegExecuteReqDTO request(String method, String argsJson) throws Exception {
    return new FfmpegExecuteReqDTO(
        "req-1",
        "p1",
        json.createObjectNode(),
        List.of(),
        new FfmpegOperationDTO(
            method, argsJson == null ? List.of() : List.of(json.readTree(argsJson))));
  }

  @Test
  void statusReportsUnavailableWhenDisabled() {
    var status = ffmpeg.status();
    assertEquals(false, status.get("available"));
    assertEquals(0, status.get("running"));
    assertTrue(((java.util.Collection<?>) status.get("presets")).contains("mp4-720p"));
  }

  @Test
  void prepareFailsClosedWithoutBinary() throws Exception {
    var error =
        assertThrows(
            ApiException.class,
            () ->
                ffmpeg.execute(
                    "p1",
                    request("prepare", null),
                    "r",
                    java.io.OutputStream.nullOutputStream()));
    assertEquals("FFMPEG_UNAVAILABLE", error.code);
  }

  @Test
  void rejectsUnknownPresetOperationAndForeignSource() throws Exception {
    var preset =
        assertThrows(
            ApiException.class,
            () ->
                ffmpeg.execute(
                    "p1",
                    request(
                        "transcode",
                        "{\"source\":\".huimeng-media/3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f.mp4\",\"preset\":\"4k\"}"),
                    "r",
                    java.io.OutputStream.nullOutputStream()));
    assertEquals("FFMPEG_PRESET_UNSUPPORTED", preset.code);

    var source =
        assertThrows(
            ApiException.class,
            () ->
                ffmpeg.execute(
                    "p1",
                    request("transcode", "{\"source\":\"../../etc/passwd\"}"),
                    "r",
                    java.io.OutputStream.nullOutputStream()));
    assertEquals("FFMPEG_SOURCE_INVALID", source.code);

    var method =
        assertThrows(
            ApiException.class,
            () ->
                ffmpeg.execute(
                    "p1",
                    request(
                        "shell", "{\"source\":\".huimeng-media/3f0d4c1e-1f2a-4b3c-8d9e-0a1b2c3d4e5f.mp4\"}"),
                    "r",
                    java.io.OutputStream.nullOutputStream()));
    assertEquals("FFMPEG_OPERATION_UNSUPPORTED", method.code);
  }

  @Test
  void concatRequiresAtLeastOneKnownSource() throws Exception {
    var error =
        assertThrows(
            ApiException.class,
            () ->
                ffmpeg.execute(
                    "p1",
                    request("concat", "{\"sources\":[]}"),
                    "r",
                    java.io.OutputStream.nullOutputStream()));
    assertEquals("FFMPEG_ARGUMENT_INVALID", error.code);
  }
}
