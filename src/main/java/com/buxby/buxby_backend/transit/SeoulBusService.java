
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
public class SeoulBusService {

    private static final String BASE_URL =
            "http://ws.bus.go.kr/api/rest/arrive";

    private final RestClient client = RestClient.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${TAGO_API_KEY}")
    private String serviceKey;

    public Map<String, Object> getArrival(
            String stId, String busRouteId, int ord) {

        try {
            String xml = client.get()
                    .uri(BASE_URL + "/getArrInfoByRoute", builder ->
                            builder.queryParam("serviceKey", serviceKey)
                                    .queryParam("stId", stId)
                                    .queryParam("busRouteId", busRouteId)
                                    .queryParam("ord", ord)
                                    .build())
                    .retrieve()
                    .body(String.class);

            JsonNode root = xmlMapper.readTree(xml);
            JsonNode header = root.path("msgHeader");

            if (!"0".equals(header.path("headerCd").asText())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "서울시 API 오류: " +
                                header.path("headerMsg").asText());
            }

            JsonNode item = root.path("msgBody").path("itemList");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("routeName", item.path("rtNm").asText());
            result.put("stationName", item.path("stNm").asText());
            result.put("firstArrival", item.path("arrmsg1").asText());
            result.put("secondArrival", item.path("arrmsg2").asText());
            result.put("firstArrivalSeconds",
                    item.path("exps1").asInt());
            result.put("secondArrivalSeconds",
                    item.path("exps2").asInt());
            result.put("updatedAt", item.path("mkTm").asText());

            return result;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 버스 도착정보 조회 실패", e);
        }
    }
}
