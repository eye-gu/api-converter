package com.apiconverter.controller;

import com.apiconverter.model.response.ResponseApiRequest;
import com.apiconverter.model.response.ResponseApiResponse;
import com.apiconverter.service.ProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ResponseApiController {

    private final ProxyService proxyService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/responses")
    public ResponseEntity<?> createResponse(@RequestBody ResponseApiRequest request) {
        try {
            String fullJson = objectMapper.writeValueAsString(request);
            log.info("[REQUEST-FULL-LENGTH] {} chars", fullJson.length());
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                log.info("[REQUEST-TOOLS] count={}, types={}", request.getTools().size(),
                        request.getTools().stream().map(t -> t.getType() + ":" + t.getName()).toList());
                log.info("[REQUEST-TOOLS-JSON] {}", objectMapper.writeValueAsString(request.getTools()));
            }
        } catch (Exception e) {
            log.warn("[REQUEST-FULL] Failed to serialize request", e);
        }

        log.info("[REQUEST] model={}, stream={}, inputType={}, previousResponseId={}, toolsCount={}",
                request.getModel(), request.getStream(),
                request.getInput() != null ? request.getInput().getClass().getSimpleName() : "null",
                request.getPreviousResponseId(),
                request.getTools() != null ? request.getTools().size() : 0);

        if (Boolean.TRUE.equals(request.getStream())) {
            log.info("[STREAM] Starting stream proxy");
            Flux<ServerSentEvent<String>> stream = proxyService.proxyStream(request)
                    .doOnSubscribe(s -> log.info("[STREAM] Client subscribed"))
                    .doOnComplete(() -> log.info("[STREAM] Completed"))
                    .doOnError(e -> log.error("[STREAM] Error: {}", e.getMessage()));
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream);
        }

        log.info("[NON-STREAM] Starting proxy");
        Mono<ResponseApiResponse> response = proxyService.proxy(request)
                .doOnNext(r -> log.info("[NON-STREAM] Got response: id={}", r.getId()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
