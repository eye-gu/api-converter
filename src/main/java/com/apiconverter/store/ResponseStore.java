package com.apiconverter.store;

import com.apiconverter.model.response.ResponseApiResponse;

import java.util.List;
import java.util.Map;

public interface ResponseStore {

    void store(ResponseApiResponse response, List<Map<String, Object>> inputMessages, String previousResponseId);

    ResponseApiResponse get(String responseId);

    List<Map<String, Object>> getInputHistory(String responseId);

    String getPreviousResponseId(String responseId);

    List<ResponseApiResponse> listResponses();

    List<Map<String, Object>> getConversationMessages(String responseId);
}
