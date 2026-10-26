package cn.huimeng.dto.req;

import java.util.List;

public record MediaProviderReqDTO(
    String label, String apiUrl, String apiKey, List<String> models) {}
