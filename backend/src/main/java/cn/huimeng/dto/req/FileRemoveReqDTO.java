package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;

/** 非递归删除不会清空目录；递归操作仍受深度和数量上限约束。 */
public record FileRemoveReqDTO(@NotBlank @Size(max = 2048) String path, boolean recursive) {}
