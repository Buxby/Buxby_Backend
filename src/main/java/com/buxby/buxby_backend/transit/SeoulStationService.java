
package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SeoulStationService {

    private static final String BASE_URL =
            "http://ws.bus.go.kr/api/rest/stationinfo";

    private final RestClient client = RestClient.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${TAGO_API_KEY}")
    private String serviceKey;

    // 1. 정류장 이름 검색
    public Map<String, Object> searchStation(String stationName) {
        String xml = request("/getStationByName", "stSrch", stationName);
        JsonNode root = parseAndValidate(xml);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("searchKeyword", stationName);
        result.put("stations", xmlMapper.convertValue(
                root.path("msgBody").path("itemList"),
                Object.class
        ));
        return result;
    }

    // 2. 정류장 경유 버스 노선 조회
    public Map<String, Object> getRoutesByStation(String arsId) {
        String xml = request("/getRouteByStation", "arsId", arsId);
        JsonNode root = parseAndValidate(xml);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("arsId", arsId);
        result.put("routes", xmlMapper.convertValue(
                root.path("msgBody").path("itemList"),
                Object.class
        ));
        return result;
    }

    // 3. TM 좌표 기반 주변 정류장 검색
    public Map<String, Object> getNearbyStations(
            double tmX, double tmY, int radius) {

        if (radius <= 0 || radius > 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "검색 반경은 1~1000m로 입력하세요.");
        }

        String xml;
        try {
            xml = client.get()
                    .uri(BASE_URL + "/getStationByPos", builder ->
                            builder.queryParam("serviceKey", serviceKey)
                                    .queryParam("tmX", tmX)
                                    .queryParam("tmY", tmY)
                                    .queryParam("radius", radius)
                                    .build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 주변 정류장 조회 실패", e);
        }

        JsonNode root = parseAndValidate(xml);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("tmX", tmX);
        result.put("tmY", tmY);
        result.put("radius", radius);
        result.put("stations", xmlMapper.convertValue(
                root.path("msgBody").path("itemList"),
                Object.class
        ));
        return result;
    }

    private String request(String path, String param, String value) {
        try {
            return client.get()
                    .uri(BASE_URL + path, builder ->
                            builder.queryParam("serviceKey", serviceKey)
                                    .queryParam(param, value)
                                    .build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 API 호출 실패", e);
        }
    }

    private JsonNode parseAndValidate(String xml) {
        try {
            if (xml == null || xml.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "서울시 API 응답이 비어 있습니다.");
            }

            JsonNode root = xmlMapper.readTree(xml);
            JsonNode header = root.path("msgHeader");

            if (!"0".equals(header.path("headerCd").asText())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "서울시 API 오류: " +
                                header.path("headerMsg").asText());
            }

            return root;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 XML 응답 처리 실패", e);
        }
    }
}
