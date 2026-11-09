package com.buxby.buxby_backend.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SeoulBusAutoMatchServiceTest {

    private final JsonNode paths = read("""
            [
              {"info":{"totalTime":40},"subPath":[{"trafficType":1,"startName":"구로역"}]},
              {"info":{"totalTime":30},"subPath":[{"trafficType":2,"startName":"A정류장","lane":[{"busNo":"6513"}]}]},
              {"info":{"totalTime":35},"subPath":[{"trafficType":2,"startName":"B정류장","lane":[{"busNo":"5714"},{"busNo":"6515"}]}]},
              {"info":{"totalTime":45},"subPath":[{"trafficType":2,"startName":"C정류장","lane":[{"busNo":"5714"}]}]}
            ]
            """);

    @Test
    void picksShortestBusPathWhenNoBusIsRequested() {
        assertEquals(30, minutes(SeoulBusAutoMatchService.shortestBusPath(paths, null, null)));
    }

    @Test
    void picksShortestPathContainingRequestedBus() {
        assertEquals(35, minutes(SeoulBusAutoMatchService.shortestBusPath(paths, "5714", null)));
        assertEquals(35, minutes(SeoulBusAutoMatchService.shortestBusPath(paths, "6515", null)));
    }

    @Test
    void narrowsToRequestedBoardingStation() {
        assertEquals(45, minutes(SeoulBusAutoMatchService.shortestBusPath(paths, "5714", "C정류장")));
    }

    @Test
    void returnsNullWhenRequestedBusIsNotInAnyPath() {
        assertNull(SeoulBusAutoMatchService.shortestBusPath(paths, "9999", null));
    }

    private static int minutes(JsonNode path) {
        return path.path("info").path("totalTime").asInt();
    }

    private static JsonNode read(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
