package cn.huimeng.dto.resp;

import java.util.List;

/** 游标分页响应；nextCursor 为空表示没有下一页。 */
public record PageRespDTO<T>(List<T> items, String nextCursor) {}
