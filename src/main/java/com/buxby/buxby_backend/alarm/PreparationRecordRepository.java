package com.buxby.buxby_backend.alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PreparationRecordRepository extends JpaRepository<PreparationRecord,Long> {
    List<PreparationRecord> findTop20ByUserIdOrderByRecordedAtDesc(String userId);
}
