
package com.buxby.buxby_backend.transit;

import com.buxby.buxby_backend.route.OdsayRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeoulBusAutoMatchService {

    private static final String ARRIVAL_URL =
            "http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll";

    private final OdsayRouteService odsay;
    private final RestClient client = RestClient.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${TAGO_API_KEY}")
    private String serviceKey;

    public SeoulBusAutoMatchService(OdsayRouteService odsay) {
        this.odsay = odsay;
    }

    public Map<String, Object> match(
            double startLongitude,
            double startLatitude,
            double endLongitude,
            double endLatitude
    ) {
        JsonNode routes = odsay.search(
                startLongitude,
                startLatitude,
                endLongitude,
                endLatitude
        );

        JsonNode paths = routes.path("result").path("path");

        if (!paths.isArray() || paths.isEmpty()) {
            return Map.of(
                    "status", "NO_ROUTE",
                    "message", "검색된 대중교통 경로가 없습니다."
            );
        }

        JsonNode selected = null;
        int shortest = Integer.MAX_VALUE;

        for (JsonNode path : paths) {
            int minutes = path.path("info")
                    .path("totalTime").asInt(-1);

            boolean hasBus = false;

            for (JsonNode section : path.path("subPath")) {
                if (section.path("trafficType").asInt() == 2) {
                    hasBus = true;
                    break;
                }
            }

            if (hasBus && minutes > 0 && minutes < shortest) {
                shortest = minutes;
                selected = path;
            }
        }

        if (selected == null) {
            return Map.of(
                    "status", "NO_BUS_ROUTE",
                    "message", "버스가 포함된 경로가 없습니다."
            );
        }

        List<Map<String, Object>> busSections = new ArrayList<>();

        for (JsonNode section : selected.path("subPath")) {

            if (section.path("trafficType").asInt() != 2) {
                continue;
            }

            String stId = section.path("startLocalStationID")
                    .asText("");

            String arsId = section.path("startArsID")
                    .asText("");

            String stationName = section.path("startName")
                    .asText("");

            List<Map<String, Object>> matchedBuses =
                    new ArrayList<>();

            for (JsonNode lane : section.path("lane")) {

                String busNo = lane.path("busNo").asText("");
                String busRouteId = lane.path("busLocalBlID")
                        .asText("");

                Map<String, Object> bus = new LinkedHashMap<>();

                bus.put("busNo", busNo);
                bus.put("busRouteId", busRouteId);

                if (busRouteId.isBlank()) {
                    bus.put("arrivalStatus", "MISSING_ROUTE_ID");
                    matchedBuses.add(bus);
                    continue;
                }

                Map<String, Object> arrivals =
                        getRouteArrivals(busRouteId);

                bus.put("routeArrivalStatus",
                        arrivals.get("status"));

                if (arrivals.containsKey("message")) {
                    bus.put("routeArrivalMessage",
                            arrivals.get("message"));
                }

                if (arrivals.containsKey("errorType")) {
                    bus.put("errorType",
                            arrivals.get("errorType"));
                }

                List<Map<String, Object>> items =
                        normalizeItems(arrivals.get("items"));

                Map<String, Object> matchedStation = null;

                for (Map<String, Object> item : items) {

                    String actualStId = String.valueOf(
                            item.getOrDefault("stId", "")
                    );

                    String actualArsId = String.valueOf(
                            item.getOrDefault("arsId", "")
                    );

                    boolean stationIdMatches =
                            !stId.isBlank()
                                    && stId.equals(actualStId);

                    boolean arsIdMatches =
                            !arsId.isBlank()
                                    && arsId.equals(actualArsId);

                    if (stationIdMatches || arsIdMatches) {
                        matchedStation = item;
                        break;
                    }
                }

                if (matchedStation != null) {

                    bus.put("arrivalStatus", "FOUND");

                    bus.put("stationOrder",
                            matchedStation.get("staOrd"));

                    bus.put("firstArrival",
                            matchedStation.get("arrmsg1"));

                    bus.put("secondArrival",
                            matchedStation.get("arrmsg2"));

                    bus.put("firstArrivalSeconds",
                            matchedStation.get("exps1"));

                    bus.put("secondArrivalSeconds",
                            matchedStation.get("exps2"));

                    bus.put("arrival", matchedStation);

                } else if ("SUCCESS".equals(
                        arrivals.get("status"))) {

                    bus.put("arrivalStatus", "STATION_NOT_FOUND");

                } else {

                    bus.put("arrivalStatus", "ARRIVAL_LOOKUP_FAILED");
                }

                matchedBuses.add(bus);
            }

            Map<String, Object> sectionResult =
                    new LinkedHashMap<>();

            sectionResult.put("stationName", stationName);
            sectionResult.put("stId", stId);
            sectionResult.put("arsId", arsId);
            sectionResult.put("buses", matchedBuses);

            busSections.add(sectionResult);
        }

        boolean found = false;
        boolean lookupFailed = false;

        for (Map<String, Object> section : busSections) {

            Object busesObject = section.get("buses");

            if (!(busesObject instanceof List<?> buses)) {
                continue;
            }

            for (Object busObject : buses) {

                if (!(busObject instanceof Map<?, ?> bus)) {
                    continue;
                }

                if ("FOUND".equals(bus.get("arrivalStatus"))) {
                    found = true;
                }

                if ("ARRIVAL_LOOKUP_FAILED".equals(
                        bus.get("arrivalStatus"))) {
                    lookupFailed = true;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("status",
                found ? "SUCCESS"
                        : lookupFailed
                        ? "ARRIVAL_LOOKUP_FAILED"
                        : "NO_MATCHED_ARRIVAL");

        result.put("routeMinutes", shortest);

        result.put("pathType",
                selected.path("pathType").asInt());

        result.put("busSections", busSections);

        return result;
    }

    private Map<String, Object> getRouteArrivals(
            String busRouteId
    ) {
        try {
            String xml = client.get()
                    .uri(ARRIVAL_URL, builder -> builder
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("busRouteId", busRouteId)
                            .build())
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) {
                return failure(
                        "EMPTY_RESPONSE",
                        "서울시 API 응답이 비어 있습니다.",
                        null
                );
            }

            JsonNode root = xmlMapper.readTree(xml);

            JsonNode header = root.path("msgHeader");

            String code = header.path("headerCd").asText("");

            if (!"0".equals(code)) {
                return failure(
                        "UPSTREAM_ERROR",
                        header.path("headerMsg")
                                .asText("서울시 API 오류")
                                + " (코드: " + code + ")",
                        null
                );
            }

            JsonNode itemNode = root.path("msgBody")
                    .path("itemList");

            Object items;

            if (itemNode.isMissingNode()
                    || itemNode.isNull()) {

                items = List.of();

            } else {

                items = xmlMapper.convertValue(
                        itemNode,
                        Object.class
                );
            }

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put("status", "SUCCESS");
            result.put("items", items);

            return result;

        } catch (Exception e) {

            return failure(
                    "ARRIVAL_LOOKUP_FAILED",
                    safeErrorMessage(e),
                    e.getClass().getSimpleName()
            );
        }
    }

    private String safeErrorMessage(Exception e) {

        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return "오류 메시지 없음";
        }

        // 서비스키가 오류 메시지에 노출되지 않도록 처리
        if (serviceKey != null && !serviceKey.isBlank()) {
            message = message.replace(
                    serviceKey,
                    "[REDACTED]"
            );
        }

        return message;
    }

    private Map<String, Object> failure(
            String status,
            String message,
            String errorType
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("status", status);
        result.put("message", message);
        result.put("items", List.of());

        if (errorType != null) {
            result.put("errorType", errorType);
        }

        return result;
    }

    private List<Map<String, Object>> normalizeItems(
            Object rawItems
    ) {
        List<Map<String, Object>> items =
                new ArrayList<>();

        if (rawItems instanceof List<?> list) {

            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    items.add(toStringMap(map));
                }
            }

        } else if (rawItems instanceof Map<?, ?> map) {

            items.add(toStringMap(map));
        }

        return items;
    }

    private Map<String, Object> toStringMap(
            Map<?, ?> source
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : source.entrySet()) {

            result.put(
                    String.valueOf(entry.getKey()),
                    entry.getValue()
            );
        }

        return result;
    }
}
