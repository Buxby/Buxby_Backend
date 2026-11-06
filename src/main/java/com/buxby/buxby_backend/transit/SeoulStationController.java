
package com.buxby.buxby_backend.transit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/seoul/stations")
public class SeoulStationController {

    private final SeoulStationService seoulStationService;

    public SeoulStationController(SeoulStationService seoulStationService) {
        this.seoulStationService = seoulStationService;
    }

    @GetMapping("/search")
    public Map<String, Object> searchStation(
            @RequestParam String stationName) {
        return seoulStationService.searchStation(stationName);
    }

    @GetMapping("/routes")
    public Map<String, Object> getRoutesByStation(
            @RequestParam String arsId) {
        return seoulStationService.getRoutesByStation(arsId);
    }

    @GetMapping("/nearby")
    public Map<String, Object> getNearbyStations(
            @RequestParam double tmX,
            @RequestParam double tmY,
            @RequestParam(defaultValue = "500") int radius) {

        return seoulStationService.getNearbyStations(tmX, tmY, radius);
    }
}
