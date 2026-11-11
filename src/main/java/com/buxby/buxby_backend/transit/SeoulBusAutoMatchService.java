
package com.buxby.buxby_backend.transit;

import com.buxby.buxby_backend.route.OdsayRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

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
        return match(startLongitude, startLatitude, endLongitude, endLatitude, null, null);
    }

    // 버스 번호를 주면 그 버스가 포함된 경로 중 최단 경로를 조회한다. 승차 정류장은 우선 조건이다.
    public Map<String, Object> match(
            double startLongitude,
            double startLatitude,
            double endLongitude,
            double endLatitude,
            String requestedBusNo,
            String requestedStation
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

        JsonNode selected = shortestBusPath(paths, requestedBusNo, requestedStation);

        if (selected == null && !isBlank(requestedStation)) {
            selected = shortestBusPath(paths, requestedBusNo, null);
        }

        if (selected == null) {
            return isBlank(requestedBusNo)
                    ? Map.of(
                            "status", "NO_BUS_ROUTE",
                            "message", "버스가 포함된 경로가 없습니다."
                    )
                    : Map.of(
                            "status", "NO_MATCHING_BUS_ROUTE",
                            "message", "선택한 버스가 포함된 경로가 없습니다."
                    );
        }

        int shortest = selected.path("info").path("totalTime").asInt();

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

    static JsonNode shortestBusPath(JsonNode paths, String busNo, String stationName) {
        JsonNode selected = null;
        int shortest = Integer.MAX_VALUE;

        for (JsonNode path : paths) {
            int minutes = path.path("info").path("totalTime").asInt(-1);

            if (minutes > 0 && minutes < shortest && hasBusSection(path, busNo, stationName)) {
                shortest = minutes;
                selected = path;
            }
        }

        return selected;
    }

    private static boolean hasBusSection(JsonNode path, String busNo, String stationName) {
        for (JsonNode section : path.path("subPath")) {
            if (section.path("trafficType").asInt() != 2) {
                continue;
            }

            boolean atStation = isBlank(stationName)
                    || stationName.equals(section.path("startName").asText());

            if (atStation && (isBlank(busNo) || hasBus(section, busNo))) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasBus(JsonNode section, String busNo) {
        for (JsonNode lane : section.path("lane")) {
            if (busNo.equals(lane.path("busNo").asText())) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // 노선 전체 정류소의 도착 정보 (서울시 getArrInfoByRouteAll). 조회 실패는 502로 알린다.
    public List<Map<String, Object>> routeArrivalItems(String busRouteId) {
        Map<String, Object> arrivals = getRouteArrivals(busRouteId);
        // 코드 4(결과 없음)는 서울 노선이 아닌 경우라 빈 목록으로 본다
        if (String.valueOf(arrivals.get("message")).endsWith("(코드: 4)")) {
            return List.of();
        }
        if (!"SUCCESS".equals(arrivals.get("status"))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "서울시 버스 도착정보 조회 실패: " + arrivals.get("message"));
        }
        return normalizeItems(arrivals.get("items"));
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
