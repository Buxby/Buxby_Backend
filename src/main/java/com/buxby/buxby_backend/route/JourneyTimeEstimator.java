package com.buxby.buxby_backend.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.buxby.buxby_backend.transit.BusApproachService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JourneyTimeEstimator {
    public record TimeEstimate(int minMinutes, int maxMinutes, String reliability, String explanation) {}

    // 대중교통 한 구간에서 기다릴 수 있는 최대 시간. 배차 간격이 아주 긴 노선(시외·농어촌)도
    // 알람이 지나치게 앞당겨지지 않도록 상한을 둔다. 배차 간격 정보가 없으면 기본값을 쓴다.
    public static final int MAX_WAIT_PER_LEG = 30;
    public static final int DEFAULT_WAIT_PER_LEG = 5;

    /**
     * ODsay totalTime은 탑승·도보 시간의 합이고 버스·지하철을 기다리는 시간은 들어 있지 않다.
     * 그래서 최소 = totalTime, 최대 = totalTime + 구간별 배차 간격(intervalTime) 합으로 잡는다.
     * (프론트 src/lib/routes.js의 최대 소요 시간과 같은 규칙)
     */
    public TimeEstimate estimate(JsonNode path) {
        int total = path.path("info").path("totalTime").asInt(-1);
        if (total <= 0) throw new IllegalArgumentException("ODsay 이동시간 정보가 없습니다.");
        int waitMargin = waitMargin(path, false);
        return new TimeEstimate(total, total + waitMargin, "ESTIMATED",
                "ODsay 탑승·도보 시간에 구간별 배차 간격만큼의 최대 대기 시간을 더한 추정치입니다.");
    }

    /** 대중교통 구간별 최대 대기 시간 합. skipFirstLeg면 첫 탑승 대기(실시간으로 확인된 경우)는 뺀다. */
    public static int waitMargin(JsonNode path, boolean skipFirstLeg) {
        int sum = 0;
        boolean first = true;
        for (JsonNode section : path.path("subPath")) {
            int type = section.path("trafficType").asInt();
            if (type != 1 && type != 2) continue;
            if (first && skipFirstLeg) {
                first = false;
                continue;
            }
            first = false;
            int interval = section.path("intervalTime").asInt(0);
            sum += interval > 0 ? Math.min(interval, MAX_WAIT_PER_LEG) : DEFAULT_WAIT_PER_LEG;
        }
        return sum;
    }

    public Map<String, Object> matchSubway(JsonNode section, Map<String, Object> response) {
        Map<String, Object> result = new LinkedHashMap<>();
        String station = section.path("startName").asText("").trim();
        String line = section.path("lane").path(0).path("name").asText("").trim();
        String nextStation = nextStation(section);
        result.put("stationName", station);
        result.put("routeLine", line);
        result.put("nextStation", nextStation);
        result.put("fetchedAt", response.get("fetchedAt"));
        if (!"SUCCESS".equals(response.get("status"))) {
            result.put("status", "NO_DATA");
            return result;
        }
        if (line.isBlank() || nextStation.isBlank()) {
            result.put("status", "DIRECTION_UNVERIFIED");
            result.put("message", "ODsay 경로에서 호선 또는 다음 역을 확인할 수 없습니다.");
            return result;
        }
        Object rawList = response.get("arrivals");
        if (!(rawList instanceof List<?> arrivals)) {
            result.put("status", "NO_DATA");
            return result;
        }
        List<Map<String, Object>> matching = new ArrayList<>();
        for (Object item : arrivals) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String arrivalLine = string(raw.get("lineName"));
            if (!sameLine(line, arrivalLine)) continue;
            String trainLine = string(raw.get("trainLineNm"));
            // Seoul API trainLineNm often says '... - [next station]방면'.
            // A generic destination name is not sufficient evidence of direction.
            String direction = directionStation(trainLine);
            if (!normalize(direction).equals(normalize(nextStation))) continue;
            Object eta = raw.get("adjustedArrivalSeconds");
            if (!(eta instanceof Number seconds) || seconds.intValue() < 0) continue;
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("trainLineNm", trainLine);
            candidate.put("updnLine", raw.get("updnLine"));
            candidate.put("arrivalSeconds", seconds.intValue());
            matching.add(candidate);
        }
        result.put("matchingTrains", matching);
        if (matching.isEmpty()) {
            result.put("status", "DIRECTION_UNVERIFIED");
            result.put("message", "다음 역과 방향이 확실하게 일치하는 열차를 찾지 못했습니다.");
        } else {
            result.put("status", "DIRECTION_MATCHED");
            result.put("message", "호선과 다음 역 방향이 일치하는 열차를 찾았습니다.");
        }
        return result;
    }

    public TimeEstimate applyRealtime(JsonNode path, TimeEstimate baseline,
                                      Map<String, Object> live, int walkMinutes) {
        if (!"DIRECTION_MATCHED".equals(live.get("status"))) return baseline;
        Object raw = live.get("matchingTrains");
        if (!(raw instanceof List<?> trains)) return baseline;
        int waitSeconds = Integer.MAX_VALUE;
        for (Object item : trains) {
            if (!(item instanceof Map<?, ?> train)) continue;
            Object eta = train.get("arrivalSeconds");
            if (!(eta instanceof Number n)) continue;
            // Ignore trains arriving before the passenger reaches the platform.
            int wait = n.intValue() - walkMinutes * 60;
            if (wait >= 60 && wait < waitSeconds) waitSeconds = wait;
        }
        if (waitSeconds == Integer.MAX_VALUE) {
            live.put("status", "NO_BOARDABLE_TRAIN");
            return baseline;
        }
        int total = path.path("info").path("totalTime").asInt(-1);
        int segmentSum = 0;
        for (JsonNode section : path.path("subPath")) {
            segmentSum += Math.max(0, section.path("sectionTime").asInt(0));
        }
        // Only replace a demonstrable residual wait component; never add ETA to totalTime.
        int residualWait = total - segmentSum;
        if (residualWait < 1 || residualWait > 15) {
            live.put("status", "WAIT_BASELINE_UNKNOWN");
            live.put("message", "ODsay의 대기시간을 분리할 수 없어 이동시간은 변경하지 않았습니다.");
            return baseline;
        }
        int actualWait = (int) Math.ceil(waitSeconds / 60.0);
        int adjusted = Math.max(1, total - residualWait + actualWait);
        // 첫 탑승 대기는 실시간으로 확인했으니 환승 구간의 대기만 남긴다
        int uncertainty = waitMargin(path, true);
        live.put("status", "REALTIME_APPLIED");
        live.put("matchedArrivalSeconds", waitSeconds + walkMinutes * 60);
        live.put("walkingToBoardingMinutes", walkMinutes);
        live.put("estimatedPlatformWaitMinutes", actualWait);
        live.put("replacedOdsayResidualMinutes", residualWait);
        live.put("message", "확인된 열차 대기시간으로 ODsay 잔여 대기시간을 대체한 참고 추정치입니다.");
        return new TimeEstimate(adjusted, adjusted + uncertainty, "REALTIME_PARTIAL",
                "지하철 첫 탑승 대기시간만 실시간 보정했습니다.");
    }

    /** Adjust only a verifiable first-bus wait. Never add an ETA to ODsay totalTime. */
    public TimeEstimate applyRealtimeBus(JsonNode path, TimeEstimate baseline,
                                         Map<String, Object> live,
                                         List<BusApproachService.Arrival> arrivals,
                                         int walkingMinutes) {
        // A residual across multiple transit legs cannot be attributed to the first bus.
        int transitLegs = 0;
        int segmentSum = 0;
        for (JsonNode section : path.path("subPath")) {
            int type = section.path("trafficType").asInt();
            if (type == 1 || type == 2) transitLegs++;
            segmentSum += Math.max(0, section.path("sectionTime").asInt(0));
        }
        if (transitLegs != 1) {
            live.put("status", "WAIT_BASELINE_UNKNOWN");
            live.put("message", "환승 경로의 대기시간을 구분할 수 없어 기존 추정치를 유지합니다.");
            return baseline;
        }
        int total = path.path("info").path("totalTime").asInt(-1);
        int residual = total - segmentSum;
        // Some ODsay routes report totalTime equal to the sum of sectionTime,
        // so there is no demonstrably separable waiting component.
        // Still identify a boardable bus and provide a clearly labelled scenario.
        boolean replaceableWait = residual >= 1 && residual <= 15;
        int bestWaitSeconds = Integer.MAX_VALUE;
        BusApproachService.Arrival matched = null;
        for (BusApproachService.Arrival arrival : arrivals) {
            int platformWait = arrival.arrivalSeconds() - walkingMinutes * 60;
            if (platformWait >= 60 && platformWait < bestWaitSeconds) {
                bestWaitSeconds = platformWait;
                matched = arrival;
            }
        }
        if (matched == null) {
            live.put("status", "NO_BOARDABLE_BUS");
            live.put("message", "정류장 도착 후 탑승 가능한 버스를 확인하지 못했습니다.");
            return baseline;
        }
        int actualWait = (bestWaitSeconds + 59) / 60;
        if (!replaceableWait) {
            // Do not silently overwrite ODsay's baseline when its wait is unknown.
            // This scenario assumes sectionTime represents travel/walking only.
            int scenario = Math.max(1, segmentSum + actualWait);
            live.put("status", "REALTIME_SCENARIO_ONLY");
            live.put("matchedBusNo", matched.routeNo());
            live.put("matchedArrivalSeconds", matched.arrivalSeconds());
            live.put("walkingToStopMinutes", walkingMinutes);
            live.put("estimatedStopWaitMinutes", actualWait);
            live.put("odsayResidualMinutes", residual);
            live.put("scenarioMinMinutes", scenario);
            live.put("scenarioMaxMinutes", scenario + waitMargin(path, true));
            live.put("scenarioAssumption", "ODsay 구간별 sectionTime에 버스 대기시간이 포함되지 않았다고 가정");
            live.put("message", "실시간 버스 도착정보로 참고 시나리오를 계산했습니다. ODsay 대기시간 중복 여부가 불확실하여 기본 추정치는 유지합니다.");
            return baseline;
        }
        int adjusted = Math.max(1, total - residual + actualWait);
        live.put("status", "REALTIME_APPLIED");
        live.put("matchedBusNo", matched.routeNo());
        live.put("matchedArrivalSeconds", matched.arrivalSeconds());
        live.put("walkingToStopMinutes", walkingMinutes);
        live.put("estimatedStopWaitMinutes", actualWait);
        live.put("replacedOdsayResidualMinutes", residual);
        live.put("message", "첫 버스 대기시간을 반영한 참고 추정치입니다. 교통 지연은 별도 반영되지 않습니다.");
        int uncertainty = waitMargin(path, true);
        return new TimeEstimate(adjusted, adjusted + uncertainty, "REALTIME_PARTIAL",
                "첫 버스 도착정보로 대기시간만 보정한 추정치입니다.");
    }

    private String nextStation(JsonNode section) {
        JsonNode stations = section.path("passStopList").path("stations");
        if (stations.isArray() && stations.size() > 1) {
            return stations.get(1).path("stationName").asText("").trim();
        }
        return "";
    }

    private String directionStation(String trainLine) {
        int idx = trainLine.lastIndexOf('-');
        if (idx < 0) return "";
        String suffix = trainLine.substring(idx + 1).trim();
        if (!suffix.endsWith("방면")) return "";
        return suffix.substring(0, suffix.length() - 2).trim();
    }

    private boolean sameLine(String expected, String actual) {
        String a = normalize(expected).replace("수도권", "");
        String b = normalize(actual);
        if (a.equals(b)) return true;
        for (int n = 1; n <= 9; n++) {
            if (a.equals(n + "호선") && b.equals(n + "호선")) return true;
        }
        return false;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").replace("역", "").trim();
    }

    private String string(Object value) { return value == null ? "" : value.toString(); }
}
