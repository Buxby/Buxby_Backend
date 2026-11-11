package com.buxby.buxby_backend.transit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/bus")
public class BusApproachController {

    private final BusApproachService service;

    public BusApproachController(BusApproachService service) {
        this.service = service;
    }

    // stopId·routeIds는 ODsay의 startLocalStationID·busLocalBlID를 그대로 받는다
    @GetMapping("/approach")
    public BusApproachService.Approach approach(
            @RequestParam(required = false) String stopId,
            @RequestParam String stopName,
            @RequestParam double stopLat,
            @RequestParam double stopLng,
            @RequestParam(required = false) List<String> routeIds,
            @RequestParam(required = false) List<String> busNos
    ) {
        if ((routeIds == null || routeIds.isEmpty()) && (busNos == null || busNos.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "노선 ID나 버스 번호가 필요합니다.");
        }
        if (Math.abs(stopLat) > 90 || Math.abs(stopLng) > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "좌표 범위가 올바르지 않습니다.");
        }
        return service.find(stopId, stopName, stopLat, stopLng, routeIds, busNos);
    }
}
