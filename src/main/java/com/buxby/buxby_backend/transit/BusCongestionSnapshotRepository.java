
package com.buxby.buxby_backend.transit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BusCongestionSnapshotRepository
        extends JpaRepository<BusCongestionSnapshot, Long> {

    // 특정 노선의 최근 혼잡도 기록 100개
    List<BusCongestionSnapshot>
    findTop100ByBusRouteIdOrderByCollectedAtDesc(
            String busRouteId
    );

    // 특정 노선의 특정 차량 기록 100개
    List<BusCongestionSnapshot>
    findTop100ByBusRouteIdAndVehicleIdOrderByCollectedAtDesc(
            String busRouteId,
            String vehicleId
    );

    List<BusCongestionSnapshot>
    findTop100ByProviderAndBusCityCodeAndBusRouteIdOrderByCollectedAtDesc(
            String provider,
            Integer busCityCode,
            String busRouteId
    );

    List<BusCongestionSnapshot>
    findTop100ByProviderAndBusCityCodeAndBusRouteIdAndVehicleIdOrderByCollectedAtDesc(
            String provider,
            Integer busCityCode,
            String busRouteId,
            String vehicleId
    );

    // 기준 시각보다 오래된 기록 일괄 삭제 (한 건씩 지우지 않고 delete 쿼리 한 번)
    @Modifying
    @Query("delete from BusCongestionSnapshot s where s.collectedAt < :cutoff")
    int deleteCollectedBefore(@Param("cutoff") LocalDateTime cutoff);
}
