package com.buxby.buxby_backend.alarm;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class TransitOutcomeScheduler {
    private final AlarmReservationRepository reservations;
    private final TransitOutcomeService outcomes;
    public TransitOutcomeScheduler(AlarmReservationRepository reservations, TransitOutcomeService outcomes) {
        this.reservations = reservations; this.outcomes = outcomes;
    }
    // Arrival time is a conservative proxy; no reliable boarding timestamp is stored yet.
    @Scheduled(fixedDelay = 60000)
    public void inferCompletedTrips() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        // 전체 예약(findAll)을 매분 읽지 않고, 처리할 예약만 조회한다.
        // assumeSuccess도 SCHEDULED 상태만 기록하므로 같은 조건이다.
        for (AlarmReservation a : reservations
                .findByStatusAndTransitOutcomeIsNullAndDesiredArrivalTimeBefore("SCHEDULED", cutoff)) {
            outcomes.assumeSuccess(a);
        }
    }
}
