package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** User-scoped manual check and cancellation; does not call ODsay. */
@RestController
@RequestMapping("/api/alarm-operations")
public class AlarmOperationsController {
    private final AlarmReservationRepository repository;
    private final AlarmAutoCheckScheduler scheduler;
    private final CurrentUserService currentUser;

    public AlarmOperationsController(AlarmReservationRepository repository,
            AlarmAutoCheckScheduler scheduler, CurrentUserService currentUser) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/check-now")
    public Map<String, Object> checkNow(@PathVariable Long id, Authentication auth) {
        String userId = currentUser.requireUserId(auth);
        repository.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        AlarmReservation alarm = scheduler.checkReservationNow(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", alarm.id);
        result.put("autoCheckStatus", alarm.autoCheckStatus);
        result.put("lastAutoCheckedAt", alarm.lastAutoCheckedAt);
        result.put("wakeTime", alarm.wakeTime);
        result.put("leaveHomeTime", alarm.leaveHomeTime);
        result.put("scheduleUpdatedAt", alarm.scheduleUpdatedAt);
        result.put("odsayCalled", false);
        result.put("deviceAlarmUpdated", false);
        return result;
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, Authentication auth) {
        String userId = currentUser.requireUserId(auth);
        AlarmReservation alarm = repository.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        if (!"CANCELLED".equals(alarm.status)) {
            alarm.status = "CANCELLED";
            alarm.scheduleUpdatedAt = LocalDateTime.now();
            alarm.autoCheckStatus = "CANCELLED_CLIENT_SYNC_REQUIRED";
            repository.save(alarm);
        }
        return Map.of("reservationId", alarm.id, "status", alarm.status,
                "deviceAlarmUpdated", false);
    }
}
