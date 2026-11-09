package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/preparation")
public class PreparationController {
    private final PreparationRecordRepository repository;
    private final CurrentUserService currentUser;
    public PreparationController(PreparationRecordRepository repository, CurrentUserService currentUser) { this.repository=repository; this.currentUser=currentUser; }
    public record RecordRequest(@Min(1) @Max(240) int minutes) {}
    public record Prediction(String userId, int predictedMinutes, int samples, String source) {}
    @PostMapping("/records")
    public Prediction record(@Valid @RequestBody RecordRequest request, Authentication authentication) {
        String userId=currentUser.requireUserId(authentication);
        repository.save(new PreparationRecord(userId, request.minutes()));
        return predictFor(userId);
    }
    @GetMapping("/predict")
    public Prediction predict(Authentication authentication) {
        return predictFor(currentUser.requireUserId(authentication));
    }
    public Prediction predictFor(String userId) {
        List<PreparationRecord> records=repository.findTop20ByUserIdOrderByRecordedAtDesc(userId);
        int minutes=records.isEmpty()?40:(int)Math.round(records.stream().mapToInt(r->r.minutes).average().orElse(40));
        return new Prediction(userId, minutes, records.size(), records.isEmpty()?"DEFAULT_40_MIN":"RECENT_20_AVERAGE");
    }
}
