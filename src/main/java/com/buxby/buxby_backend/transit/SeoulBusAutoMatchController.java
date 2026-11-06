
package com.buxby.buxby_backend.transit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/seoul/bus")
public class SeoulBusAutoMatchController {

    private final SeoulBusAutoMatchService service;

    public SeoulBusAutoMatchController(
            SeoulBusAutoMatchService service
    ) {
        this.service = service;
    }

    @GetMapping("/auto-match")
    public Map<String, Object> autoMatch(
            @RequestParam double startLongitude,
            @RequestParam double startLatitude,
            @RequestParam double endLongitude,
            @RequestParam double endLatitude
    ) {
        return service.match(
                startLongitude,
                startLatitude,
                endLongitude,
                endLatitude
        );
    }
}
