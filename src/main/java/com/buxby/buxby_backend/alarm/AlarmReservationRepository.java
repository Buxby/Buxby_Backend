package com.buxby.buxby_backend.alarm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AlarmReservationRepository extends JpaRepository<AlarmReservation, Long> {
    List<AlarmReservation> findByUserIdOrderByDesiredArrivalTimeDesc(String userId);
    List<AlarmReservation> findTop20ByUserIdAndTransitOutcomeIsNotNullOrderByDesiredArrivalTimeDesc(String userId);
    Optional<AlarmReservation> findByIdAndUserId(Long id, String userId);
    List<AlarmReservation> findByDesiredArrivalTimeAfterOrderByDesiredArrivalTimeAsc(
            java.time.LocalDateTime time);
}
