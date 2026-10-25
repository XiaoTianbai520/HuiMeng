package cn.huimeng.dto.req;

import jakarta.validation.constraints.*;
import java.util.List;

/** 签发单项目外部客户端凭证及显式 MCP/A2A 能力范围。 */
public record IssueExternalClientReqDTO(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String projectId,
    @NotNull @Size(max = 128) List<@NotBlank @Size(max = 128) String> mcpTools,
    @NotNull @Size(max = 128) List<@NotBlank @Size(max = 128) String> a2aTeams) {}
