package com.apiconverter.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseStreamEvent {

    private String type;

    @JsonProperty("response")
    private ResponseApiResponse response;

    private String item;

    @JsonProperty("content_index")
    private Integer contentIndex;

    private OutputItem itemData;

    @JsonProperty("output_text")
    private String outputText;

    private String delta;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutputItem {
        private String id;
        private String type;
        private String role;
        private String status;
        private List<ResponseApiResponse.ContentItem> content;
    }
}
