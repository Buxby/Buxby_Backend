package com.buxby.buxby_backend.alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface AlarmReservationRepository extends JpaRepository<AlarmReservation,Long> {
    List<AlarmReservation> findByUserIdOrderByDesiredArrivalTimeDesc(String userId);
    Optional<AlarmReservation> findByIdAndUserId(Long id, String userId);
}
