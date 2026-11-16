package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/transit-outcomes")
public class TransitOutcomeController {
    private final CurrentUserService users;
    private final TransitOutcomeService outcomes;
    private final TransitLearningService learning;
    public TransitOutcomeController(CurrentUserService users, TransitOutcomeService outcomes, TransitLearningService learning) {
        this.users = users; this.outcomes = outcomes; this.learning = learning;
    }
    @PostMapping("/{reservationId}/missed")
    public Map<String,Object> missed(@PathVariable Long reservationId, Authentication auth) {
        return outcomes.missed(users.requireUserId(auth), reservationId);
    }
    @GetMapping("/recommended-buffer")
    public Map<String,Object> recommended(Authentication auth) {
        return Map.of("bufferMinutes", learning.recommendedBuffer(users.requireUserId(auth), 5));
    }
}
