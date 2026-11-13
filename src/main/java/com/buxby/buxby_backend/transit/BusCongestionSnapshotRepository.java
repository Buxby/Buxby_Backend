
package com.buxby.buxby_backend.transit;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
