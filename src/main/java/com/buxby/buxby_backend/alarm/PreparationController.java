
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

import java.util.List;

@RestController
@RequestMapping("/api/preparation")
public class PreparationController {

    private final PreparationRecordRepository records;
    private final PreparationProfileRepository profiles;
    private final CurrentUserService currentUser;

    public PreparationController(
            PreparationRecordRepository records,
            PreparationProfileRepository profiles,
            CurrentUserService currentUser
    ) {
        this.records = records;
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    public record RecordRequest(
            @NotNull @Min(1) @Max(240) Integer minutes
    ) {}

    public record Prediction(
            String userId,
            int predictedMinutes,
            int samples,
            String source
    ) {}

    public record SetupStatus(
            boolean configured,
            Integer preparationMinutes,
            String message
    ) {}

    @GetMapping("/setup")
    public SetupStatus setupStatus(Authentication authentication) {
        String userId = currentUser.requireUserId(authentication);

        return profiles.findById(userId)
                .map(profile -> new SetupStatus(
                        true,
                        profile.initialMinutes,
                        "준비시간이 설정되어 있습니다."
                ))
                .orElseGet(() -> new SetupStatus(
                        false,
                        null,
                        "최초 준비시간 설정이 필요합니다."
                ));
    }

    @PostMapping("/setup")
    public Prediction setup(
            @Valid @RequestBody RecordRequest request,
            Authentication authentication
    ) {
        String userId = currentUser.requireUserId(authentication);

        if (profiles.existsById(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 최초 준비시간이 설정되어 있습니다."
            );
        }

        profiles.save(new PreparationProfile(
                userId,
                request.minutes()
        ));

        return predictFor(userId);
    }

    @PostMapping("/records")
    public Prediction record(
            @Valid @RequestBody RecordRequest request,
            Authentication authentication
    ) {
        String userId = currentUser.requireUserId(authentication);

        records.save(new PreparationRecord(
                userId,
                request.minutes()
        ));

        return predictFor(userId);
    }

    @GetMapping("/predict")
    public Prediction predict(Authentication authentication) {
        return predictFor(
                currentUser.requireUserId(authentication)
        );
    }

    public Prediction predictFor(String userId) {
        List<PreparationRecord> recent =
                records.findTop20ByUserIdOrderByRecordedAtDesc(userId);

        PreparationProfile profile =
                profiles.findById(userId).orElse(null);

        if (profile == null && recent.isEmpty()) {
            return new Prediction(
                    userId, 40, 0, "DEFAULT_40_MIN"
            );
        }

        if (recent.isEmpty()) {
            return new Prediction(
                    userId,
                    profile.initialMinutes,
                    0,
                    "INITIAL_USER_INPUT"
            );
        }

        int average = (int) Math.round(
                recent.stream()
                        .mapToInt(record -> record.minutes)
                        .average()
                        .orElse(40)
        );

        return new Prediction(
                userId,
                average,
                recent.size(),
                "RECENT_20_AVERAGE"
        );
    }
}
