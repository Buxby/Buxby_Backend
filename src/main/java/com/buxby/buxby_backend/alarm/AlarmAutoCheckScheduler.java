package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.route.JourneyTimeEstimator;
import com.buxby.buxby_backend.transit.BusApproachService;
import com.buxby.buxby_backend.transit.subway.SeoulSubwayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls saved reservations every 30 minutes WITHOUT calling ODsay.
 * Real-time ETAs are only relevant near departure; never use them as forecasts
 * for tomorrow morning. In ambiguous cases the original alarm stays unchanged.
 */
@Component
public class AlarmAutoCheckScheduler {
    private final AlarmReservationRepository repository;
    private final ObjectMapper mapper;
    private final JourneyTimeEstimator estimator;
    private final BusApproachService bus;
    private final SeoulSubwayService subway;

    public AlarmAutoCheckScheduler(AlarmReservationRepository repository, ObjectMapper mapper,
                                   JourneyTimeEstimator estimator, BusApproachService bus,
                                   SeoulSubwayService subway) {
        this.repository = repository;
        this.mapper = mapper;
        this.estimator = estimator;
        this.bus = bus;
        this.subway = subway;
    }

    @Scheduled(fixedDelayString = "${buxby.alarm.check-interval-ms:1800000}",
               initialDelayString = "${buxby.alarm.initial-delay-ms:60000}")
    public void checkReservations() {
        LocalDateTime now = LocalDateTime.now();
        for (AlarmReservation alarm :
                repository.findByDesiredArrivalTimeAfterOrderByDesiredArrivalTimeAsc(now)) {
            if ("CANCELLED".equals(alarm.status) || "FAILED".equals(alarm.status)) continue;
            try {
                checkOne(alarm, now);
            } catch (Exception e) {
                alarm.lastAutoCheckedAt = now;
                alarm.autoCheckStatus = "CHECK_FAILED_KEEP_ORIGINAL";
                repository.save(alarm);
                System.err.println("[BUXBY] alarm auto-check failed for reservation "
                        + alarm.id + ": " + e.getClass().getSimpleName());
            }
        }
    }

    /** Manual diagnostic run for one reservation; no ODsay route search. */
    public synchronized AlarmReservation checkReservationNow(Long id) {
        AlarmReservation alarm = repository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        if ("CANCELLED".equals(alarm.status) || "FAILED".equals(alarm.status)) return alarm;
        LocalDateTime now = LocalDateTime.now();
        if (!alarm.desiredArrivalTime.isAfter(now)) {
            alarm.lastAutoCheckedAt = now;
            alarm.autoCheckStatus = "ARRIVAL_PASSED";
            return repository.save(alarm);
        }
        try {
            checkOne(alarm, now);
        } catch (Exception ex) {
            alarm.lastAutoCheckedAt = now;
            alarm.autoCheckStatus = "CHECK_FAILED_KEEP_ORIGINAL";
            repository.save(alarm);
            System.err.println("[BUXBY] manual check failed for reservation " + id + ": "
                    + ex.getClass().getSimpleName());
        }
        return repository.findById(id).orElse(alarm);
    }

    private void checkOne(AlarmReservation alarm, LocalDateTime now) throws Exception {
        alarm.lastAutoCheckedAt = now;
        if (alarm.selectedPathJson == null || alarm.selectedPathJson.isBlank()) {
            alarm.autoCheckStatus = "LEGACY_NO_SAVED_PATH";
            repository.save(alarm);
            return;
        }
        // The live vehicle ETA cannot predict a journey departing in hours.
        if (alarm.leaveHomeTime.isAfter(now.plusMinutes(15))) {
            alarm.autoCheckStatus = "FUTURE_PLAN_NO_LIVE_LOOKUP";
            repository.save(alarm);
            return;
        }
        if (!alarm.leaveHomeTime.isAfter(now)) {
            alarm.autoCheckStatus = "DEPARTURE_PASSED";
            repository.save(alarm);
            return;
        }

        JsonNode path = mapper.readTree(alarm.selectedPathJson);
        JsonNode first = null;
        int walking = 0;
        for (JsonNode section : path.path("subPath")) {
            int type = section.path("trafficType").asInt();
            if (type == 3) walking += Math.max(0, section.path("sectionTime").asInt());
            if (type == 1 || type == 2) { first = section; break; }
        }
        if (first == null) {
            alarm.autoCheckStatus = "NO_TRANSIT";
            repository.save(alarm);
            return;
        }

        JourneyTimeEstimator.TimeEstimate baseline = estimator.estimate(path);
        JourneyTimeEstimator.TimeEstimate adjusted = baseline;
        Map<String, Object> live = new LinkedHashMap<>();
        if (first.path("trafficType").asInt() == 2) {
            List<String> ids = new ArrayList<>();
            List<String> numbers = new ArrayList<>();
            for (JsonNode lane : first.path("lane")) {
                String id = lane.path("busLocalBlID").asText("");
                String number = lane.path("busNo").asText("");
                if (!id.isBlank()) ids.add(id);
                if (!number.isBlank()) numbers.add(number);
            }
            BusApproachService.Approach approach = bus.find(
                    first.path("startLocalStationID").asText(""),
                    first.path("startName").asText(""),
                    first.path("startY").asDouble(),
                    first.path("startX").asDouble(), ids, numbers);
            if ("FOUND".equals(approach.status())) {
                adjusted = estimator.applyRealtimeBus(
                        path, baseline, live, approach.arrivals(), walking);
            } else {
                live.put("status", approach.status());
            }
        } else {
            String station = first.path("startName").asText("");
            live = estimator.matchSubway(first, subway.getArrivals(station));
            adjusted = estimator.applyRealtime(path, baseline, live, walking);
        }

        // The bus estimator currently reports REALTIME_SCENARIO_ONLY:
        // it is NOT a validated replacement for the original route duration.
        // Also, changing a wake alarm after its time has passed cannot wake anyone.
        if (!"REALTIME_APPLIED".equals(live.get("status"))) {
            alarm.autoCheckStatus = String.valueOf(live.getOrDefault("status", "NO_SAFE_ADJUSTMENT"));
        } else if (!alarm.wakeTime.isAfter(now)) {
            alarm.autoCheckStatus = "WAKE_ALREADY_DUE_KEEP_ORIGINAL";
        } else {
            int newMinutes = Math.max(alarm.routeMinutes, adjusted.maxMinutes());
            if (newMinutes > alarm.routeMinutes) {
                int buffer = alarm.bufferMinutes == null ? 5 : alarm.bufferMinutes;
                LocalDateTime newLeave = alarm.desiredArrivalTime.minusMinutes(newMinutes + buffer);
                LocalDateTime newWake = newLeave.minusMinutes(alarm.preparationMinutes);
                if (newWake.isAfter(now) && newWake.isBefore(alarm.wakeTime)) {
                    alarm.routeMinutes = newMinutes;
                    alarm.leaveHomeTime = newLeave;
                    alarm.wakeTime = newWake;
                    alarm.scheduleUpdatedAt = now;
                    alarm.status = "PENDING_CLIENT";
                    alarm.autoCheckStatus = "UPDATED_CLIENT_RESCHEDULE_REQUIRED";
                } else {
                    alarm.autoCheckStatus = "TOO_LATE_TO_ADVANCE_KEEP_ORIGINAL";
                }
            } else {
                alarm.autoCheckStatus = "NO_ADDITIONAL_DELAY";
            }
        }
        repository.save(alarm);
    }
}
