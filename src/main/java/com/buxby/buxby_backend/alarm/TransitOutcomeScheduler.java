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
        for (AlarmReservation a : reservations.findAll()) {
            if (a.desiredArrivalTime.isBefore(cutoff) && a.transitOutcome == null)
                outcomes.assumeSuccess(a);
        }
    }
}
