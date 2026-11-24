
package com.buxby.buxby_backend.transit;

import com.buxby.buxby_backend.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

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
public class IncheonBusPositionService {

    private static final Logger log =
            LoggerFactory.getLogger(IncheonBusPositionService.class);

    private static final String BASE_URL =
            "https://apis.data.go.kr/6280000/busLocationService/getBusRouteLocation";

    private final RestClient client = HttpClients.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${incheon.bus.api.key:}")
    private String serviceKey;

    // =====================================
    // 인천 버스 실시간 차량 위치 및 혼잡도 조회
    // =====================================

    public Map<String, Object> getPositions(String busRouteId) {

        String apiKey = serviceKey == null
                ? ""
                : serviceKey.trim();

        if (apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "인천 버스 API 인증키가 설정되지 않았습니다."
            );
        }

        if (busRouteId == null || busRouteId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "인천 버스 노선 ID를 입력해 주세요."
            );
        }

        try {

            // =====================================
            // 1. API 요청 URL 생성
            // =====================================

            URI uri = UriComponentsBuilder
                    .fromUriString(BASE_URL)
                    .queryParam("ServiceKey", "{serviceKey}")
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "10")
                    .queryParam("routeId", "{routeId}")
                    .encode()
                    .buildAndExpand(Map.of(
                            "serviceKey", apiKey,
                            "routeId", busRouteId.trim()
                    ))
                    .toUri();

            // 인증키를 가린 실제 요청 URL 기록
            String safeRequestUri = uri.toString().replace(
                    apiKey,
                    "[REDACTED]"
            );

            log.info(
                    "[인천 API 요청 URL] {}",
                    safeRequestUri
            );

            // =====================================
            // 2. 인천시 API 호출
            // =====================================

            String xml = client.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            // 인천 API 원본 XML 진단
            // 원본 XML을 한 줄로 정리해서 로그 출력
            if (xml != null) {

                String safeXml = xml.replaceAll("\\s+", " ").trim();

                if (apiKey != null && !apiKey.isBlank()) {
                    safeXml = safeXml.replace(apiKey, "[REDACTED]");
                }

                if (safeXml.length() > 2500) {
                    safeXml = safeXml.substring(0, 2500);
                }

                log.warn("[인천 API 원본 응답] {}", safeXml);
            }

            // =====================================
            // 3. XML 파싱
            // =====================================

            JsonNode root = xmlMapper.readTree(xml);

            // =====================================
            // 3. API 게이트웨이 오류 확인
            // =====================================

            String gatewayCode =
                    findText(root, "returnReasonCode");

            String gatewayMessage =
                    findText(root, "errMsg");

            String gatewayDetail =
                    findText(root, "returnAuthMsg");

            if (gatewayCode != null) {

                log.error(
                        "[인천 API 게이트웨이 오류] code={}, message={}, detail={}",
                        gatewayCode,
                        gatewayMessage,
                        gatewayDetail
                );

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "인천 API 게이트웨이 오류: "
                                + gatewayCode
                                + " / "
                                + gatewayMessage
                );
            }

            // 정상 API 응답 확인
            String resultCode =
                    findText(root, "resultCode");

            String resultMessage =
                    findText(root, "resultMsg");

            // 인천 API 정상 응답 코드는 0
            if (!"0".equals(resultCode)
                    && !"00".equals(resultCode)) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "인천 버스 API 오류: "
                                + (resultMessage != null
                                ? resultMessage
                                : "응답 코드 " + resultCode)
                );
            }

            // =====================================
            // 4. 차량 목록 추출
            // =====================================

            List<JsonNode> rawItems = extractItems(root);

            List<Map<String, Object>> vehicles =
                    new ArrayList<>();

            for (JsonNode item : rawItems) {

                Map<String, Object> vehicle =
                        new LinkedHashMap<>();

                String vehicleId =
                        getText(item, "BUSID");

                String congestionCode =
                        getText(item, "CONGESTION");

                Integer remainSeat =
                        getInteger(item, "REMAIND_SEAT");

                // 255는 정보 미제공
                if (remainSeat != null && remainSeat == 255) {
                    remainSeat = null;
                }

                // 차량 기본 정보
                vehicle.put("vehicleId", vehicleId);

                vehicle.put(
                        "vehicleNumber",
                        getText(item, "BUS_NUM_PLATE")
                );

                // 최근 정류장 정보
                vehicle.put(
                        "currentStationId",
                        getText(item, "LATEST_STOP_ID")
                );

                vehicle.put(
                        "currentStationName",
                        getText(item, "LATEST_STOP_NAME")
                );

                vehicle.put(
                        "stationSequence",
                        getText(item, "LATEST_STOPSEQ")
                );

                vehicle.put(
                        "nodeSequence",
                        getText(item, "PATHSEQ")
                );

                // 진행 방향
                String directionCode =
                        getText(item, "DIRCD");

                vehicle.put("directionCode", directionCode);
                vehicle.put(
                        "direction",
                        getDirectionLabel(directionCode)
                );

                // 혼잡도
                String congestionLabel =
                        getCongestionLabel(congestionCode);

                vehicle.put("congestionCode", congestionCode);
                vehicle.put("congestion", congestionLabel);
                vehicle.put("congestionLabel", congestionLabel);

                // 잔여 좌석
                vehicle.put("remainSeatCount", remainSeat);

                // 기타 차량 정보
                vehicle.put(
                        "lowPlate",
                        getText(item, "LOW_TP_CD")
                );

                String lastBusCode =
                        getText(item, "LASTBUSYN");

                vehicle.put("lastBusCode", lastBusCode);
                vehicle.put(
                        "lastBus",
                        lastBusCode == null
                                ? null
                                : "1".equals(lastBusCode)
                                ? "막차"
                                : "일반"
                );

                vehicles.add(vehicle);
            }

            // 차량 ID가 없는 항목 제거
            vehicles.removeIf(
                    vehicle -> vehicle.get("vehicleId") == null
            );

            // =====================================
            // 5. 결과 반환
            // =====================================

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "status",
                    vehicles.isEmpty() ? "NO_DATA" : "SUCCESS"
            );

            result.put("provider", "INCHEON");
            result.put("busCityCode", 2000);
            result.put("busRouteId", busRouteId);
            result.put("count", vehicles.size());
            result.put("vehicles", vehicles);

            result.put(
                    "totalCount",
                    findText(root, "totalCount")
            );

            return result;

        } catch (RestClientResponseException e) {

            String responseBody = e.getResponseBodyAsString();

            // 인증키가 로그에 노출되지 않도록 처리
            if (responseBody != null
                    && serviceKey != null
                    && !serviceKey.isBlank()) {

                responseBody = responseBody.replace(
                        serviceKey,
                        "[REDACTED]"
                );
            }

            // 로그 길이 제한
            if (responseBody != null && responseBody.length() > 1000) {
                responseBody = responseBody.substring(0, 1000);
            }

            log.error(
                    "[인천 버스 API HTTP 오류] status={}, response={}",
                    e.getStatusCode().value(),
                    responseBody
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "인천 버스 API HTTP 오류: "
                            + e.getStatusCode().value()
            );

        } catch (ResponseStatusException e) {

            log.error(
                    "[인천 버스 API 응답 오류] status={}, reason={}",
                    e.getStatusCode().value(),
                    e.getReason()
            );

            throw e;

        } catch (Exception e) {

            log.error(
                    "[인천 버스 위치정보 처리 실패] type={}",
                    e.getClass().getSimpleName(),
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "인천 버스 위치정보 처리에 실패했습니다."
            );
        }
    }

    // =====================================
    // XML에서 차량 목록 추출
    // =====================================

    private List<JsonNode> extractItems(JsonNode root) {

        List<JsonNode> items = new ArrayList<>();

        // msgBody 아래의 모든 XML 요소 탐색
        JsonNode body = findNode(root, "msgBody");

        if (body == null) {
            return items;
        }

        collectItems(body, items);

        return items;
    }


    // =====================================
    // XML 트리에서 BUSID가 있는 차량 객체 추출
    // =====================================

    private void collectItems(
            JsonNode node,
            List<JsonNode> items
    ) {

        if (node == null || node.isNull()) {
            return;
        }

        // 배열인 경우 내부 요소 순회
        if (node.isArray()) {

            node.forEach(item ->
                    collectItems(item, items)
            );

            return;
        }

        if (!node.isObject()) {
            return;
        }

        // 실제 차량 객체인지 확인
        if (node.has("BUSID")) {

            items.add(node);

            return;
        }

        // itemList, item 등 모든 하위 XML 요소 탐색
        var fields = node.fields();

        while (fields.hasNext()) {

            JsonNode child = fields.next().getValue();

            collectItems(child, items);
        }
    }

    // =====================================
    // JSON/XML 노드 검색
    // =====================================

    private JsonNode findNode(JsonNode node, String field) {

        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {

            JsonNode direct = node.get(field);

            if (direct != null) {
                return direct;
            }

            var fields = node.fields();

            while (fields.hasNext()) {

                JsonNode found =
                        findNode(fields.next().getValue(), field);

                if (found != null) {
                    return found;
                }
            }
        }

        if (node.isArray()) {

            for (JsonNode child : node) {

                JsonNode found = findNode(child, field);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private String findText(JsonNode node, String field) {

        JsonNode value = findNode(node, field);

        if (value == null || value.isNull()) {
            return null;
        }

        String text = value.asText().trim();

        return text.isBlank() ? null : text;
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

    // =====================================
    // 혼잡도 변환
    // =====================================

    private String getCongestionLabel(String code) {

        if (code == null) {
            return "정보없음";
        }

        return switch (code) {
            case "1" -> "여유";
            case "2" -> "보통";
            case "3" -> "혼잡";
            case "255" -> "정보없음";
            default -> "정보없음";
        };
    }

    // =====================================
    // 진행 방향 변환
    // =====================================

    private String getDirectionLabel(String code) {

        if (code == null) {
            return "정보없음";
        }

        return switch (code) {
            case "0" -> "상행";
            case "1" -> "하행";
            case "2" -> "순환";
            default -> "정보없음";
        };
    }
}