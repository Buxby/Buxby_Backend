package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TagoService {
    private final RestClient client = RestClient.create();
    private final ObjectMapper mapper;
    private final String key;
    public TagoService(ObjectMapper mapper, @Value("${tago.api.key:}") String key) {
        this.mapper = mapper;
        this.key = key;
    }
    public JsonNode fetch(String service, String operation, Integer cityCode, String routeId, String routeNo, String nodeId, int page, int rows) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (cityCode != null) params.put("cityCode", cityCode);
        if (routeId != null && !routeId.isBlank()) params.put("routeId", routeId);
        if (routeNo != null && !routeNo.isBlank()) params.put("routeNo", routeNo);
        if (nodeId != null && !nodeId.isBlank()) params.put("nodeId", nodeId);
        return call(service, operation, params, page, rows);
    }

    // 좌표 주변 정류소 (TAGO 버스정류소정보). 결과 항목에 도시코드가 들어 있다.
    public JsonNode nearbyStops(double latitude, double longitude, int rows) {
        return call("station", "getCrdntPrxmtSttnList", Map.of("gpsLati", latitude, "gpsLong", longitude), 1, rows);
    }

    // TAGO는 결과가 없으면 items를 빈 문자열로, 하나면 객체로, 여러 개면 배열로 준다
    public static List<JsonNode> items(JsonNode json) {
        JsonNode item = json.path("response").path("body").path("items").path("item");
        List<JsonNode> result = new ArrayList<>();
        if (item.isArray()) item.forEach(result::add);
        else if (item.isObject()) result.add(item);
        return result;
    }

    private JsonNode call(String service, String operation, Map<String, Object> params, int page, int rows) {
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TAGO_API_KEY 환경변수를 설정하세요.");
        String root = switch (service) {
            case "route" -> "BusRouteInfoInqireService";
            case "station" -> "BusSttnInfoInqireService";
            default -> "ArvlInfoInqireService";
        };
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://apis.data.go.kr/1613000/" + root + "/" + operation)
                .queryParam("serviceKey", key).queryParam("_type", "json")
                .queryParam("pageNo", page).queryParam("numOfRows", rows);
        params.forEach(uri::queryParam);
        try {
            // build().encode() avoids double-encoding already encoded API keys; use decoding key in env.
            URI target = uri.build().encode().toUri();
            String raw = client.get().uri(target).retrieve().body(String.class);
            JsonNode json = mapper.readTree(raw);
            JsonNode header = json.path("response").path("header");
            if (!"00".equals(header.path("resultCode").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TAGO 응답 오류: " + header.path("resultMsg").asText("알 수 없음"));
            }
            return json;
        } catch (ResponseStatusException ex) { throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TAGO API 요청 실패 (인증키, 도시코드, API 제공 상태 확인)", ex);
        }
    }
}
