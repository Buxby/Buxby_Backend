
package com.buxby.buxby_backend.transit.subway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeoulSubwayService {

    private static final String BASE_URL =
            "http://swopenAPI.seoul.go.kr/api/subway";

    private static final ZoneId SEOUL_ZONE =
            ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter SOURCE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient client = RestClient.create();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${subway.api.key:}")
    private String serviceKey;


    // =====================================
    // 실시간 지하철 도착정보 조회
    // =====================================

    public Map<String, Object> getArrivals(String stationName) {

        if (stationName == null || stationName.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "지하철역 이름을 입력해 주세요."
            );
        }

        if (serviceKey == null || serviceKey.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "서울 지하철 API 인증키가 설정되지 않았습니다."
            );
        }

        String normalizedStationName = stationName.trim();

        try {

            // =====================================
            // 1. API 요청 주소 생성
            // =====================================

            URI uri = UriComponentsBuilder
                    .fromUriString(BASE_URL)
                    .pathSegment(
                            serviceKey,
                            "json",
                            "realtimeStationArrival",
                            "0",
                            "10",
                            normalizedStationName
                    )
                    .build()
                    .encode()
                    .toUri();


            // =====================================
            // 2. 서울시 API 호출
            // =====================================

            String json = client.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {

                throw new IllegalStateException(
                        "서울시 지하철 API 응답이 비어 있습니다."
                );
            }


            // =====================================
            // 3. JSON 파싱
            // =====================================

            JsonNode root = objectMapper.readTree(json);

            // API 결과 코드 확인
            JsonNode resultNode = root.path("RESULT");

            if (resultNode.isMissingNode()) {
                resultNode = root.path("errorMessage");
            }

            String resultCode = getText(resultNode, "CODE");

            if (resultCode == null) {
                resultCode = getText(resultNode, "code");
            }

            String resultMessage = getText(resultNode, "MESSAGE");

            if (resultMessage == null) {
                resultMessage = getText(resultNode, "message");
            }


            // =====================================
            // 4. 조회 결과 없음 처리
            // =====================================

            if ("INFO-200".equals(resultCode)) {

                return createNoDataResult(
                        normalizedStationName,
                        resultMessage
                );
            }


            // =====================================
            // 5. API 오류 처리
            // =====================================

            if (resultCode != null
                    && !resultCode.isBlank()
                    && !"INFO-000".equals(resultCode)) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "서울 지하철 API 오류: "
                                + resultCode
                                + " "
                                + (resultMessage == null
                                ? ""
                                : resultMessage)
                );
            }


            // =====================================
            // 6. 열차 목록 추출
            // =====================================

            JsonNode arrivalNode =
                    root.path("realtimeArrivalList");

            List<Map<String, Object>> arrivals =
                    new ArrayList<>();

            LocalDateTime now =
                    LocalDateTime.now(SEOUL_ZONE);

            if (arrivalNode.isArray()) {

                for (JsonNode item : arrivalNode) {

                    arrivals.add(
                            convertArrival(item, now)
                    );
                }
            }


            // 결과가 없는 경우
            if (arrivals.isEmpty()) {

                return createNoDataResult(
                        normalizedStationName,
                        "현재 조회된 열차 정보가 없습니다."
                );
            }


            // =====================================
            // 7. 최종 결과 반환
            // =====================================

            Map<String, Object> response =
                    new LinkedHashMap<>();

            response.put("status", "SUCCESS");
            response.put("stationName", normalizedStationName);
            response.put("count", arrivals.size());
            response.put("fetchedAt", now.toString());
            response.put("arrivals", arrivals);

            return response;

        } catch (ResponseStatusException e) {

            throw e;

        } catch (RestClientResponseException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울 지하철 API HTTP 오류: "
                            + e.getStatusCode().value()
            );

        } catch (Exception e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울 지하철 도착정보 처리 중 오류가 발생했습니다."
            );
        }
    }


    // =====================================
    // 열차 데이터 변환
    // =====================================

    private Map<String, Object> convertArrival(
            JsonNode item,
            LocalDateTime now
    ) {

        Map<String, Object> arrival =
                new LinkedHashMap<>();

        String subwayId = getText(item, "subwayId");

        String rawArrival = getText(item, "barvlDt");

        String receivedAt = getText(item, "recptnDt");

        Integer rawArrivalSeconds =
                parseInteger(rawArrival);

        Long dataAgeSeconds =
                calculateDataAge(receivedAt, now);

        // 공식 문서의 recptnDt 시차 보정
        Integer adjustedArrivalSeconds =
                adjustArrivalTime(
                        rawArrivalSeconds,
                        dataAgeSeconds
                );


        arrival.put("subwayId", subwayId);

        arrival.put(
                "lineName",
                getLineName(subwayId)
        );

        arrival.put("updnLine", getText(item, "updnLine"));

        arrival.put("trainLineNm", getText(item, "trainLineNm"));

        arrival.put("stationName", getText(item, "statnNm"));

        arrival.put("trainStatus", getText(item, "btrainSttus"));

        arrival.put("rawArrivalSeconds", rawArrivalSeconds);

        arrival.put(
                "adjustedArrivalSeconds",
                adjustedArrivalSeconds
        );

        arrival.put("dataAgeSeconds", dataAgeSeconds);

        arrival.put("trainNumber", getText(item, "btrainNo"));

        arrival.put(
                "destinationStation",
                getText(item, "bstatnNm")
        );

        arrival.put("receivedAt", receivedAt);

        arrival.put("arrivalMessage", getText(item, "arvlMsg2"));

        arrival.put(
                "arrivalDetailMessage",
                getText(item, "arvlMsg3")
        );

        String arrivalCode = getText(item, "arvlCd");

        arrival.put("arrivalCode", arrivalCode);

        arrival.put(
                "arrivalCodeLabel",
                getArrivalCodeLabel(arrivalCode)
        );

        arrival.put("isLastTrain", getText(item, "lstcarAt"));

        return arrival;
    }


    // =====================================
    // API 데이터 생성 시각에 따른 도착시간 보정
    // =====================================

    private Long calculateDataAge(
            String receivedAt,
            LocalDateTime now
    ) {

        if (receivedAt == null) {
            return null;
        }

        try {

            LocalDateTime sourceTime =
                    LocalDateTime.parse(
                            receivedAt,
                            SOURCE_TIME_FORMAT
                    );

            long age = Duration.between(
                    sourceTime,
                    now
            ).getSeconds();

            return Math.max(0, age);

        } catch (Exception e) {

            return null;
        }
    }


    private Integer adjustArrivalTime(
            Integer rawSeconds,
            Long dataAgeSeconds
    ) {

        if (rawSeconds == null) {
            return null;
        }

        if (dataAgeSeconds == null) {
            return rawSeconds;
        }

        return (int) Math.max(
                0,
                rawSeconds - dataAgeSeconds
        );
    }


    // =====================================
    // 호선 ID 변환
    // =====================================

    private String getLineName(String subwayId) {

        if (subwayId == null) {
            return "정보 없음";
        }

        return switch (subwayId) {
            case "1001" -> "1호선";
            case "1002" -> "2호선";
            case "1003" -> "3호선";
            case "1004" -> "4호선";
            case "1005" -> "5호선";
            case "1006" -> "6호선";
            case "1007" -> "7호선";
            case "1008" -> "8호선";
            case "1009" -> "9호선";
            case "1061" -> "중앙선";
            case "1063" -> "경의중앙선";
            case "1065" -> "공항철도";
            case "1067" -> "경춘선";
            case "1075" -> "수인분당선";
            case "1077" -> "신분당선";
            case "1081" -> "경강선";
            case "1092" -> "우이신설선";
            case "1093" -> "서해선";
            case "1032" -> "GTX-A";
            default -> subwayId;
        };
    }


    // =====================================
    // 도착 상태 코드 변환
    // =====================================

    private String getArrivalCodeLabel(String code) {

        if (code == null) {
            return "정보 없음";
        }

        return switch (code) {
            case "0" -> "진입";
            case "1" -> "도착";
            case "2" -> "출발";
            case "3" -> "전역 출발";
            case "4" -> "전역 진입";
            case "5" -> "전역 도착";
            case "99" -> "운행 중";
            default -> "확인 불가";
        };
    }


    // =====================================
    // 조회 결과 없음
    // =====================================

    private Map<String, Object> createNoDataResult(
            String stationName,
            String message
    ) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("status", "NO_DATA");
        result.put("stationName", stationName);
        result.put("count", 0);
        result.put("arrivals", List.of());

        result.put(
                "message",
                message == null
                        ? "현재 조회된 열차 정보가 없습니다."
                        : message
        );

        return result;
    }


    // =====================================
    // 공통 데이터 처리
    // =====================================

    private String getText(
            JsonNode node,
            String field
    ) {

        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        String text = value.asText().trim();

        return text.isEmpty() ? null : text;
    }


    private Integer parseInteger(String value) {

        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
