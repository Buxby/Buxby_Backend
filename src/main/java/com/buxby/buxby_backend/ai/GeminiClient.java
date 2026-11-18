package com.buxby.buxby_backend.ai;

import com.buxby.buxby_backend.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini generateContent 호출. 기본 모델이 혼잡(429/503)하거나 응답이 없거나 사라졌으면(404)
 * 다음 모델로 넘어간다. Gemini는 혼잡할 때 같은 모델을 다시 불러도 대부분 다시 실패해서
 * 같은 모델 재시도 대신 가벼운 모델로 바로 바꾼다.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final Set<Integer> TRY_NEXT_MODEL = Set.of(404, 429, 500, 502, 503, 504);

    private final RestClient client = HttpClients.create(HttpClients.AI_READ_TIMEOUT);
    private final ObjectMapper mapper;
    private final String apiKey;
    private final List<String> models;

    public GeminiClient(
            ObjectMapper mapper,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-3.5-flash}") String model,
            @Value("${gemini.fallback-models:}") String fallbackModels
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey;
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(model.trim());
        for (String m : fallbackModels.split(",")) {
            if (!m.isBlank()) ordered.add(m.trim());
        }
        this.models = List.copyOf(ordered);
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    /**
     * 요청 본문(contents, generationConfig 등)을 보내고 첫 후보의 텍스트를 돌려준다.
     * 모든 모델이 혼잡하면 503, 그 밖의 실패는 502.
     */
    public String generateText(Map<String, Object> body) {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_API_KEY가 필요합니다.");
        }
        List<String> failures = new ArrayList<>();
        for (String model : models) {
            URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent");
            try {
                String raw = client.post().uri(uri)
                        .header("x-goog-api-key", apiKey)
                        .body(body).retrieve().body(String.class);
                JsonNode root = mapper.readTree(raw);
                String text = root.path("candidates").path(0).path("content")
                        .path("parts").path(0).path("text").asText("");
                if (text.isBlank()) {
                    failures.add(model + ":EMPTY");
                    continue;
                }
                if (!failures.isEmpty()) log.warn("Gemini 대체 모델 사용: {} (실패: {})", model, failures);
                return text;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                // 응답 본문은 남기지 않는다 (요청 정보가 들어 있을 수 있음)
                failures.add(model + ":" + status);
                if (!TRY_NEXT_MODEL.contains(status)) {
                    log.warn("Gemini 요청 실패: {}", failures);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Gemini API 요청에 실패했습니다 (HTTP " + status + ").", e);
                }
            } catch (ResourceAccessException e) {
                failures.add(model + ":TIMEOUT");
            } catch (Exception e) {
                failures.add(model + ":" + e.getClass().getSimpleName());
            }
        }
        log.warn("Gemini 모든 모델 실패: {}", failures);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 서버가 혼잡합니다. 잠시 후 다시 시도하세요. " + failures);
    }
}
