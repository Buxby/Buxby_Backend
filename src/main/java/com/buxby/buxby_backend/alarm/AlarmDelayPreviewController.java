package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only test of delay compensation. Does not call ODsay, update DB,
 * or schedule a real device alarm.
 */
@RestController
@RequestMapping("/api/alarm-reservations")
public class AlarmDelayPreviewController {
    private final AlarmReservationRepository reservations;
    private final CurrentUserService currentUser;

    public AlarmDelayPreviewController(AlarmReservationRepository reservations,
                                       CurrentUserService currentUser) {
        this.reservations = reservations;
        this.currentUser = currentUser;
    }

    public record DelayPreviewRequest(@NotNull @Min(1) @Max(120) Integer delayMinutes) {}

    @PostMapping("/{id}/delay-preview")
    public Map<String, Object> preview(@PathVariable Long id,
                                       @Valid @RequestBody DelayPreviewRequest request,
                                       Authentication authentication) {
        String userId = currentUser.requireUserId(authentication);
        AlarmReservation alarm = reservations.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "해당 사용자의 예약을 찾을 수 없습니다."));
        int delay = request.delayMinutes();
        int newRouteMinutes = Math.addExact(alarm.routeMinutes, delay);
        int buffer = alarm.bufferMinutes == null ? 5 : alarm.bufferMinutes;
        LocalDateTime proposedLeave = alarm.desiredArrivalTime.minusMinutes(newRouteMinutes + buffer);
        LocalDateTime proposedWake = proposedLeave.minusMinutes(alarm.preparationMinutes);
        LocalDateTime now = LocalDateTime.now();
        String result = !alarm.wakeTime.isAfter(now) ? "WAKE_TIME_ALREADY_PASSED"
                : !proposedWake.isAfter(now) ? "TOO_LATE_TO_ADVANCE"
                : "SIMULATION_ONLY";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reservationId", alarm.id);
        response.put("status", result);
        response.put("delayMinutes", delay);
        response.put("currentRouteMinutes", alarm.routeMinutes);
        response.put("simulatedRouteMinutes", newRouteMinutes);
        response.put("currentWakeTime", alarm.wakeTime);
        response.put("simulatedWakeTime", proposedWake);
        response.put("currentLeaveHomeTime", alarm.leaveHomeTime);
        response.put("simulatedLeaveHomeTime", proposedLeave);
        response.put("savedToDatabase", false);
        response.put("odsayCalled", false);
        response.put("message", "지연 상황 계산 미리보기입니다. 실제 예약과 휴대폰 알람은 변경하지 않습니다.");
        return response;
    }
}
