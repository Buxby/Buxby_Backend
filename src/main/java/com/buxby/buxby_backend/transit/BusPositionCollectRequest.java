package com.buxby.buxby_backend.transit;

import java.util.List;

public record BusPositionCollectRequest(
        List<String> busRouteIds
) {
}