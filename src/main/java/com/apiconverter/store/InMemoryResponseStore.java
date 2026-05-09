package com.apiconverter.store;

import com.apiconverter.model.response.ResponseApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "store.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryResponseStore implements ResponseStore {

    private static final int DEFAULT_MAX_ENTRIES = 1000;

    private final int maxEntries;
    private final Map<String, ResponseApiResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> inputHistories = new ConcurrentHashMap<>();
    private final Map<String, String> previousResponseIds = new ConcurrentHashMap<>();

    public InMemoryResponseStore() {
        this.maxEntries = DEFAULT_MAX_ENTRIES;
    }

    @Override
    public void store(ResponseApiResponse response, List<Map<String, Object>> inputMessages, String previousResponseId) {
        if (responses.size() >= maxEntries) {
            evictOldest();
        }
        responses.put(response.getId(), response);
        if (inputMessages != null) {
            inputHistories.put(response.getId(), new ArrayList<>(inputMessages));
        }
        if (previousResponseId != null) {
            previousResponseIds.put(response.getId(), previousResponseId);
        }
    }

    @Override
    public ResponseApiResponse get(String responseId) {
        return responses.get(responseId);
    }

    @Override
    public List<Map<String, Object>> getInputHistory(String responseId) {
        return inputHistories.get(responseId);
    }

    @Override
    public String getPreviousResponseId(String responseId) {
        return previousResponseIds.get(responseId);
    }

    @Override
    public List<ResponseApiResponse> listResponses() {
        return responses.values().stream()
                .sorted(Comparator.comparingLong(r -> r.getCreatedAt() != null ? r.getCreatedAt() : 0))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getConversationMessages(String responseId) {
        LinkedList<String> chain = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        String currentId = responseId;
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            chain.addFirst(currentId);
            currentId = previousResponseIds.get(currentId);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (String id : chain) {
            List<Map<String, Object>> inputs = inputHistories.get(id);
            if (inputs != null) {
                messages.addAll(inputs);
            }
            ResponseApiResponse resp = responses.get(id);
            if (resp != null && resp.getOutput() != null) {
                for (ResponseApiResponse.OutputItem item : resp.getOutput()) {
                    if ("message".equals(item.getType()) && item.getContent() != null) {
                        StringBuilder sb = new StringBuilder();
                        for (ResponseApiResponse.ContentItem ci : item.getContent()) {
                            if (ci.getText() != null) sb.append(ci.getText());
                        }
                        if (!sb.isEmpty()) {
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("role", "assistant");
                            msg.put("content", sb.toString());
                            msg.put("response_id", id);
                            messages.add(msg);
                        }
                    }
                }
            }
        }
        return messages;
    }

    private void evictOldest() {
        Optional<String> oldest = responses.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().getCreatedAt() != null ? e.getValue().getCreatedAt() : 0))
                .map(Map.Entry::getKey);
        oldest.ifPresent(id -> {
            responses.remove(id);
            inputHistories.remove(id);
            previousResponseIds.remove(id);
        });
    }
}
