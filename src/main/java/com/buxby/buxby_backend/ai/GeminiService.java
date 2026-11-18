
package com.buxby.buxby_backend.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private final ObjectMapper mapper;
    private final GeminiClient gemini;

    public GeminiService(ObjectMapper mapper, GeminiClient gemini) {
        this.mapper = mapper;
        this.gemini = gemini;
    }

    public Map<String, Object> interpret(String message) {

        if (!gemini.configured()) {
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

            // 기본 모델이 혼잡하면 GeminiClient가 대체 모델로 넘어간다
            String text = gemini.generateText(body);

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
