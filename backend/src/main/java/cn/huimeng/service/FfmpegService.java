package cn.huimeng.service;

import cn.huimeng.dto.req.FfmpegExecuteReqDTO;
import cn.huimeng.dto.resp.GeneratedMediaRespDTO;
import java.io.OutputStream;
import java.util.Map;

/** 服务端受控转码：只接受固定预设与项目素材，不执行浏览器给定的任意命令行。 */
public interface FfmpegService {
  /** 二进制可用性与正在运行的任务数。 */
  Map<String, Object> status();

  /** 开流前校验：成员、预设、素材与二进制，失败按 JSON 错误返回而不是 SSE。 */
  void validate(String projectId, FfmpegExecuteReqDTO input);

  /** prepare 只探测、cancel 只中断；其余同步执行并把 SSE 事件写入 output，返回产出媒体。 */
  GeneratedMediaRespDTO execute(
      String projectId, FfmpegExecuteReqDTO input, String requestId, OutputStream output);
}
