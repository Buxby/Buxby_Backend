package com.buxby.buxby_backend.route;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routes")
public class RouteSearchController {
    private final OdsayRouteService service;
    public RouteSearchController(OdsayRouteService service) { this.service=service; }
    @GetMapping("/search")
    public JsonNode search(@RequestParam double startLongitude, @RequestParam double startLatitude,
                           @RequestParam double endLongitude, @RequestParam double endLatitude) {
        return service.search(startLongitude,startLatitude,endLongitude,endLatitude);
    }
}
