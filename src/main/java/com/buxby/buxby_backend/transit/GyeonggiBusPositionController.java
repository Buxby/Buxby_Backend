package com.buxby.buxby_backend.transit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/gyeonggi/bus")
public class GyeonggiBusPositionController {

    private final GyeonggiBusPositionService positionService;

    public GyeonggiBusPositionController(
            GyeonggiBusPositionService positionService
    ) {
        this.positionService = positionService;
    }

    // 경기 버스 실시간 차량 위치 및 혼잡도 조회
    @GetMapping("/positions")
    public Map<String, Object> getPositions(
            @RequestParam String busRouteId
    ) {
        return positionService.getPositions(busRouteId);
    }
}