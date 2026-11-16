package com.buxby.buxby_backend.alarm;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class TransitOutcomeService {
    private final AlarmReservationRepository reservations;
    private final TransitLearningService learning;
    public TransitOutcomeService(AlarmReservationRepository reservations, TransitLearningService learning) {
        this.reservations = reservations; this.learning = learning;
    }
    @Transactional
    public Map<String, Object> missed(String userId, Long reservationId) {
        AlarmReservation a = reservations.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        if ("CANCELLED".equals(a.status) || "FAILED".equals(a.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소된 예약은 집계할 수 없습니다.");
        a.transitOutcome = "MISSED";
        a.transitOutcomeAt = LocalDateTime.now();
        reservations.save(a);
        return Map.of("reservationId", a.id, "outcome", a.transitOutcome,
                "recommendedBufferMinutes", learning.recommendedBuffer(userId, a.bufferMinutes == null ? 5 : a.bufferMinutes));
    }
    @Transactional
    public void assumeSuccess(AlarmReservation a) {
        if (a.transitOutcome != null || !"SCHEDULED".equals(a.status)) return;
        a.transitOutcome = "ASSUMED_SUCCESS";
        a.transitOutcomeAt = LocalDateTime.now();
        reservations.save(a);
    }
}
