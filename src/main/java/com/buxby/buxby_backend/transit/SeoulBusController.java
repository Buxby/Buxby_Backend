
package com.buxby.buxby_backend.transit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/seoul/bus")
public class SeoulBusController {

    private final SeoulBusService seoulBusService;

    public SeoulBusController(SeoulBusService seoulBusService) {
        this.seoulBusService = seoulBusService;
    }

    @GetMapping("/arrival")
    public Map<String, Object> getArrival(
            @RequestParam String stId,
            @RequestParam String busRouteId,
            @RequestParam int ord) {

        return seoulBusService.getArrival(stId, busRouteId, ord);
    }
}
