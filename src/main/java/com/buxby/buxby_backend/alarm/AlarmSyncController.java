package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client polls pending schedules; device scheduling itself is client-owned. */
@RestController
@RequestMapping("/api/alarm-sync")
public class AlarmSyncController {
    private final AlarmReservationRepository repository;
    private final CurrentUserService currentUser;
    public AlarmSyncController(AlarmReservationRepository repository, CurrentUserService currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        String userId = currentUser.requireUserId(auth);
        return repository.findByUserIdOrderByDesiredArrivalTimeDesc(userId).stream()
                .map(this::toSync).toList();
    }

    private Map<String, Object> toSync(AlarmReservation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reservationId", a.id);
        m.put("destination", a.destination);
        m.put("status", a.status);
        m.put("wakeTime", a.wakeTime);
        m.put("leaveHomeTime", a.leaveHomeTime);
        m.put("desiredArrivalTime", a.desiredArrivalTime);
        m.put("routeMinutes", a.routeMinutes);
        m.put("scheduleUpdatedAt", a.scheduleUpdatedAt);
        m.put("autoCheckStatus", a.autoCheckStatus);
        m.put("clientAction", "CANCELLED".equals(a.status) ? "CANCEL_ON_DEVICE" :
                "FAILED".equals(a.status) ? "CHECK_MANUALLY" :
                "SCHEDULED".equals(a.status) ? "VERIFY_DEVICE_ALARM" : "SCHEDULE_ON_DEVICE");
        return m;
    }

    public record AcknowledgeRequest(@NotNull LocalDateTime expectedWakeTime) {}

    @PostMapping("/{id}/ack")
    public Map<String, Object> acknowledge(@PathVariable Long id,
            @Valid @RequestBody AcknowledgeRequest req, Authentication auth) {
        String userId = currentUser.requireUserId(auth);
        AlarmReservation alarm = repository.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        if ("CANCELLED".equals(alarm.status) || "FAILED".equals(alarm.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소 또는 실패한 예약입니다.");
        }
        if (!alarm.wakeTime.equals(req.expectedWakeTime())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "서버 알람시간이 변경됐습니다. 다시 동기화하세요.");
        }
        if (!alarm.wakeTime.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "기상시간이 지났습니다.");
        }
        alarm.status = "SCHEDULED";
        repository.save(alarm);
        return Map.of("reservationId", alarm.id, "status", alarm.status,
                "deviceAlarmVerifiedByServer", false,
                "message", "클라이언트의 알람 예약 완료 보고를 저장했습니다.");
    }
}
