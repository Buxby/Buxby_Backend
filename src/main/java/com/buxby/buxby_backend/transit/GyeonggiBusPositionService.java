package com.buxby.buxby_backend.transit;

import com.buxby.buxby_backend.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GyeonggiBusPositionService {

    private static final Logger log =
            LoggerFactory.getLogger(GyeonggiBusPositionService.class);

    private static final String BASE_URL =
            "https://apis.data.go.kr/6410000/buslocationservice/v2/getBusLocationListv2";

    private final RestClient client = HttpClients.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gbis.api.key:}")
    private String serviceKey;

    // =====================================
    // 경기 버스 실시간 차량 위치 및 혼잡도 조회
    // =====================================

    public Map<String, Object> getPositions(String busRouteId) {

        String apiKey = serviceKey == null
                ? ""
                : serviceKey.trim();

        if (apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GBIS API 인증키가 설정되지 않았습니다."
            );
        }

        if (busRouteId == null || busRouteId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "경기 버스 노선 ID를 입력해 주세요."
            );
        }

        try {

            // API 요청 URL 생성
            URI uri = UriComponentsBuilder
                    .fromUriString(BASE_URL)
                    .queryParam("serviceKey", "{serviceKey}")
                    .queryParam("routeId", "{routeId}")
                    .queryParam("format", "json")
                    .encode()
                    .buildAndExpand(Map.of(
                            "serviceKey", apiKey,
                            "routeId", busRouteId.trim()
                    ))
                    .toUri();

            // GBIS API 호출
            String json = client.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                throw new IllegalStateException(
                        "GBIS 버스 위치정보 응답이 비어 있습니다."
                );
            }

            JsonNode root = objectMapper.readTree(json);

            // GBIS 응답 구조
            // response.msgHeader
            // response.msgBody.busLocationList
            JsonNode response = root.path("response");

            JsonNode header = response.path("msgHeader");

            if (header.isMissingNode()) {
                header = root.path("msgHeader");
            }

            String resultCode =
                    header.path("resultCode").asText("");

            String resultMessage =
                    header.path("resultMessage")
                            .asText("GBIS API 응답 오류");

            // =====================================
            // 차량 정보가 없는 경우
            // =====================================

            if ("4".equals(resultCode)) {
                return createNoData(busRouteId);
            }

            // =====================================
            // API 응답 오류
            // =====================================

            if (!"0".equals(resultCode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GBIS API 오류: " + resultMessage
                                + " (코드: " + resultCode + ")"
                );
            }

            // 차량 목록
            JsonNode body = response.path("msgBody");

            if (body.isMissingNode()) {
                body = root.path("msgBody");
            }

            JsonNode itemNode = body.path("busLocationList");

            List<JsonNode> rawItems = normalizeItems(itemNode);

            List<Map<String, Object>> vehicles =
                    new ArrayList<>();

            for (JsonNode item : rawItems) {

                Map<String, Object> vehicle =
                        new LinkedHashMap<>();

                String crowdedCode = getText(item, "crowded");

                String routeTypeCode =
                        getText(item, "routeTypeCd");

                // 잔여 좌석을 제공하는 노선 유형만 표시
                Integer remainSeatCount = switch (routeTypeCode == null
                        ? ""
                        : routeTypeCode) {

                    case "11", "12", "14", "16", "17", "21", "22" ->
                            getInteger(item, "remainSeatCnt");

                    default -> null;
                };

                // 차량 기본 정보
                vehicle.put(
                        "vehicleId",
                        getText(item, "vehId")
                );

                vehicle.put(
                        "vehicleNumber",
                        getText(item, "plateNo")
                );

                // 현재 정류장 정보
                vehicle.put(
                        "currentStationId",
                        getText(item, "stationId")
                );

                vehicle.put(
                        "stationSequence",
                        getText(item, "stationSeq")
                );

                // 차량 운행 상태
                vehicle.put(
                        "vehicleStateCode",
                        getText(item, "stateCd")
                );

                vehicle.put(
                        "vehicleState",
                        getVehicleStateLabel(
                                getText(item, "stateCd")
                        )
                );

                // 혼잡도
                vehicle.put("congestionCode", crowdedCode);

                String congestionLabel =
                        getCongestionLabel(crowdedCode);

                vehicle.put("congestion", congestionLabel);
                vehicle.put("congestionLabel", congestionLabel);

                // 잔여 좌석
                vehicle.put("remainSeatCount", remainSeatCount);
                vehicle.put("remainSeatCnt", remainSeatCount);

                // 노선 유형
                vehicle.put(
                        "routeTypeCode",
                        getText(item, "routeTypeCd")
                );

                // 차량 유형
                vehicle.put(
                        "lowPlate",
                        getText(item, "lowPlate")
                );

                // 태그리스 차량 여부
                vehicle.put(
                        "taglessCode",
                        getText(item, "taglessCd")
                );

                vehicles.add(vehicle);
            }

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "status",
                    vehicles.isEmpty() ? "NO_DATA" : "SUCCESS"
            );

            result.put("provider", "GYEONGGI");
            result.put("busRouteId", busRouteId);
            result.put("count", vehicles.size());
            result.put("vehicles", vehicles);

            result.put(
                    "queryTime",
                    header.path("queryTime").asText(null)
            );

            return result;

        } catch (RestClientResponseException e) {

            log.error(
                    "[GBIS API HTTP 오류] status={}",
                    e.getStatusCode().value()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GBIS API HTTP 오류: "
                            + e.getStatusCode().value()
            );

        } catch (ResponseStatusException e) {

            log.error(
                    "[GBIS API 응답 오류] status={}, reason={}",
                    e.getStatusCode().value(),
                    e.getReason()
            );

            throw e;

        } catch (Exception e) {

            log.error(
                    "[GBIS 위치정보 처리 실패] type={}",
                    e.getClass().getSimpleName(),
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GBIS 버스 위치정보 처리에 실패했습니다."
            );
        }
    }

    // =====================================
    // 차량 정보 없음
    // =====================================

    private Map<String, Object> createNoData(String busRouteId) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("status", "NO_DATA");
        result.put("provider", "GYEONGGI");
        result.put("busRouteId", busRouteId);
        result.put("count", 0);
        result.put("vehicles", List.of());
        result.put("message", "현재 운행 중인 차량 정보가 없습니다.");

        return result;
    }

    // =====================================
    // 혼잡도 코드 변환
    // =====================================

    private String getCongestionLabel(String code) {

        if (code == null) {
            return "정보없음";
        }

        return switch (code) {
            case "1" -> "여유";
            case "2" -> "보통";
            case "3" -> "혼잡";
            case "4" -> "매우혼잡";
            default -> "정보없음";
        };
    }

    // =====================================
    // 차량 상태 변환
    // =====================================

    private String getVehicleStateLabel(String code) {

        if (code == null) {
            return "정보없음";
        }

        return switch (code) {
            case "0" -> "교차로 통과";
            case "1" -> "정류소 도착";
            case "2" -> "정류소 출발";
            default -> "정보없음";
        };
    }

    // =====================================
    // JSON 배열/객체 정규화
    // =====================================

    private List<JsonNode> normalizeItems(JsonNode node) {

        List<JsonNode> items = new ArrayList<>();

        if (node == null || node.isMissingNode() || node.isNull()) {
            return items;
        }

        if (node.isArray()) {
            node.forEach(items::add);
        } else if (node.isObject()) {
            items.add(node);
        }

        return items;
    }

    private String getText(JsonNode node, String field) {

        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        String text = value.asText().trim();

        return text.isBlank() ? null : text;
    }

    private Integer getInteger(JsonNode node, String field) {

        String value = getText(node, field);

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
