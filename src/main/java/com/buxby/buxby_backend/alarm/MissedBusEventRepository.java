
package com.buxby.buxby_backend.alarm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MissedBusEventRepository
        extends JpaRepository<MissedBusEvent, Long> {

    List<MissedBusEvent> findTop20ByUserIdOrderByOccurredAtDesc(
            String userId
    );
}
