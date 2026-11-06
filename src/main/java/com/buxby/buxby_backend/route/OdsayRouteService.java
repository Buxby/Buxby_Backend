package com.buxby.buxby_backend.route;

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

@Service
public class OdsayRouteService {
    private final RestClient client = RestClient.create();
    private final ObjectMapper mapper;
    private final String key;
    public OdsayRouteService(ObjectMapper mapper, @Value("${odsay.api.key:}") String key) {
        this.mapper = mapper; this.key = key;
    }
    public JsonNode search(double startLongitude, double startLatitude, double endLongitude, double endLatitude) {
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ODSay API 키가 필요합니다 (ODSAY_API_KEY).");
        if (!Double.isFinite(startLatitude) || !Double.isFinite(endLatitude) || !Double.isFinite(startLongitude) || !Double.isFinite(endLongitude) || Math.abs(startLatitude)>90 || Math.abs(endLatitude)>90 || Math.abs(startLongitude)>180 || Math.abs(endLongitude)>180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "좌표 범위가 올바르지 않습니다.");
        try {
            URI uri = UriComponentsBuilder.fromUriString("https://api.odsay.com/v1/api/searchPubTransPathT")
                .queryParam("apiKey", key).queryParam("SX", startLongitude).queryParam("SY", startLatitude)
                .queryParam("EX", endLongitude).queryParam("EY", endLatitude)
                .queryParam("OPT", 0).build().encode().toUri();
            JsonNode json = mapper.readTree(client.get().uri(uri).retrieve().body(String.class));
            if (json.has("error")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ODsay 오류: " + json.path("error").path("msg").asText("조회 실패"));
            return json;
        } catch (RestClientResponseException e) {
            System.err.println("[ODsay] HTTP 오류 코드: " + e.getStatusCode());
            System.err.println("[ODsay] 오류 응답: " + e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ODsay API 요청 실패 (서버 로그 확인)", e);
        } catch (ResponseStatusException e) { throw e;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ODsay 경로 검색 실패", e); }
    }
}
