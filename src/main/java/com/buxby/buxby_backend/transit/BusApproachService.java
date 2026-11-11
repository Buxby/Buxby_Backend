package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 승차 정류장에 내 노선 버스가 몇 정류장 전, 몇 초 후에 도착하는지 조회한다.
// TAGO가 정류장을 찾으면 TAGO, 못 찾으면(서울은 TAGO 미제공) 서울시 버스도착정보를 쓴다.
// 둘 다 정류장을 찾지 못하면 UNSUPPORTED_REGION을 돌려준다.
@Service
public class BusApproachService {

    public record Arrival(String routeNo, String routeId, int remainingStops, int arrivalSeconds) {}

    public record Approach(String status, String provider, Integer cityCode, String stopId, String stopName,
                           List<Arrival> arrivals, LocalDateTime checkedAt) {}

    record Stop(int cityCode, String nodeId, String name) {}

    private static final int NEARBY_ROWS = 100;
    private static final Pattern STOPS_BEFORE = Pattern.compile("\\[(\\d+)번째 전]");

    private final TagoService tago;
    private final SeoulBusAutoMatchService seoul;
    private final Map<String, Stop> stops = new ConcurrentHashMap<>();
    private final Set<String> notTago = ConcurrentHashMap.newKeySet();

    public BusApproachService(TagoService tago, SeoulBusAutoMatchService seoul) {
        this.tago = tago;
        this.seoul = seoul;
    }

    public Approach find(String stopId, String stopName, double latitude, double longitude,
                         List<String> routeIds, List<String> busNos) {
        // 서울 정류장도 경기·인천 노선이 지나면 TAGO에 같은 이름으로 등록돼 있어서(홍대입구역 → 인천 ICB313000422)
        // TAGO ID 일치 → 서울시 → TAGO 이름 일치 순으로 찾는다
        Optional<Stop> stop = resolveStop(stopId, stopName, latitude, longitude, false);
        if (stop.isEmpty()) {
            Approach seoulResult = seoulApproach(stopId, stopName, routeIds);
            if (!"UNSUPPORTED_REGION".equals(seoulResult.status())) return seoulResult;
            stop = resolveStop(stopId, stopName, latitude, longitude, true);
            if (stop.isEmpty()) return seoulResult;
        }

        Stop found = stop.get();
        JsonNode json = tago.fetch("arrival", "getSttnAcctoArvlPrearngeInfoList",
                found.cityCode(), null, null, found.nodeId(), 1, 100);

        List<Arrival> arrivals = TagoService.items(json).stream()
                .filter(item -> matches(item, routeIds, busNos))
                // 0초는 이미 도착했거나 정보가 없는 경우라 탈 수 있는 버스로 보지 않는다
                .filter(item -> item.path("arrtime").asInt(0) > 0)
                .map(item -> new Arrival(
                        item.path("routeno").asText(),
                        item.path("routeid").asText(),
                        item.path("arrprevstationcnt").asInt(),
                        item.path("arrtime").asInt()))
                .sorted(Comparator.comparingInt(Arrival::arrivalSeconds))
                .toList();

        return new Approach(arrivals.isEmpty() ? "NO_ARRIVAL" : "FOUND", "TAGO", found.cityCode(),
                found.nodeId(), found.name(), arrivals, LocalDateTime.now());
    }

    // 서울은 ODsay 정류장·노선 ID가 서울시 stId·busRouteId와 같다. 노선마다 첫째·둘째 버스를 모은다.
    private Approach seoulApproach(String stopId, String stopName, List<String> routeIds) {
        if (isBlank(stopId) || routeIds == null || routeIds.isEmpty()) {
            return unsupported(stopId, stopName);
        }

        boolean stationFound = false;
        List<Arrival> arrivals = new ArrayList<>();
        for (String routeId : routeIds) {
            for (Map<String, Object> item : seoul.routeArrivalItems(routeId)) {
                if (!stopId.equals(text(item, "stId"))) continue;
                stationFound = true;
                String routeNo = text(item, "rtNm");
                seoulArrival(routeNo, routeId, text(item, "arrmsg1"), text(item, "exps1"),
                        text(item, "staOrd"), text(item, "sectOrd1")).ifPresent(arrivals::add);
                seoulArrival(routeNo, routeId, text(item, "arrmsg2"), text(item, "exps2"),
                        text(item, "staOrd"), text(item, "sectOrd2")).ifPresent(arrivals::add);
                break;
            }
        }

        if (!stationFound) return unsupported(stopId, stopName);
        arrivals.sort(Comparator.comparingInt(Arrival::arrivalSeconds));
        return new Approach(arrivals.isEmpty() ? "NO_ARRIVAL" : "FOUND", "SEOUL", null,
                stopId, stopName, arrivals, LocalDateTime.now());
    }

    // 남은 정류장 수는 안내 문구의 "[n번째 전]"에서 읽고, 없으면 정류소 순번 - 버스 구간 순번으로 계산한다.
    // 운행종료·출발대기처럼 도착 예정 초가 0이면 탈 수 있는 버스로 보지 않는다.
    static Optional<Arrival> seoulArrival(String routeNo, String routeId, String message, String seconds,
                                          String stationOrder, String busSectionOrder) {
        int arrivalSeconds = number(seconds);
        if (arrivalSeconds <= 0) return Optional.empty();

        Matcher matcher = STOPS_BEFORE.matcher(message == null ? "" : message);
        int remainingStops;
        if (matcher.find()) {
            remainingStops = Integer.parseInt(matcher.group(1));
        } else {
            int station = number(stationOrder);
            int section = number(busSectionOrder);
            remainingStops = station > 0 && section > 0 ? Math.max(0, station - section) : 0;
        }
        return Optional.of(new Arrival(routeNo, routeId, remainingStops, arrivalSeconds));
    }

    // ODsay 정류장 ID는 TAGO nodeId와 같은 체계라 ID로 찾는다. byName이면 같은 이름의 가장 가까운 정류장도 쓴다.
    Optional<Stop> resolveStop(String stopId, String stopName, double latitude, double longitude, boolean byName) {
        String cacheKey = (byName || isBlank(stopId) ? stopName + "@" + latitude + "," + longitude : stopId);
        Stop cached = stops.get(cacheKey);
        if (cached != null) return Optional.of(cached);
        if (notTago.contains(cacheKey)) return Optional.empty();

        List<JsonNode> nearby = TagoService.items(tago.nearbyStops(latitude, longitude, NEARBY_ROWS));
        Comparator<JsonNode> nearest = Comparator.comparingDouble(item -> distance(item, latitude, longitude));
        // 접두사 없는 지역 ID는 여러 도시에 겹칠 수 있어(대전 DJB8001418·계룡 GRB8001418) 정확히 같은 ID, 가까운 순으로 고른다
        Optional<JsonNode> match = nearby.stream()
                .filter(item -> stopId != null && stopId.equals(item.path("nodeid").asText()))
                .findFirst()
                .or(() -> nearby.stream()
                        .filter(item -> sameId(item.path("nodeid").asText(), stopId))
                        .min(nearest))
                .or(() -> byName
                        ? nearby.stream()
                                .filter(item -> !isBlank(stopName) && stopName.equals(item.path("nodenm").asText()))
                                .min(nearest)
                        : Optional.empty());

        Optional<Stop> stop = match.map(item -> new Stop(
                item.path("citycode").asInt(), item.path("nodeid").asText(), item.path("nodenm").asText()));
        // 정류소 정보는 거의 바뀌지 않아 찾은 결과와 못 찾은 결과 모두 기억해 30초마다 다시 묻지 않는다
        if (stop.isPresent()) stops.put(cacheKey, stop.get());
        else notTago.add(cacheKey);
        return stop;
    }

    private static Approach unsupported(String stopId, String stopName) {
        return new Approach("UNSUPPORTED_REGION", null, null, stopId, stopName, List.of(), LocalDateTime.now());
    }

    private static boolean matches(JsonNode item, List<String> routeIds, List<String> busNos) {
        String routeId = item.path("routeid").asText();
        String routeNo = item.path("routeno").asText();
        return (routeIds != null && routeIds.stream().anyMatch(id -> sameId(routeId, id)))
                || (busNos != null && busNos.contains(routeNo));
    }

    // TAGO ID는 지역 접두사(DJB, GGB 등) + 지역 ID다. ODsay는 지역에 따라 접두사를 붙이기도(대전) 빼기도(경기) 한다.
    static boolean sameId(String tagoId, String odsayId) {
        if (isBlank(tagoId) || isBlank(odsayId)) return false;
        if (tagoId.equals(odsayId)) return true;
        String prefix = tagoId.substring(0, Math.max(0, tagoId.length() - odsayId.length()));
        return tagoId.endsWith(odsayId) && prefix.matches("[A-Z]{2,4}");
    }

    private static double distance(JsonNode item, double latitude, double longitude) {
        double dLat = item.path("gpslati").asDouble() - latitude;
        double dLng = item.path("gpslong").asDouble() - longitude;
        return dLat * dLat + dLng * dLng;
    }

    private static String text(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
