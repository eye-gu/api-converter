package com.apiconverter.service;

import com.apiconverter.config.UpstreamConfig;
import com.apiconverter.converter.ResponseToChatConverter;
import com.apiconverter.model.chat.ChatCompletionRequest;
import com.apiconverter.model.chat.ChatCompletionResponse;
import com.apiconverter.model.response.ResponseApiRequest;
import com.apiconverter.model.response.ResponseApiResponse;
import com.apiconverter.store.ResponseStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

    private final WebClient webClient;
    private final UpstreamConfig upstreamConfig;
    private final ResponseToChatConverter converter;
    private final ResponseStore responseStore;
    private final ObjectMapper objectMapper;

    public Mono<ResponseApiResponse> proxy(ResponseApiRequest request) {
        ChatCompletionRequest ccReq = converter.toChatCompletion(request);
        String targetModel = resolveModel(request.getModel());
        ccReq.setModel(targetModel);
        ccReq.setStream(false);

        List<Map<String, Object>> inputMessages = extractInputMessages(request);

        log.info("[PROXY] Sending non-stream request to upstream: model={}, url={}, messagesCount={}",
                targetModel, upstreamConfig.getBaseUrl(), ccReq.getMessages().size());

        return webClient.post()
                .uri(upstreamConfig.getBaseUrl())
                .header("Authorization", "Bearer " + upstreamConfig.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ccReq)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(ccResp -> {
                    ResponseApiResponse resp = converter.toResponseApi(ccResp, request.getModel());
                    responseStore.store(resp, inputMessages, request.getPreviousResponseId());
                    return resp;
                });
    }

    public Flux<ServerSentEvent<String>> proxyStream(ResponseApiRequest request) {
        ChatCompletionRequest ccReq = converter.toChatCompletion(request);
        String targetModel = resolveModel(request.getModel());
        ccReq.setModel(targetModel);
        ccReq.setStream(true);

        List<Map<String, Object>> inputMessages = extractInputMessages(request);

        String respId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long createdAt = Instant.now().getEpochSecond();

        StringBuilder fullText = new StringBuilder();
        Map<Integer, Map<String, String>> toolCalls = new LinkedHashMap<>();
        String[] textMsgId = {null};
        boolean[] textHeaderEmitted = {false};
        boolean[] upstreamFailed = {false};

        log.info("[STREAM] Upstream request: model={}, url={}, messagesCount={}, respId={}, tools={}",
                targetModel, upstreamConfig.getBaseUrl(), ccReq.getMessages().size(), respId,
                ccReq.getTools() != null ? ccReq.getTools().size() : 0);

        Flux<ServerSentEvent<String>> createdFlux;
        try {
            Map<String, Object> respObj = buildResponseObject(respId, createdAt, request.getModel(), "in_progress");
            String json = objectMapper.writeValueAsString(Map.of("type", "response.created", "response", respObj));
            createdFlux = Flux.just(
                    ServerSentEvent.<String>builder().event("response.created").data(json).build());
        } catch (Exception e) {
            log.error("[STREAM] Failed to build created event", e);
            createdFlux = Flux.empty();
        }

        Flux<ServerSentEvent<String>> upstreamFlux = webClient.post()
                .uri(upstreamConfig.getBaseUrl())
                .header("Authorization", "Bearer " + upstreamConfig.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ccReq)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> log.debug("[STREAM-RAW] chunk ({} chars): {}", chunk.length(),
                        chunk.length() > 300 ? chunk.substring(0, 300) + "..." : chunk))
                .flatMap(chunk -> {
                    List<ServerSentEvent<String>> events = new ArrayList<>();
                    for (String line : chunk.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty() || "[DONE]".equals(line)) continue;
                        String payload = line;
                        if (line.startsWith("data:")) {
                            payload = line.substring(5).trim();
                            if ("[DONE]".equals(payload)) continue;
                        }
                        try {
                            JsonNode node = objectMapper.readTree(payload);
                            JsonNode choices = node.get("choices");
                            if (choices == null || !choices.isArray() || choices.isEmpty()) continue;
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta == null) continue;

                            if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                                for (JsonNode tc : delta.get("tool_calls")) {
                                    int idx = tc.get("index").asInt();
                                    Map<String, String> state = toolCalls.computeIfAbsent(idx, k -> {
                                        Map<String, String> m = new LinkedHashMap<>();
                                        m.put("fcId", "fc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                                        return m;
                                    });

                                    if (tc.has("id")) {
                                        state.put("callId", tc.get("id").asText());
                                    }
                                    if (tc.has("function")) {
                                        JsonNode fn = tc.get("function");
                                        if (fn.has("name") && !state.containsKey("name")) {
                                            state.put("name", fn.get("name").asText());
                                            String itemAdded = objectMapper.writeValueAsString(Map.of(
                                                    "type", "response.output_item.added",
                                                    "output_index", idx,
                                                    "item", Map.of(
                                                            "id", state.get("fcId"),
                                                            "type", "function_call",
                                                            "status", "in_progress",
                                                            "call_id", state.get("callId"),
                                                            "name", state.get("name")
                                                    )
                                            ));
                                            events.add(ServerSentEvent.<String>builder()
                                                    .event("response.output_item.added").data(itemAdded).build());
                                        }
                                        if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                            String args = fn.get("arguments").asText();
                                            state.merge("arguments", args, String::concat);
                                        }
                                    }
                                }
                            }

                            if (delta.has("content") && !delta.get("content").isNull()) {
                                String text = delta.get("content").asText();
                                if (!text.isEmpty()) {
                                    if (!textHeaderEmitted[0]) {
                                        textHeaderEmitted[0] = true;
                                        String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                        textMsgId[0] = msgId;

                                        String itemAdded = objectMapper.writeValueAsString(Map.of(
                                                "type", "response.output_item.added",
                                                "output_index", 0, "item",
                                                Map.of("id", msgId, "type", "message", "role", "assistant",
                                                        "status", "in_progress", "content", List.of())));
                                        events.add(ServerSentEvent.<String>builder()
                                                .event("response.output_item.added").data(itemAdded).build());

                                        String partAdded = objectMapper.writeValueAsString(Map.of(
                                                "type", "response.content_part.added",
                                                "output_index", 0, "content_index", 0,
                                                "part", Map.of("type", "output_text", "text", "")));
                                        events.add(ServerSentEvent.<String>builder()
                                                .event("response.content_part.added").data(partAdded).build());
                                    }
                                    fullText.append(text);
                                    String json = objectMapper.writeValueAsString(Map.of(
                                            "type", "response.output_text.delta",
                                            "output_index", 0, "content_index", 0, "delta", text));
                                    events.add(ServerSentEvent.<String>builder()
                                            .event("response.output_text.delta").data(json).build());
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[STREAM] Failed to parse line: {}", line);
                        }
                    }
                    return Flux.fromIterable(events);
                })
                .doOnComplete(() -> log.info("[STREAM-UPSTREAM] Upstream completed, text={}, toolCalls={}",
                        fullText.length(), toolCalls.size()))
                .doOnError(e -> {
                    if (e instanceof WebClientResponseException webEx) {
                        log.error("[STREAM-UPSTREAM] Upstream error: {} - body: {}", webEx.getStatusCode(), webEx.getResponseBodyAsString());
                    } else {
                        log.error("[STREAM-UPSTREAM] Upstream error: {}", e.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    upstreamFailed[0] = true;
                    log.warn("[STREAM-UPSTREAM] Sending response.failed due to upstream error");
                    try {
                        String failedJson = objectMapper.writeValueAsString(Map.of(
                                "type", "response.failed",
                                "response", Map.of(
                                        "id", respId,
                                        "object", "response",
                                        "created_at", createdAt,
                                        "model", request.getModel(),
                                        "status", "failed",
                                        "error", Map.of(
                                                "type", "server_error",
                                                "message", e.getMessage() != null ? e.getMessage() : "upstream error"
                                        )
                                )
                        ));
                        return Flux.just(
                                ServerSentEvent.<String>builder().event("response.failed").data(failedJson).build());
                    } catch (Exception ex) {
                        return Flux.empty();
                    }
                });

        Flux<ServerSentEvent<String>> footerFlux = Flux.defer(() -> {
            if (upstreamFailed[0]) {
                return Flux.empty();
            }
            log.info("[STREAM-FOOTER] Building completion events");
            try {
                List<ServerSentEvent<String>> events = new ArrayList<>();

                if (!toolCalls.isEmpty()) {
                    List<Map<String, Object>> outputItems = new ArrayList<>();
                    for (Map.Entry<Integer, Map<String, String>> entry : toolCalls.entrySet()) {
                        int idx = entry.getKey();
                        Map<String, String> state = entry.getValue();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", state.get("fcId"));
                        item.put("type", "function_call");
                        item.put("status", "completed");
                        item.put("call_id", state.getOrDefault("callId", ""));
                        item.put("name", state.getOrDefault("name", ""));
                        item.put("arguments", state.getOrDefault("arguments", ""));
                        outputItems.add(item);

                        String itemDone = objectMapper.writeValueAsString(Map.of(
                                "type", "response.output_item.done",
                                "output_index", idx, "item", item));
                        events.add(ServerSentEvent.<String>builder()
                                .event("response.output_item.done").data(itemDone).build());
                    }

                    Map<String, Object> completedResp = buildCompletedResponseWithOutput(respId, createdAt, request.getModel(), outputItems);
                    String completedJson = objectMapper.writeValueAsString(Map.of("type", "response.completed", "response", completedResp));
                    events.add(ServerSentEvent.<String>builder()
                            .event("response.completed").data(completedJson).build());

                    log.info("[STREAM-FOOTER] Sent {} tool call events", toolCalls.size());
                } else {
                    String text = fullText.toString();
                    String msgId = textMsgId[0] != null ? textMsgId[0] : "msg_completed";

                    String partDone = objectMapper.writeValueAsString(Map.of(
                            "type", "response.content_part.done",
                            "output_index", 0, "content_index", 0,
                            "part", Map.of("type", "output_text", "text", text)));
                    events.add(ServerSentEvent.<String>builder()
                            .event("response.content_part.done").data(partDone).build());

                    String itemDone = objectMapper.writeValueAsString(Map.of(
                            "type", "response.output_item.done",
                            "output_index", 0, "item",
                            buildOutputItem(msgId, "message", "assistant", "completed",
                                    List.of(Map.of("type", "output_text", "text", text)))));
                    events.add(ServerSentEvent.<String>builder()
                            .event("response.output_item.done").data(itemDone).build());

                    Map<String, Object> completedRespObj = buildCompletedResponse(respId, createdAt, request.getModel(), text);
                    String completedJson = objectMapper.writeValueAsString(Map.of("type", "response.completed", "response", completedRespObj));
                    events.add(ServerSentEvent.<String>builder()
                            .event("response.completed").data(completedJson).build());

                    log.info("[STREAM-FOOTER] Sent text completion, length={}", text.length());
                }

                ResponseApiResponse resp = buildStoredResponse(respId, createdAt, request.getModel(),
                        fullText.toString(), toolCalls);
                responseStore.store(resp, inputMessages, request.getPreviousResponseId());

                return Flux.fromIterable(events);
            } catch (Exception e) {
                log.error("[STREAM-FOOTER] Failed to build footer events", e);
                return Flux.empty();
            }
        });

        return Flux.concat(createdFlux, upstreamFlux, footerFlux);
    }

    private String resolveModel(String model) {
        if (upstreamConfig.getModelMapping() != null && upstreamConfig.getModelMapping().containsKey(model)) {
            return upstreamConfig.getModelMapping().get(model);
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractInputMessages(ResponseApiRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getInput() instanceof String text) {
            messages.add(Map.of("role", "user", "content", text));
        } else if (request.getInput() instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) item;
                    messages.add(Map.of("role", map.getOrDefault("role", "user"), "content", map.getOrDefault("content", "")));
                }
            }
        }
        return messages;
    }

    private ResponseApiResponse buildStoredResponse(String respId, long createdAt, String model,
                                                     String text, Map<Integer, Map<String, String>> toolCalls) {
        ResponseApiResponse resp = new ResponseApiResponse();
        resp.setId(respId);
        resp.setObject("response");
        resp.setCreatedAt(createdAt);
        resp.setModel(model);
        resp.setStatus("completed");

        List<ResponseApiResponse.OutputItem> outputItems = new ArrayList<>();
        if (!toolCalls.isEmpty()) {
            for (Map.Entry<Integer, Map<String, String>> entry : toolCalls.entrySet()) {
                Map<String, String> state = entry.getValue();
                ResponseApiResponse.OutputItem oi = new ResponseApiResponse.OutputItem();
                oi.setId(state.get("fcId"));
                oi.setType("function_call");
                oi.setStatus("completed");
                oi.setName(state.getOrDefault("name", ""));
                oi.setCallId(state.getOrDefault("callId", ""));
                oi.setArguments(state.getOrDefault("arguments", ""));
                outputItems.add(oi);
            }
        } else if (!text.isEmpty()) {
            ResponseApiResponse.OutputItem oi = new ResponseApiResponse.OutputItem();
            oi.setType("message");
            oi.setRole("assistant");
            oi.setStatus("completed");
            ResponseApiResponse.ContentItem ci = new ResponseApiResponse.ContentItem();
            ci.setType("output_text");
            ci.setText(text);
            oi.setContent(List.of(ci));
            outputItems.add(oi);
        }
        resp.setOutput(outputItems);
        return resp;
    }

    private Map<String, Object> buildResponseObject(String respId, long createdAt, String model, String status) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", respId);
        resp.put("object", "response");
        resp.put("created_at", createdAt);
        resp.put("model", model);
        resp.put("status", status);
        resp.put("output", List.of());
        return resp;
    }

    private Map<String, Object> buildOutputItem(String id, String type, String role, String status, List<Map<String, Object>> content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("role", role);
        item.put("status", status);
        item.put("content", content);
        return item;
    }

    private Map<String, Object> buildCompletedResponse(String respId, long createdAt, String model, String text) {
        Map<String, Object> outputItem = new LinkedHashMap<>();
        outputItem.put("id", "msg_completed");
        outputItem.put("type", "message");
        outputItem.put("role", "assistant");
        outputItem.put("status", "completed");
        outputItem.put("content", List.of(Map.of("type", "output_text", "text", text)));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", respId);
        resp.put("object", "response");
        resp.put("created_at", createdAt);
        resp.put("model", model);
        resp.put("status", "completed");
        resp.put("output", List.of(outputItem));
        return resp;
    }

    private Map<String, Object> buildCompletedResponseWithOutput(String respId, long createdAt, String model,
                                                                  List<Map<String, Object>> outputItems) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", respId);
        resp.put("object", "response");
        resp.put("created_at", createdAt);
        resp.put("model", model);
        resp.put("status", "completed");
        resp.put("output", outputItems);
        return resp;
    }
}
