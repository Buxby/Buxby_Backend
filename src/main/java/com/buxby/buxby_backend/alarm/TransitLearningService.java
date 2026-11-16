package com.buxby.buxby_backend.alarm;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class TransitLearningService {
    private final AlarmReservationRepository repository;
    public TransitLearningService(AlarmReservationRepository repository) { this.repository = repository; }
    public int recommendedBuffer(String userId, int baseBuffer) {
        List<AlarmReservation> history = repository.findTop20ByUserIdAndTransitOutcomeIsNotNullOrderByDesiredArrivalTimeDesc(userId);
        if (history.size() < 5) return Math.max(5, Math.min(15, baseBuffer));
        long misses = history.stream().filter(a -> "MISSED".equals(a.transitOutcome)).count();
        double rate = (double) misses / history.size();
        int extra = rate >= .30 ? 5 : rate >= .20 ? 3 : rate >= .10 ? 2 : 0;
        return Math.max(5, Math.min(15, baseBuffer + extra));
    }
}
