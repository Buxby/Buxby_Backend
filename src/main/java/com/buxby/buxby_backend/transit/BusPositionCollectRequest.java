package com.buxby.buxby_backend.transit;

import java.util.List;

public record BusPositionCollectRequest(
        List<String> busRouteIds,
        List<BusTarget> busTargets
) {

    public record BusTarget(
            String busRouteId,
            String busNo,
            String provider,
            Integer providerCode,
            Integer busCityCode
    ) {
    }
}