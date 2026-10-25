package cn.huimeng.dto.resp;

import java.util.List;

/** 非分页列表；沿用冻结契约中的 items 字段。 */
public record ItemsRespDTO<T>(List<T> items) {}
