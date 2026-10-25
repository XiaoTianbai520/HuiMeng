package cn.huimeng.dto.resp;

import java.util.List;

/** 媒体协议的业务终态；HTTP 200 仍须检查 code。 */
public record MediaResultRespDTO(int code, List<GeneratedMediaRespDTO> data, String message) {}
