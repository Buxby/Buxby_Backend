package com.buxby.buxby_backend.alarm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/preparation")
public class PreparationController {
    private final PreparationRecordRepository repository;
    public PreparationController(PreparationRecordRepository repository) { this.repository=repository; }
    public record RecordRequest(@NotBlank String userId, @Min(1) @Max(240) int minutes) {}
    public record Prediction(String userId, int predictedMinutes, int samples, String source) {}
    @PostMapping("/records")
    public Prediction record(@Valid @RequestBody RecordRequest request) {
        repository.save(new PreparationRecord(request.userId(), request.minutes()));
        return predict(request.userId());
    }
    @GetMapping("/predict")
    public Prediction predict(@RequestParam String userId) {
        if (userId == null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId가 필요합니다.");
        List<PreparationRecord> records=repository.findTop20ByUserIdOrderByRecordedAtDesc(userId);
        int minutes=records.isEmpty()?40:(int)Math.round(records.stream().mapToInt(r->r.minutes).average().orElse(40));
        return new Prediction(userId, minutes, records.size(), records.isEmpty()?"DEFAULT_40_MIN":"RECENT_20_AVERAGE");
    }
}
