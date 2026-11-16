package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import jakarta.transaction.Transactional;
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
 * V10: Explicitly apply a SIMULATED delay to the persisted reservation.
 * This does not detect a real transit delay and does not program a phone alarm.
 * expectedRouteMinutes is an optimistic concurrency guard against duplicate application.
 */
@RestController
@RequestMapping("/api/alarm-reservations")
public class AlarmDelayApplyController {
    private final AlarmReservationRepository reservations;
    private final CurrentUserService currentUser;

    public AlarmDelayApplyController(AlarmReservationRepository reservations,
                                     CurrentUserService currentUser) {
        this.reservations = reservations;
        this.currentUser = currentUser;
    }

    public record DelayApplyRequest(
            @NotNull @Min(1) @Max(120) Integer delayMinutes,
            @NotNull @Min(1) Integer expectedRouteMinutes) {}

    @PostMapping("/{id}/delay-apply")
    @Transactional
    public Map<String, Object> apply(@PathVariable Long id,
                                     @Valid @RequestBody DelayApplyRequest request,
                                     Authentication authentication) {
        String userId = currentUser.requireUserId(authentication);
        AlarmReservation alarm = reservations.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "해당 사용자의 예약을 찾을 수 없습니다."));

        if ("CANCELLED".equals(alarm.status) || "FAILED".equals(alarm.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소 또는 실패한 예약은 수정할 수 없습니다.");
        }
        if (alarm.routeMinutes != request.expectedRouteMinutes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이동시간이 이미 변경되었습니다. 예약을 다시 조회한 뒤 재시도하세요.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!alarm.wakeTime.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "기존 기상시간이 지났으므로 알람을 앞당길 수 없습니다.");
        }

        int updatedRoute;
        try {
            updatedRoute = Math.addExact(alarm.routeMinutes, request.delayMinutes());
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이동시간 범위를 초과했습니다.");
        }
        int buffer = alarm.bufferMinutes == null ? 5 : alarm.bufferMinutes;
        LocalDateTime updatedLeave = alarm.desiredArrivalTime.minusMinutes((long) updatedRoute + buffer);
        LocalDateTime updatedWake = updatedLeave.minusMinutes(alarm.preparationMinutes);
        if (!updatedWake.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "변경된 기상시간이 이미 지났습니다. 알람을 안전하게 앞당길 수 없습니다.");
        }

        LocalDateTime oldWake = alarm.wakeTime;
        LocalDateTime oldLeave = alarm.leaveHomeTime;
        int oldRoute = alarm.routeMinutes;
        alarm.routeMinutes = updatedRoute;
        alarm.leaveHomeTime = updatedLeave;
        alarm.wakeTime = updatedWake;
        alarm.scheduleUpdatedAt = now;
        alarm.status = "PENDING_CLIENT";
        alarm.autoCheckStatus = "MANUAL_DELAY_APPLIED_CLIENT_RESCHEDULE_REQUIRED";
        reservations.save(alarm);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", alarm.id);
        result.put("status", alarm.autoCheckStatus);
        result.put("delayMinutes", request.delayMinutes());
        result.put("previousRouteMinutes", oldRoute);
        result.put("updatedRouteMinutes", updatedRoute);
        result.put("previousWakeTime", oldWake);
        result.put("updatedWakeTime", updatedWake);
        result.put("previousLeaveHomeTime", oldLeave);
        result.put("updatedLeaveHomeTime", updatedLeave);
        result.put("scheduleUpdatedAt", now);
        result.put("savedToDatabase", true);
        result.put("odsayCalled", false);
        result.put("deviceAlarmUpdated", false);
        result.put("message", "테스트 지연을 DB 예약에 반영했습니다. 휴대폰 알람은 클라이언트에서 다시 예약해야 합니다.");
        return result;
    }
}
