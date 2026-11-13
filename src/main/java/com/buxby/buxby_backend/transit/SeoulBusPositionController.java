
package com.buxby.buxby_backend.transit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seoul/bus")
public class SeoulBusPositionController {

    private final SeoulBusPositionService positionService;
    private final BusCongestionSnapshotService snapshotService;

    public SeoulBusPositionController(
            SeoulBusPositionService positionService,
            BusCongestionSnapshotService snapshotService
    ) {
        this.positionService = positionService;
        this.snapshotService = snapshotService;
    }

    // =====================================
    // 1. 실시간 버스 위치 및 혼잡도 조회
    // =====================================

    @GetMapping("/positions")
    public Map<String, Object> getPositions(
            @RequestParam String busRouteId
    ) {
        return positionService.getPositions(busRouteId);
    }


    // =====================================
    // 2. 실시간 혼잡도 조회 및 DB 저장
    // =====================================

    @PostMapping("/positions/collect")
    public Map<String, Object> collectPositions(
            @RequestParam String busRouteId
    ) {
        return snapshotService.collect(busRouteId);
    }


    // =====================================
    // 3. 과거 혼잡도 기록 조회
    // =====================================

    @GetMapping("/positions/history")
    public List<BusCongestionSnapshot> getHistory(
            @RequestParam String busRouteId,
            @RequestParam(required = false) String vehicleId
    ) {
        return snapshotService.getHistory(
                busRouteId,
                vehicleId
        );
    }

    // 여러 노선 혼잡도 수집
    @PostMapping("/positions/collect-batch")
    public Map<String, Object> collectBatch(
            @RequestBody BusPositionCollectRequest request
    ) {

        return snapshotService.collectMany(
                request == null ? null : request.busRouteIds()
        );
    }
}
