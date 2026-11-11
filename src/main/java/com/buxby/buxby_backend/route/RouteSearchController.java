package com.buxby.buxby_backend.route;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteSearchController {
    private final OdsayRouteService service;
    private final ObjectMapper mapper;
    public RouteSearchController(OdsayRouteService service, ObjectMapper mapper) { this.service=service; this.mapper=mapper; }

    // 응답 변환기는 Jackson 3이라 Jackson 2 JsonNode를 그대로 반환하면 노드 속성만 직렬화된다. Map으로 바꿔 반환한다.
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam double startLongitude, @RequestParam double startLatitude,
                                      @RequestParam double endLongitude, @RequestParam double endLatitude) {
        return mapper.convertValue(service.search(startLongitude,startLatitude,endLongitude,endLatitude),
                new TypeReference<Map<String, Object>>() {});
    }
}
