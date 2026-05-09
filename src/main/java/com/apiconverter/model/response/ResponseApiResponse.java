package com.apiconverter.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseApiResponse {

    private String id;

    private String object;

    @JsonProperty("created_at")
    private Long createdAt;

    private String model;

    private String status;

    private List<OutputItem> output;

    private Usage usage;

    private Map<String, Object> metadata;

    private String error;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutputItem {
        private String id;
        private String type;
        private String role;
        private String status;
        private List<ContentItem> content;
        private String name;
        @JsonProperty("call_id")
        private String callId;
        private String arguments;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentItem {
        private String type;
        private String text;
        private List<Map<String, Object>> annotations;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        @JsonProperty("input_tokens")
        private Integer inputTokens;
        @JsonProperty("output_tokens")
        private Integer outputTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
