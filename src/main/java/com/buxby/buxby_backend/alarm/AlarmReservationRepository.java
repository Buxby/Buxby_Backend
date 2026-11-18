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
    // 도착시간이 지났는데 아직 이동 결과가 기록되지 않은 예약 (이동 결과 추정 스케줄러용)
    List<AlarmReservation> findByStatusAndTransitOutcomeIsNullAndDesiredArrivalTimeBefore(
            String status, java.time.LocalDateTime time);
}
