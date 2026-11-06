package com.buxby.buxby_backend.alarm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/alarms")
public class SmartAlarmController {
    private final PreparationController preparation;
    public SmartAlarmController(PreparationController preparation) { this.preparation=preparation; }
    public record PlanRequest(@NotBlank String userId, @NotNull LocalDateTime desiredArrivalTime,
        @Min(1) @Max(1440) int routeTotalMinutes, @Min(0) @Max(180) int extraBufferMinutes) {}
    public record PlanResponse(LocalDateTime wakeTime, LocalDateTime leaveHomeTime, LocalDateTime desiredArrivalTime,
        int preparationMinutes, String predictionSource, boolean requiresFlutterScheduling, String status) {}
    @PostMapping("/plan")
    public PlanResponse plan(@Valid @RequestBody PlanRequest req) {
        if (req.routeTotalMinutes() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이동시간은 1분 이상이어야 합니다.");
        if (!req.desiredArrivalTime().isAfter(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "목적지 도착시간은 미래여야 합니다.");
        var prediction=preparation.predict(req.userId());
        LocalDateTime leave=req.desiredArrivalTime().minusMinutes(req.routeTotalMinutes()).minusMinutes(req.extraBufferMinutes());
        LocalDateTime wake=leave.minusMinutes(prediction.predictedMinutes());
        if (!wake.isAfter(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기상 알람 시각이 이미 지났습니다. 더 늦은 도착시간 또는 더 빠른 경로를 선택하세요.");
        return new PlanResponse(wake,leave,req.desiredArrivalTime(),prediction.predictedMinutes(),
            prediction.source(),true,"PROPOSED_NOT_SCHEDULED");
    }
}
