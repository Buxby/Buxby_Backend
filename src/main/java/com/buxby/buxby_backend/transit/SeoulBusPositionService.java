
package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeoulBusPositionService {

    private static final String BASE_URL =
            "http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid";

    private final RestClient client = RestClient.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${TAGO_API_KEY}")
    private String serviceKey;

    // 노선별 운행 차량 위치 및 혼잡도 조회
    public Map<String, Object> getPositions(String busRouteId) {

        if (busRouteId == null || busRouteId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "버스 노선 ID를 입력해 주세요."
            );
        }

        try {

            // API 요청 URL 생성
            URI uri = UriComponentsBuilder
                    .fromUriString(BASE_URL)
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("busRouteId", busRouteId)
                    .build()
                    .encode()
                    .toUri();

            // 서울시 API 호출
            String xml = client.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) {
                throw new IllegalStateException(
                        "서울시 버스 위치정보 응답이 비어 있습니다."
                );
            }

            // XML 파싱
            JsonNode root = xmlMapper.readTree(xml);

            JsonNode header = root.path("msgHeader");


            String headerCode =
                    header.path("headerCd").asText("");

            // 현재 조회 가능한 버스 차량이 없는 경우
            if ("4".equals(headerCode)) {

                Map<String, Object> result =
                        new LinkedHashMap<>();

                result.put("status", "NO_DATA");
                result.put("busRouteId", busRouteId);
                result.put("count", 0);
                result.put("vehicles", List.of());
                result.put(
                        "message",
                        "현재 조회된 운행 차량 정보가 없습니다."
                );

                return result;
            }

            // 그 외 실제 오류 처리
            if (!"0".equals(headerCode)) {

                String message = header.path("headerMsg")
                        .asText("서울시 버스 위치정보 API 오류");

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        message + " (코드: " + headerCode + ")"
                );
            }


            // 차량 목록 추출
            JsonNode itemNode = root.path("msgBody")
                    .path("itemList");

            List<JsonNode> rawItems = normalizeItems(itemNode);

            List<Map<String, Object>> vehicles =
                    new ArrayList<>();

            for (JsonNode item : rawItems) {

                Map<String, Object> vehicle =
                        new LinkedHashMap<>();

                String congestionCode =
                        getText(item, "congetion");

                // 차량 정보
                vehicle.put("vehicleId", getText(item, "vehId"));
                vehicle.put("vehicleNumber", getText(item, "plainNo"));

                // 다음 정류장 정보
                vehicle.put("nextStopId", getText(item, "nextStId"));
                vehicle.put(
                        "nextStopArrivalSeconds",
                        getText(item, "nextStTm")
                );

                // 차량 위치
                vehicle.put(
                        "longitude",
                        getText(item, "gpsX")
                );

                vehicle.put(
                        "latitude",
                        getText(item, "gpsY")
                );

                // 혼잡도
                vehicle.put("congestionCode", congestionCode);
                vehicle.put(
                        "congestion",
                        getCongestionLabel(congestionCode)
                );

                // 만차 여부: 원본 값을 그대로 보존
                vehicle.put(
                        "isFullFlag",
                        getText(item, "isFullFlag")
                );

                // 정류장 도착 여부
                vehicle.put(
                        "stopFlag",
                        getText(item, "stopFlag")
                );

                // 데이터 수집 시각
                vehicle.put(
                        "dataTime",
                        getText(item, "dataTm")
                );

                vehicles.add(vehicle);
            }

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put("status", "SUCCESS");
            result.put("busRouteId", busRouteId);
            result.put("count", vehicles.size());
            result.put("vehicles", vehicles);

            return result;


        } catch (RestClientResponseException e) {

            // 서울시 API 자체의 HTTP 오류
            String responseBody = e.getResponseBodyAsString();

            // 응답에 인증키가 포함되는 경우 숨김
            if (responseBody != null && serviceKey != null) {
                responseBody = responseBody.replace(
                        serviceKey,
                        "[REDACTED]"
                );
            }

            // 로그가 지나치게 길어지지 않도록 제한
            if (responseBody != null && responseBody.length() > 500) {
                responseBody = responseBody.substring(0, 500);
            }

            log.error(
                    "[서울시 버스 API HTTP 오류] status={}, response={}",
                    e.getStatusCode().value(),
                    responseBody
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 버스 API HTTP 오류: "
                            + e.getStatusCode().value()
            );

        } catch (ResponseStatusException e) {

            // 서울시 API가 XML로 반환한 오류코드 확인
            log.error(
                    "[서울시 버스 API 응답 오류] status={}, reason={}",
                    e.getStatusCode().value(),
                    e.getReason()
            );

            throw e;

        } catch (Exception e) {

            // XML 파싱 및 네트워크 등 기타 오류 확인
            log.error(
                    "[서울시 버스 위치정보 처리 실패] type={}",
                    e.getClass().getSimpleName(),
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 버스 위치정보 조회 실패. "
                            + "IntelliJ 서버 로그를 확인해 주세요."
            );
        }

    }

    // 혼잡도 코드 변환
    private String getCongestionLabel(String code) {

        return switch (code) {
            case "0" -> "정보없음";
            case "3" -> "여유";
            case "4" -> "보통";
            case "5" -> "혼잡";
            case "6" -> "매우혼잡";
            default -> "확인불가";
        };
    }

    // XML에서 문자열 추출
    private String getText(JsonNode node, String field) {

        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        String text = value.asText();

        return text.isBlank() ? null : text;
    }

    // itemList가 단일 객체인지 배열인지 구분
    private List<JsonNode> normalizeItems(JsonNode itemNode) {

        List<JsonNode> items = new ArrayList<>();

        if (itemNode.isArray()) {

            itemNode.forEach(items::add);

        } else if (itemNode.isObject()) {

            items.add(itemNode);

        }

        return items;
    }

    private static final Logger log =
            LoggerFactory.getLogger(SeoulBusPositionService.class);
}
