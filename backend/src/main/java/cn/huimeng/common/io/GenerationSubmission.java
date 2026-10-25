package cn.huimeng.common.io;

import cn.huimeng.dto.resp.RunRespDTO;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** 控制器内部返回句柄；重复幂等请求不重放原生成流。 */
public record GenerationSubmission(RunRespDTO run, boolean replay, ResponseBodyEmitter stream) {}
