package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 승차 정류장에 내 노선 버스가 몇 정류장 전, 몇 초 후에 도착하는지 TAGO로 조회한다.
// TAGO는 서울을 제공하지 않으므로 주변 정류소가 없으면 UNSUPPORTED_REGION을 돌려준다.
@Service
public class BusApproachService {

    public record Arrival(String routeNo, String routeId, int remainingStops, int arrivalSeconds) {}

    public record Approach(String status, String provider, Integer cityCode, String stopId, String stopName,
                           List<Arrival> arrivals, LocalDateTime checkedAt) {}

    record Stop(int cityCode, String nodeId, String name) {}

    private static final int NEARBY_ROWS = 100;

    private final TagoService tago;
    private final Map<String, Stop> stops = new ConcurrentHashMap<>();

    public BusApproachService(TagoService tago) {
        this.tago = tago;
    }

    public Approach find(String stopId, String stopName, double latitude, double longitude,
                         List<String> routeIds, List<String> busNos) {
        Optional<Stop> stop = resolveStop(stopId, stopName, latitude, longitude);
        if (stop.isEmpty()) {
            return new Approach("UNSUPPORTED_REGION", "TAGO", null, stopId, stopName, List.of(), LocalDateTime.now());
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

    // ODsay 정류장 ID는 TAGO nodeId와 같은 체계라 우선 ID로 찾고, 없으면 같은 이름의 가장 가까운 정류장을 쓴다
    Optional<Stop> resolveStop(String stopId, String stopName, double latitude, double longitude) {
        String cacheKey = isBlank(stopId) ? stopName + "@" + latitude + "," + longitude : stopId;
        Stop cached = stops.get(cacheKey);
        if (cached != null) return Optional.of(cached);

        List<JsonNode> nearby = TagoService.items(tago.nearbyStops(latitude, longitude, NEARBY_ROWS));
        Comparator<JsonNode> nearest = Comparator.comparingDouble(item -> distance(item, latitude, longitude));
        // 접두사 없는 지역 ID는 여러 도시에 겹칠 수 있어(대전 DJB8001418·계룡 GRB8001418) 정확히 같은 ID, 가까운 순으로 고른다
        Optional<JsonNode> match = nearby.stream()
                .filter(item -> stopId != null && stopId.equals(item.path("nodeid").asText()))
                .findFirst()
                .or(() -> nearby.stream()
                        .filter(item -> sameId(item.path("nodeid").asText(), stopId))
                        .min(nearest))
                .or(() -> nearby.stream()
                        .filter(item -> !isBlank(stopName) && stopName.equals(item.path("nodenm").asText()))
                        .min(nearest));

        Optional<Stop> stop = match.map(item -> new Stop(
                item.path("citycode").asInt(), item.path("nodeid").asText(), item.path("nodenm").asText()));
        stop.ifPresent(value -> stops.put(cacheKey, value));
        return stop;
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
