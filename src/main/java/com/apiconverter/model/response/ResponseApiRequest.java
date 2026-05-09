package com.apiconverter.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseApiRequest {

    private String model;

    private Object input;

    private String instructions;

    @JsonProperty("previous_response_id")
    private String previousResponseId;

    private Boolean stream;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    private Boolean store;

    private Map<String, Object> metadata;

    private List<ToolDef> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolDef {
        private String type;
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }
}
