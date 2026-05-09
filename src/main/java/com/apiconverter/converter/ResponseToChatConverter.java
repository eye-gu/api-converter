package com.apiconverter.converter;

import com.apiconverter.model.chat.ChatCompletionRequest;
import com.apiconverter.model.chat.ChatCompletionResponse;
import com.apiconverter.model.response.ResponseApiRequest;
import com.apiconverter.model.response.ResponseApiResponse;
import com.apiconverter.store.ResponseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ResponseToChatConverter {

    private final ResponseStore responseStore;
    private final ObjectMapper objectMapper;

    public ChatCompletionRequest toChatCompletion(ResponseApiRequest request) {
        ChatCompletionRequest cc = new ChatCompletionRequest();
        cc.setModel(request.getModel());
        cc.setTemperature(request.getTemperature());
        cc.setTopP(request.getTopP());
        cc.setStream(request.getStream());

        if (request.getMaxOutputTokens() != null) {
            cc.setMaxTokens(request.getMaxOutputTokens());
        }

        List<ChatCompletionRequest.Message> messages = new ArrayList<>();

        if (request.getInstructions() != null && !request.getInstructions().isBlank()) {
            ChatCompletionRequest.Message sys = new ChatCompletionRequest.Message();
            sys.setRole("system");
            sys.setContent(request.getInstructions());
            messages.add(sys);
        }

        if (request.getPreviousResponseId() != null) {
            appendHistoryMessages(messages, request.getPreviousResponseId());
        }

        convertInput(messages, request.getInput());

        if (request.getTools() != null) {
            List<ChatCompletionRequest.Tool> tools = new ArrayList<>();
            for (ResponseApiRequest.ToolDef toolDef : request.getTools()) {
                if ("function".equals(toolDef.getType())) {
                    ChatCompletionRequest.Tool tool = new ChatCompletionRequest.Tool();
                    tool.setType("function");
                    ChatCompletionRequest.FunctionDef fn = new ChatCompletionRequest.FunctionDef();
                    fn.setName(toolDef.getName());
                    fn.setDescription(toolDef.getDescription());
                    fn.setParameters(toolDef.getParameters());
                    tool.setFunction(fn);
                    tools.add(tool);
                }
            }
            if (!tools.isEmpty()) {
                cc.setTools(tools);
            }
        }

        cc.setToolChoice(request.getToolChoice());

        cc.setMessages(messages);
        return cc;
    }

    @SuppressWarnings("unchecked")
    private void convertInput(List<ChatCompletionRequest.Message> messages, Object input) {
        if (input == null) return;

        if (input instanceof String text) {
            ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
            msg.setRole("user");
            msg.setContent(text);
            messages.add(msg);
            return;
        }

        if (input instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) item;
                    String type = (String) map.getOrDefault("type", "");

                    if ("function_call_output".equals(type)) {
                        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                        msg.setRole("tool");
                        Object output = map.get("output");
                        msg.setContent(output instanceof Map ? output.toString() : String.valueOf(output));
                        messages.add(msg);
                        continue;
                    }

                    if (!"message".equals(type) && !type.isEmpty()) {
                        continue;
                    }

                    String role = (String) map.get("role");
                    if (role == null) continue;
                    if ("developer".equals(role)) {
                        role = "system";
                    }
                    Object content = map.get("content");
                    ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                    msg.setRole(role);
                    msg.setContent(extractTextContent(content));
                    messages.add(msg);
                } else if (item instanceof String text) {
                    ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                    msg.setRole("user");
                    msg.setContent(text);
                    messages.add(msg);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object extractTextContent(Object content) {
        if (content == null) return null;
        if (content instanceof String) return content;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) part;
                    if ("input_text".equals(map.get("type"))) {
                        sb.append(map.get("text"));
                    }
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private void appendHistoryMessages(List<ChatCompletionRequest.Message> messages, String previousResponseId) {
        ResponseApiResponse stored = responseStore.get(previousResponseId);
        if (stored == null) return;

        List<Map<String, Object>> historyInputs = responseStore.getInputHistory(previousResponseId);
        if (historyInputs != null) {
            for (Map<String, Object> entry : historyInputs) {
                ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                msg.setRole((String) entry.get("role"));
                msg.setContent(entry.get("content"));
                messages.add(msg);
            }
        }

        if (stored.getOutput() != null) {
            for (ResponseApiResponse.OutputItem item : stored.getOutput()) {
                if ("message".equals(item.getType()) && item.getContent() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (ResponseApiResponse.ContentItem ci : item.getContent()) {
                        if (ci.getText() != null) {
                            sb.append(ci.getText());
                        }
                    }
                    if (!sb.isEmpty()) {
                        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                        msg.setRole("assistant");
                        msg.setContent(sb.toString());
                        messages.add(msg);
                    }
                }
            }
        }
    }

    public ResponseApiResponse toResponseApi(ChatCompletionResponse ccResp, String model) {
        ResponseApiResponse resp = new ResponseApiResponse();
        String respId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        resp.setId(respId);
        resp.setObject("response");
        resp.setCreatedAt(Instant.now().getEpochSecond());
        resp.setModel(model);
        resp.setStatus("completed");

        List<ResponseApiResponse.OutputItem> outputItems = new ArrayList<>();
        if (ccResp.getChoices() != null && !ccResp.getChoices().isEmpty()) {
            ChatCompletionResponse.Choice choice = ccResp.getChoices().get(0);
            ChatCompletionResponse.Message message = choice.getMessage();
            if (message != null) {
                ResponseApiResponse.OutputItem outputItem = new ResponseApiResponse.OutputItem();
                outputItem.setId("msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                outputItem.setType("message");
                outputItem.setRole("assistant");
                outputItem.setStatus("completed");

                ResponseApiResponse.ContentItem contentItem = new ResponseApiResponse.ContentItem();
                contentItem.setType("output_text");
                contentItem.setText(message.getContent() != null ? message.getContent().toString() : "");
                outputItem.setContent(List.of(contentItem));
                outputItems.add(outputItem);
            }
        }
        resp.setOutput(outputItems);

        if (ccResp.getUsage() != null) {
            ResponseApiResponse.Usage usage = new ResponseApiResponse.Usage();
            usage.setInputTokens(ccResp.getUsage().getPromptTokens());
            usage.setOutputTokens(ccResp.getUsage().getCompletionTokens());
            usage.setTotalTokens(ccResp.getUsage().getTotalTokens());
            resp.setUsage(usage);
        }

        return resp;
    }
}
