package com.buxby.buxby_backend.alarm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {
    public record AlarmRequest(
            @NotNull LocalDateTime busBoardingTime,
            @NotNull @Min(0) @Max(1440) Integer predictedPreparationMinutes,
            @NotNull @Min(0) @Max(1440) Integer walkingMinutes,
            @NotNull @Min(0) @Max(1440) Integer bufferMinutes,
            @Min(0) @Max(86400) Integer arrivalSeconds) { }

    public record AlarmResponse(LocalDateTime wakeTime, LocalDateTime leaveHomeTime,
                                LocalDateTime busBoardingTime, int preparationMinutes,
                                String predictionSource, String note) { }

    @PostMapping("/recommend")
    public AlarmResponse recommend(@Valid @RequestBody AlarmRequest request) {
        // Optional arrivalSeconds is the TAGO arrtime (seconds until arrival), not an absolute timestamp.
        // Do not silently mix it with a separately supplied boarding datetime.
        if (!request.busBoardingTime().isAfter(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "탑승 시각은 미래여야 합니다.");
        LocalDateTime leave = request.busBoardingTime().minusMinutes(request.walkingMinutes()).minusMinutes(request.bufferMinutes());
        LocalDateTime wake = leave.minusMinutes(request.predictedPreparationMinutes());
        if (!wake.isAfter(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기상 시각이 이미 지났습니다.");
        return new AlarmResponse(wake, leave, request.busBoardingTime(), request.predictedPreparationMinutes(),
                "EXTERNAL_OR_MANUAL", "predictedPreparationMinutes는 AI 또는 프론트에서 받은 값입니다. AI 학습은 이 API에 포함되지 않습니다. 실제 알람 예약은 클라이언트가 수행합니다.");
    }
}
