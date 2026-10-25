package cn.huimeng.dao.entity;

import java.util.List;

/** 持久层游标结果；由业务层转换为响应 DTO。 */
public record CursorPageDO<T>(List<T> items, String nextCursor) {}
