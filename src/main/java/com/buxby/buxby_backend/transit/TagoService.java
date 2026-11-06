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
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TAGO_API_KEY 환경변수를 설정하세요.");
        String root = service.equals("route") ? "BusRouteInfoInqireService" : "ArvlInfoInqireService";
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://apis.data.go.kr/1613000/" + root + "/" + operation)
                .queryParam("serviceKey", key).queryParam("_type", "json")
                .queryParam("pageNo", page).queryParam("numOfRows", rows);
        if (cityCode != null) uri.queryParam("cityCode", cityCode);
        if (routeId != null && !routeId.isBlank()) uri.queryParam("routeId", routeId);
        if (routeNo != null && !routeNo.isBlank()) uri.queryParam("routeNo", routeNo);
        if (nodeId != null && !nodeId.isBlank()) uri.queryParam("nodeId", nodeId);
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
