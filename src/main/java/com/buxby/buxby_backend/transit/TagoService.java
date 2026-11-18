
package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.buxby.buxby_backend.HttpClients;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TagoService {

    private final RestClient client = HttpClients.create();
    private final ObjectMapper mapper;
    private final String key;

    public TagoService(
            ObjectMapper mapper,
            @Value("${tago.api.key:}") String key
    ) {
        this.mapper = mapper;
        this.key = key;
    }

    public JsonNode fetch(
            String service,
            String operation,
            Integer cityCode,
            String routeId,
            String routeNo,
            String nodeId,
            int page,
            int rows
    ) {
        Map<String, Object> params = new LinkedHashMap<>();

        if (cityCode != null) {
            params.put("cityCode", cityCode);
        }

        if (routeId != null && !routeId.isBlank()) {
            params.put("routeId", routeId);
        }

        if (routeNo != null && !routeNo.isBlank()) {
            params.put("routeNo", routeNo);
        }

        if (nodeId != null && !nodeId.isBlank()) {
            params.put("nodeId", nodeId);
        }

        return call(service, operation, params, page, rows);
    }

    // 좌표 주변 정류소 조회
    public JsonNode nearbyStops(
            double latitude,
            double longitude,
            int rows
    ) {
        return call(
                "station",
                "getCrdntPrxmtSttnList",
                Map.of(
                        "gpsLati", latitude,
                        "gpsLong", longitude
                ),
                1,
                rows
        );
    }

    // TAGO 응답의 item을 리스트로 변환
    public static List<JsonNode> items(JsonNode json) {
        JsonNode item = json.path("response")
                .path("body")
                .path("items")
                .path("item");

        List<JsonNode> result = new ArrayList<>();

        if (item.isArray()) {
            item.forEach(result::add);
        } else if (item.isObject()) {
            result.add(item);
        }

        return result;
    }

    private JsonNode call(
            String service,
            String operation,
            Map<String, Object> params,
            int page,
            int rows
    ) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TAGO_API_KEY 환경변수를 설정하세요."
            );
        }

        String root = switch (service) {
            case "route" -> "BusRouteInfoInqireService";
            case "station" -> "BusSttnInfoInqireService";
            default -> "ArvlInfoInqireService";
        };

        UriComponentsBuilder uri = UriComponentsBuilder
                .fromUriString(
                        "https://apis.data.go.kr/1613000/"
                                + root + "/" + operation
                )
                .queryParam("serviceKey", key)
                .queryParam("_type", "json")
                .queryParam("pageNo", page)
                .queryParam("numOfRows", rows);

        params.forEach(uri::queryParam);

        try {
            URI target = uri.build().encode().toUri();

            String raw = client.get()
                    .uri(target)
                    .retrieve()
                    .body(String.class);

            JsonNode json = mapper.readTree(raw);

            JsonNode header = json.path("response")
                    .path("header");

            if (!"00".equals(
                    header.path("resultCode").asText()
            )) {
                String resultCode = header.path("resultCode")
                        .asText("UNKNOWN");

                String resultMsg = header.path("resultMsg")
                        .asText("알 수 없음");

                System.err.println(
                        "[TAGO ERROR] service=" + service
                                + ", operation=" + operation
                                + ", resultCode=" + resultCode
                                + ", resultMsg=" + resultMsg
                );

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "TAGO 응답 오류: " + resultMsg
                );
            }

            return json;

        } catch (ResponseStatusException ex) {
            throw ex;

        } catch (Exception ex) {
            // 인증키가 포함된 전체 요청 URL은 출력하지 않음
            System.err.println(
                    "[TAGO ERROR] service=" + service
                            + ", operation=" + operation
                            + ", exception="
                            + ex.getClass().getSimpleName()
            );

            // 예외의 원인 유형만 출력하여 인증키 노출 방지
            Throwable cause = ex.getCause();

            if (cause != null) {
                System.err.println(
                        "[TAGO ERROR] cause="
                                + cause.getClass().getSimpleName()
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "TAGO API 요청 실패 (인증키, 도시코드, API 제공 상태 확인)",
                    ex
            );
        }
    }
}
