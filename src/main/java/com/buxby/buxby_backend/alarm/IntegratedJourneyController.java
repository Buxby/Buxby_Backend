package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.ai.GeminiService;
import com.buxby.buxby_backend.auth.CurrentUserService;
import com.buxby.buxby_backend.route.JourneyTimeEstimator;
import com.buxby.buxby_backend.route.OdsayRouteService;
import com.buxby.buxby_backend.transit.SeoulBusAutoMatchService;
import com.buxby.buxby_backend.transit.BusApproachService;
import java.util.ArrayList;
import java.util.List;
import com.buxby.buxby_backend.transit.subway.SeoulSubwayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/journey")
public class IntegratedJourneyController {

    private final GeminiService gemini;
    private final OdsayRouteService odsay;
    private final PreparationController preparation;
    private final AlarmReservationRepository reservations;
    private final SeoulBusAutoMatchService busAutoMatch;
    private final BusApproachService busApproach;
    private final ObjectMapper mapper;
    private final CurrentUserService currentUser;
    private final JourneyTimeEstimator timeEstimator;
    private final SeoulSubwayService subwayService;

    public IntegratedJourneyController(
            GeminiService gemini,
            OdsayRouteService odsay,
            PreparationController preparation,
            AlarmReservationRepository reservations,
            SeoulBusAutoMatchService busAutoMatch,
            BusApproachService busApproach,
            ObjectMapper mapper,
            CurrentUserService currentUser,
            JourneyTimeEstimator timeEstimator,
            SeoulSubwayService subwayService
    ) {
        this.gemini = gemini;
        this.odsay = odsay;
        this.preparation = preparation;
        this.reservations = reservations;
        this.busAutoMatch = busAutoMatch;
        this.busApproach = busApproach;
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.timeEstimator = timeEstimator;
        this.subwayService = subwayService;
    }

    public record JourneyRequest(
            String message,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double startLongitude,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double startLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double endLongitude,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double endLatitude,
            LocalDateTime desiredArrivalTime,
            @Min(0) @Max(180) Integer bufferMinutes,
            String destination,
            Boolean saveReservation
    ) {}

    public record JourneyResponse(
            String status,
            String message,
            Map<String, Object> interpreted,
            Map<String, Object> route,
            Integer routeMinutes,
            Integer preparationMinutes,
            LocalDateTime desiredArrivalTime,
            LocalDateTime leaveHomeTime,
            LocalDateTime wakeTime,
            Long reservationId,
            String clientAction,
            Map<String, Object> realtimeBus,
            Integer minRouteMinutes,
            Integer maxRouteMinutes,
            Map<String, Object> realtimeSubway
    ) {}

    @PostMapping("/plan")
    public JourneyResponse plan(
            @Valid @RequestBody JourneyRequest req,
            Authentication authentication
    ) {
        String userId = currentUser.requireUserId(authentication);
        Map<String, Object> interpreted = new LinkedHashMap<>();
        LocalDateTime arrival = req.desiredArrivalTime();
        boolean needsAi = arrival == null;

        if (needsAi) {
            if (req.message() == null || req.message().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "도착시간 또는 자연어 요청을 입력하세요."
                );
            }

            interpreted = gemini.interpret(req.message());
            JsonNode intent = mapper.valueToTree(interpreted);

            if (intent.path("arrivalDateTime").isTextual()) {
                try {
                    arrival = LocalDateTime.parse(intent.path("arrivalDateTime").asText());
                } catch (Exception e) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "도착시간 형식이 올바르지 않습니다."
                    );
                }
            }
        } else {
            interpreted.put("intent", "SET_ALARM");
            interpreted.put("source", "DIRECT_INPUT");
            interpreted.put("arrivalDateTime", arrival.toString());
            interpreted.put("destination", req.destination());
        }

        if (arrival == null) {
            return new JourneyResponse(
                    "NEEDS_ARRIVAL_TIME",
                    "도착 희망 날짜와 시간을 알려주세요.",
                    interpreted,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ASK_USER",
                    null,
                    null,
                    null,
                    null
            );
        }

        if (!arrival.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "미래 도착시간을 입력하세요."
            );
        }

        JsonNode routes = odsay.search(
                req.startLongitude(),
                req.startLatitude(),
                req.endLongitude(),
                req.endLatitude()
        );

        Map<String, Object> routeData = mapper.convertValue(
                routes,
                new TypeReference<Map<String, Object>>() {}
        );
        JsonNode paths = routes.path("result").path("path");

        if (!paths.isArray() || paths.isEmpty()) {
            return new JourneyResponse(
                    "NO_ROUTE",
                    "검색된 대중교통 경로가 없습니다.",
                    interpreted,
                    routeData,
                    null,
                    null,
                    arrival,
                    null,
                    null,
                    null,
                    "ASK_USER",
                    null,
                    null,
                    null,
                    null
            );
        }

        int minutes = Integer.MAX_VALUE;
        JsonNode fastestPath = null;

        for (JsonNode path : paths) {
            int candidate = path.path("info").path("totalTime").asInt(-1);
            if (candidate > 0 && candidate < minutes) {
                minutes = candidate;
                fastestPath = path;
            }
        }

        if (minutes == Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 이동시간을 확인할 수 없습니다."
            );
        }

        JourneyTimeEstimator.TimeEstimate estimate = timeEstimator.estimate(fastestPath);
        // Keep the full ODsay response for compatibility, and expose the actual selected path.
        routeData.put("selectedPath", mapper.convertValue(fastestPath, new TypeReference<Map<String, Object>>() {}));

        var predicted = preparation.predictFor(userId);
        int buffer = req.bufferMinutes() == null ? 5 : req.bufferMinutes();
        // Schedule alarms against the conservative (maximum) estimated travel time.
        // Keep ODsay's fastest totalTime as the baseline/minimum for display.
        minutes = Math.max(minutes, estimate.maxMinutes());
        LocalDateTime leave = arrival.minusMinutes(minutes + buffer);
        LocalDateTime wake = leave.minusMinutes(predicted.predictedMinutes());

        if (!wake.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "기상 예정 시각이 지났습니다. 도착시간을 조정하세요."
            );
        }

        Map<String, Object> realtimeSubway = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        // A current ETA only applies to a trip departing now, not a future alarm.
        boolean nearTerm = !leave.isAfter(now.plusMinutes(15));
        if (nearTerm) {
            JsonNode firstSubway = null;
            int walkingToBoardingMinutes = 0;
            for (JsonNode section : fastestPath.path("subPath")) {
                int type = section.path("trafficType").asInt();
                if (type == 1) {
                    firstSubway = section;
                    break;
                }
                // If a bus comes before the subway, we cannot safely apply a live ETA.
                if (type == 2) {
                    break;
                }
                if (type == 3) {
                    walkingToBoardingMinutes += Math.max(0, section.path("sectionTime").asInt(0));
                }
            }
            if (firstSubway != null) {
                String station = firstSubway.path("startName").asText("").trim();
                if (!station.isBlank()) {
                    try {
                        Map<String, Object> arrivals = subwayService.getArrivals(station);
                        realtimeSubway = timeEstimator.matchSubway(firstSubway, arrivals);
                        estimate = timeEstimator.applyRealtime(
                                fastestPath, estimate, realtimeSubway, walkingToBoardingMinutes
                        );
                    } catch (Exception e) {
                        realtimeSubway.put("status", "REALTIME_UNAVAILABLE");
                        realtimeSubway.put("message", "실시간 지하철 조회 실패: 기본 ODsay 추정치를 유지합니다.");
                    }
                } else {
                    realtimeSubway.put("status", "STATION_UNKNOWN");
                }
            } else {
                realtimeSubway.put("status", "NOT_APPLICABLE");
                realtimeSubway.put("message", "첫 지하철 이전에 버스가 있거나 지하철 구간이 없습니다.");
            }
        } else {
            realtimeSubway.put("status", "FUTURE_PLAN");
            realtimeSubway.put("message", "미래 출발 일정에는 현재 실시간 도착정보를 적용하지 않습니다.");
        }

        Map<String, Object> realtimeBus = new LinkedHashMap<>();
        boolean fastestPathHasBus = false;

        if (fastestPath != null) {
            for (JsonNode section : fastestPath.path("subPath")) {
                if (section.path("trafficType").asInt() == 2) {
                    fastestPathHasBus = true;
                    break;
                }
            }
        }

        if (fastestPathHasBus) {
            if (!nearTerm) {
                realtimeBus.put("status", "FUTURE_PLAN");
                realtimeBus.put("message", "미래 출발 일정에는 현재 버스 도착정보를 적용하지 않습니다.");
            } else {
                // Only the first transit leg can be matched to a bus arriving now.
                JsonNode firstBus = null;
                int walkingMinutes = 0;
                for (JsonNode section : fastestPath.path("subPath")) {
                    int type = section.path("trafficType").asInt();
                    if (type == 2) { firstBus = section; break; }
                    if (type == 1) break;
                    if (type == 3) walkingMinutes += Math.max(0, section.path("sectionTime").asInt(0));
                }
                if (firstBus == null) {
                    realtimeBus.put("status", "NOT_FIRST_TRANSIT");
                    realtimeBus.put("message", "버스가 첫 대중교통이 아니어서 현재 도착정보를 적용하지 않습니다.");
                } else {
                    List<String> routeIds = new ArrayList<>();
                    List<String> busNumbers = new ArrayList<>();
                    for (JsonNode lane : firstBus.path("lane")) {
                        String id = lane.path("busLocalBlID").asText("");
                        String no = lane.path("busNo").asText("");
                        if (!id.isBlank()) routeIds.add(id);
                        if (!no.isBlank()) busNumbers.add(no);
                    }
                    String stopId = firstBus.path("startLocalStationID").asText("");
                    String stopName = firstBus.path("startName").asText("");
                    double lat = firstBus.path("startY").asDouble(req.startLatitude());
                    double lon = firstBus.path("startX").asDouble(req.startLongitude());
                    try {
                        BusApproachService.Approach approach = busApproach.find(
                                stopId, stopName, lat, lon, routeIds, busNumbers);
                        realtimeBus.put("status", approach.status());
                        realtimeBus.put("provider", approach.provider());
                        realtimeBus.put("stopName", approach.stopName());
                        realtimeBus.put("stopId", approach.stopId());
                        realtimeBus.put("checkedAt", approach.checkedAt());
                        realtimeBus.put("arrivals", approach.arrivals());
                        if ("FOUND".equals(approach.status())) {
                            estimate = timeEstimator.applyRealtimeBus(
                                    fastestPath, estimate, realtimeBus, approach.arrivals(), walkingMinutes);
                        } else {
                            realtimeBus.put("message", "일치하는 버스 도착정보가 없어 ODsay 추정치를 유지합니다.");
                        }
                    } catch (Exception e) {
                        realtimeBus.put("status", "REALTIME_UNAVAILABLE");
                        realtimeBus.put("message", "실시간 버스 조회에 실패해 ODsay 추정치를 유지합니다.");
                    }
                }
            }
        } else {
            realtimeBus.put("status", "NOT_APPLICABLE");
            realtimeBus.put("message", "최단 경로에 버스가 없어 실시간 버스 조회를 생략했습니다.");
        }

        // Recalculate after live adjustments when available; never use the minimum
        // estimate to schedule an alarm. Future plans retain the conservative baseline.
        minutes = Math.max(1, estimate.maxMinutes());
        leave = arrival.minusMinutes(minutes + buffer);
        wake = leave.minusMinutes(predicted.predictedMinutes());
        if (!wake.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "기상 예정 시각이 지났습니다. 도착시간을 조정하세요.");
        }

        Long reservationId = null;
        if (Boolean.TRUE.equals(req.saveReservation())) {
            String destination = req.destination();
            if (destination == null || destination.isBlank()) {
                Object aiDestination = interpreted.get("destination");
                destination = aiDestination == null ? "목적지" : String.valueOf(aiDestination);
            }

            AlarmReservation reservation = new AlarmReservation(
                    userId, destination, arrival, wake, leave,
                    minutes, predicted.predictedMinutes());
            // Persist the selected ODsay path once; scheduled checks never call ODsay.
            reservation.selectedPathJson = fastestPath.toString();
            reservation.bufferMinutes = buffer;
            reservation.autoCheckStatus = "WAITING";
            reservationId = reservations.save(reservation).id;
        }

        return new JourneyResponse(
                "PLANNED",
                "알람 시각 계산 완료. 실제 알람 실행은 클라이언트에서 처리합니다.",
                interpreted,
                routeData,
                minutes,
                predicted.predictedMinutes(),
                arrival,
                leave,
                wake,
                reservationId,
                "SCHEDULE_ON_CLIENT",
                realtimeBus,
                estimate.minMinutes(),
                estimate.maxMinutes(),
                realtimeSubway
        );
    }

    /** Live diagnostics only. Does not schedule or save an alarm. */
    public record RealtimePreviewRequest(
            @NotNull Double startLongitude, @NotNull Double startLatitude,
            @NotNull Double endLongitude, @NotNull Double endLatitude) {}

    @PostMapping("/realtime-preview")
    public Map<String, Object> realtimePreview(@Valid @RequestBody RealtimePreviewRequest req,
                                               Authentication authentication) {
        currentUser.requireUserId(authentication);
        JsonNode routes = odsay.search(req.startLongitude(), req.startLatitude(),
                req.endLongitude(), req.endLatitude());
        JsonNode paths = routes.path("result").path("path");
        if (!paths.isArray() || paths.isEmpty()) {
            return Map.of("status", "NO_ROUTE", "message", "경로가 없습니다.");
        }
        JsonNode selected = null;
        int shortest = Integer.MAX_VALUE;
        for (JsonNode path : paths) {
            int candidate = path.path("info").path("totalTime").asInt(-1);
            if (candidate > 0 && candidate < shortest) { shortest = candidate; selected = path; }
        }
        if (selected == null) return Map.of("status", "NO_ROUTE");
        JourneyTimeEstimator.TimeEstimate baseline = timeEstimator.estimate(selected);
        JourneyTimeEstimator.TimeEstimate adjusted = baseline;
        Map<String, Object> live = new LinkedHashMap<>();
        int walk = 0;
        JsonNode firstTransit = null;
        for (JsonNode section : selected.path("subPath")) {
            int type = section.path("trafficType").asInt();
            if (type == 3) walk += Math.max(0, section.path("sectionTime").asInt());
            if (type == 1 || type == 2) { firstTransit = section; break; }
        }
        if (firstTransit == null) {
            live.put("status", "NO_TRANSIT");
        } else if (firstTransit.path("trafficType").asInt() == 2) {
            List<String> routeIds = new ArrayList<>();
            List<String> busNos = new ArrayList<>();
            for (JsonNode lane : firstTransit.path("lane")) {
                String id = lane.path("busLocalBlID").asText("");
                String no = lane.path("busNo").asText("");
                if (!id.isBlank()) routeIds.add(id);
                if (!no.isBlank()) busNos.add(no);
            }
            String stopName = firstTransit.path("startName").asText("");
            live.put("stopName", stopName);
            live.put("busNumbers", busNos);
            live.put("routeIds", routeIds);
            try {
                BusApproachService.Approach approach = busApproach.find(
                        firstTransit.path("startLocalStationID").asText(""), stopName,
                        firstTransit.path("startY").asDouble(req.startLatitude()),
                        firstTransit.path("startX").asDouble(req.startLongitude()),
                        routeIds, busNos);
                live.put("status", approach.status());
                live.put("provider", approach.provider());
                live.put("stopId", approach.stopId());
                live.put("checkedAt", approach.checkedAt());
                live.put("arrivals", approach.arrivals());
                if ("FOUND".equals(approach.status())) {
                    adjusted = timeEstimator.applyRealtimeBus(selected, baseline, live,
                            approach.arrivals(), walk);
                }
            } catch (Exception e) {
                live.put("status", "REALTIME_UNAVAILABLE");
                live.put("message", "버스 실시간 조회에 실패했습니다. 기본 추정치를 유지합니다.");
                live.put("failureStage", "BUS_APPROACH_LOOKUP");
                live.put("errorType", e.getClass().getSimpleName());
                if (e instanceof ResponseStatusException rse) {
                    live.put("upstreamHttpStatus", rse.getStatusCode().value());
                    live.put("errorCategory", "UPSTREAM_API_OR_PROVIDER_ERROR");
                } else if (e instanceof RestClientResponseException rce) {
                    live.put("upstreamHttpStatus", rce.getStatusCode().value());
                    live.put("errorCategory", "UPSTREAM_HTTP_ERROR");
                } else {
                    live.put("errorCategory", "LOOKUP_OR_MAPPING_ERROR");
                }
                // Do not expose exception messages: upstream errors may contain API keys or request URLs.
                System.err.println("[BUXBY] realtime-preview bus lookup failed: "
                        + e.getClass().getSimpleName()
                        + " (category=" + live.get("errorCategory") + ")");
            }
        } else {
            String station = firstTransit.path("startName").asText("");
            live.put("stationName", station);
            try {
                live = timeEstimator.matchSubway(firstTransit, subwayService.getArrivals(station));
                adjusted = timeEstimator.applyRealtime(selected, baseline, live, walk);
            } catch (Exception e) {
                live.put("status", "REALTIME_UNAVAILABLE");
                live.put("message", "지하철 실시간 조회에 실패했습니다. 기본 추정치를 유지합니다.");
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("checkedAt", LocalDateTime.now());
        response.put("selectedPath", mapper.convertValue(selected, new TypeReference<Map<String, Object>>() {}));
        response.put("baselineMinMinutes", baseline.minMinutes());
        response.put("baselineMaxMinutes", baseline.maxMinutes());
        response.put("minRouteMinutes", adjusted.minMinutes());
        response.put("maxRouteMinutes", adjusted.maxMinutes());
        response.put("realtime", live);
        response.put("message", "현재 출발 가정의 실시간 조회 결과이며 미래 알람에 적용하지 않습니다.");
        return response;
    }

}
