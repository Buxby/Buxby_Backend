
package com.buxby.buxby_backend.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/transit")
@Validated
public class TransitController {

    private final TagoService tago;
    private final ObjectMapper mapper;

    public TransitController(TagoService tago, ObjectMapper mapper) {
        this.tago = tago;
        this.mapper = mapper;
    }

    private Map<String, Object> toMap(JsonNode node) {
        return mapper.convertValue(
                node,
                new TypeReference<Map<String, Object>>() {}
        );
    }

    @GetMapping("/cities")
    public Map<String, Object> cities() {
        return toMap(tago.fetch(
                "route", "getCtyCodeList",
                null, null, null, null, 1, 1000
        ));
    }

    @GetMapping("/routes")
    public Map<String, Object> routes(
            @RequestParam int cityCode,
            @RequestParam(required = false) String routeNo,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "100")
            @Min(1) @Max(1000) int numOfRows
    ) {
        return toMap(tago.fetch(
                "route", "getRouteNoList",
                cityCode, null, routeNo, null,
                pageNo, numOfRows
        ));
    }

    @GetMapping("/routes/{routeId}/stops")
    public Map<String, Object> routeStops(
            @PathVariable String routeId,
            @RequestParam int cityCode,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "100")
            @Min(1) @Max(1000) int numOfRows
    ) {
        return toMap(tago.fetch(
                "route", "getRouteAcctoThrghSttnList",
                cityCode, routeId, null, null,
                pageNo, numOfRows
        ));
    }

    @GetMapping("/routes/{routeId}")
    public Map<String, Object> routeDetail(
            @PathVariable String routeId,
            @RequestParam int cityCode
    ) {
        return toMap(tago.fetch(
                "route", "getRouteInfoIem",
                cityCode, routeId, null, null,
                1, 100
        ));
    }

    @GetMapping("/stops/{nodeId}/arrivals")
    public Map<String, Object> arrivals(
            @PathVariable String nodeId,
            @RequestParam int cityCode,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "100")
            @Min(1) @Max(1000) int numOfRows
    ) {
        return toMap(tago.fetch(
                "arrival",
                "getSttnAcctoArvlPrearngeInfoList",
                cityCode, null, null, nodeId,
                pageNo, numOfRows
        ));
    }

    @GetMapping("/stops/{nodeId}/routes/{routeId}/arrivals")
    public Map<String, Object> routeArrival(
            @PathVariable String nodeId,
            @PathVariable String routeId,
            @RequestParam int cityCode
    ) {
        return toMap(tago.fetch(
                "arrival",
                "getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList",
                cityCode, routeId, null, nodeId,
                1, 100
        ));
    }
}
