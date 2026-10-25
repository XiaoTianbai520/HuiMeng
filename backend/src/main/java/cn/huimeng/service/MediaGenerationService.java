package cn.huimeng.service;

import cn.huimeng.common.io.GenerationSubmission;
import cn.huimeng.dto.req.GenerateImageReqDTO;
import cn.huimeng.dto.resp.RunRespDTO;

/** 受控媒体最小批次；名额和幂等属于项目，不以浏览器断线释放。 */
public interface MediaGenerationService {
  GenerationSubmission generate(
      String projectId, GenerateImageReqDTO input, String key, String requestId);

  RunRespDTO get(String projectId, String runId);

  RunRespDTO cancel(String projectId, String runId);
}
