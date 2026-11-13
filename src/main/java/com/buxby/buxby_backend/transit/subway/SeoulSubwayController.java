
package com.buxby.buxby_backend.transit.subway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/seoul/subway")
public class SeoulSubwayController {

    private final SeoulSubwayService subwayService;

    public SeoulSubwayController(
            SeoulSubwayService subwayService
    ) {
        this.subwayService = subwayService;
    }

    // 역 이름으로 실시간 지하철 도착정보 조회
    @GetMapping("/arrivals")
    public Map<String, Object> getArrivals(
            @RequestParam String stationName
    ) {

        return subwayService.getArrivals(stationName);
    }
}
