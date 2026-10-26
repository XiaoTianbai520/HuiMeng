package cn.huimeng.service;

import cn.huimeng.common.io.GenerationSubmission;
import cn.huimeng.dto.req.GenerateImageReqDTO;
import cn.huimeng.dto.resp.RunRespDTO;

/** 受控媒体最小批次；名额和幂等属于项目，不以浏览器断线释放。 */
public interface MediaGenerationService {
  GenerationSubmission generate(
      String projectId, GenerateImageReqDTO input, String key, String requestId);

  /** 只发起运行并立即返回句柄；外部协议（MCP/A2A）用查询接口跟进，不占用 HTTP 流。 */
  RunRespDTO start(String projectId, GenerateImageReqDTO input, String key, String requestId);

  RunRespDTO get(String projectId, String runId);

  RunRespDTO cancel(String projectId, String runId);

  /**
   * Agent 内部生图：与 HTTP 入口共用名额、幂等与结果归属，同步返回生成件。
   *
   * <p>文本会话本身不占名额，因此内部生成只计一次；无 HTTP 路由。
   */
  cn.huimeng.dto.resp.GeneratedMediaRespDTO generateInternal(
      String projectId, cn.huimeng.dto.req.GenerateImageReqDTO input, String key, String requestId);
}
