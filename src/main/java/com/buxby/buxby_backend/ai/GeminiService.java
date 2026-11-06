
package com.buxby.buxby_backend.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private final ObjectMapper mapper;
    private final RestClient client = RestClient.create();
    private final String key;
    private final String model;

    public GeminiService(
            ObjectMapper mapper,
            @Value("${gemini.api.key:}") String key,
            @Value("${gemini.model:gemini-2.5-flash}") String model
    ) {
        this.mapper = mapper;
        this.key = key;
        this.model = model;
    }

    public Map<String, Object> interpret(String message) {

        if (key.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GEMINI_API_KEY 환경변수를 설정하세요."
            );
        }

        String instruction =
                "당신은 한국 대중교통 알람 앱의 의도 분석기입니다. " +
                        "현재 교통정보나 노선을 지어내지 마세요. " +
                        "현재 한국 시간은 " +
                        LocalDateTime.now(ZoneId.of("Asia/Seoul")) +
                        "입니다. 내일/오늘 등 상대 날짜는 한국 시간으로 해석하세요. " +
                        "사용자의 발화에서 다음 JSON 객체만 반환하세요: " +
                        "intent (ROUTE, SET_ALARM, BOTH, OTHER 중 하나), " +
                        "origin (출발지 문자열 또는 null), " +
                        "destination (목적지 문자열 또는 null), " +
                        "arrivalDateTime (사용자가 명시한 경우 yyyy-MM-dd'T'HH:mm:ss 또는 null), " +
                        "reply (부족한 정보가 있으면 한국어로 짧게 질문). " +
                        "알람을 실제로 등록했다고 말하지 마세요. " +
                        "좌표와 실제 도착시간은 별도 교통 API가 필요합니다.\n" +
                        "사용자: " + message;

        try {
            URI uri = URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + model + ":generateContent"
            );

            Map<String, Object> body = Map.of(
                    "contents", List.of(
                            Map.of(
                                    "parts", List.of(
                                            Map.of("text", instruction)
                                    )
                            )
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json"
                    )
            );

            String raw = client.post()
                    .uri(uri)
                    .header("x-goog-api-key", key)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode response = mapper.readTree(raw);

            String text = response
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText("");

            if (text.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Gemini 응답이 비어 있습니다."
                );
            }

            JsonNode result = mapper.readTree(text);

            if (!result.isObject()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Gemini 응답이 JSON 객체가 아닙니다."
                );
            }

            return mapper.convertValue(
                    result,
                    new TypeReference<Map<String, Object>>() {}
            );

        } catch (RestClientResponseException e) {

            System.err.println(
                    "[Gemini] HTTP 오류 코드: " + e.getStatusCode()
            );
            System.err.println(
                    "[Gemini] 오류 응답: " + e.getResponseBodyAsString()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Gemini API 요청 실패 (서버 로그 확인)",
                    e
            );

        } catch (ResponseStatusException e) {
            throw e;

        } catch (Exception e) {

            System.err.println(
                    "[Gemini] 요청 또는 응답 처리 실패: " + e.getMessage()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Gemini 의도 분석 실패",
                    e
            );
        }
    }
}
