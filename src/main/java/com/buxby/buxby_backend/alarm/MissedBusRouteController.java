
package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import com.buxby.buxby_backend.route.OdsayRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/missed-bus")
public class MissedBusRouteController {

    private final CurrentUserService currentUser;
    private final AlarmReservationRepository reservations;
    private final OdsayRouteService odsay;
    private final ObjectMapper mapper;

    public MissedBusRouteController(
            CurrentUserService currentUser,
            AlarmReservationRepository reservations,
            OdsayRouteService odsay,
            ObjectMapper mapper
    ) {
        this.currentUser = currentUser;
        this.reservations = reservations;
        this.odsay = odsay;
        this.mapper = mapper;
    }

    public record RerouteRequest(
            @NotNull Long reservationId,

            @NotNull
            @DecimalMin("-180.0")
            @DecimalMax("180.0")
            Double currentLongitude,

            @NotNull
            @DecimalMin("-90.0")
            @DecimalMax("90.0")
            Double currentLatitude
    ) {}

    public record RouteOption(
            int optionNumber,
            String routeType,
            int travelMinutes,
            LocalDateTime estimatedArrivalTime,
            boolean late,
            int lateMinutes,
            String summary
    ) {}

    public record RerouteResponse(
            String status,
            Long reservationId,
            String destination,
            LocalDateTime desiredArrivalTime,
            LocalDateTime searchedAt,
            List<RouteOption> options,
            String message
    ) {}

    @PostMapping("/reroute")
    public RerouteResponse reroute(
            @Valid @RequestBody RerouteRequest request,
            Authentication authentication
    ) {
        String userId = currentUser.requireUserId(authentication);

        AlarmReservation reservation = reservations
                .findByIdAndUserId(request.reservationId(), userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "본인의 알람 예약을 찾을 수 없습니다."
                ));

        if (reservation.selectedPathJson == null
                || reservation.selectedPathJson.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "기존 예약에 경로 정보가 없습니다."
            );
        }

        JsonNode previousPath;

        try {
            previousPath = mapper.readTree(
                    reservation.selectedPathJson
            );
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "기존 경로 정보를 읽을 수 없습니다."
            );
        }

        // ODsay 경로의 info.mapEX/mapEY에서 목적지 좌표 추출
        // 구형 데이터의 다른 좌표 필드도 순서대로 확인
        JsonNode info = previousPath.path("info");

        Double destinationLongitude = readCoordinate(
                info, "mapEX", "EX", "endX"
        );

        Double destinationLatitude = readCoordinate(
                info, "mapEY", "EY", "endY"
        );

        // 일부 응답은 마지막 이동 구간에 목적지 좌표가 있음
        if (destinationLongitude == null
                || destinationLatitude == null) {

            JsonNode sections = previousPath.path("subPath");

            if (sections.isArray()) {
                for (int i = sections.size() - 1; i >= 0; i--) {
                    JsonNode section = sections.get(i);

                    Double lon = readCoordinate(
                            section, "endX", "EX"
                    );

                    Double lat = readCoordinate(
                            section, "endY", "EY"
                    );

                    if (lon != null && lat != null) {
                        destinationLongitude = lon;
                        destinationLatitude = lat;
                        break;
                    }
                }
            }
        }

        if (destinationLongitude == null
                || destinationLatitude == null) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "기존 예약에서 목적지 좌표를 확인할 수 없습니다. "
                            + "목적지 좌표를 포함해 예약을 다시 생성해야 합니다."
            );
        }

        JsonNode result = odsay.search(
                request.currentLongitude(),
                request.currentLatitude(),
                destinationLongitude,
                destinationLatitude
        );

        JsonNode paths = result.path("result").path("path");

        LocalDateTime now = LocalDateTime.now();

        if (!paths.isArray() || paths.isEmpty()) {
            return new RerouteResponse(
                    "NO_ROUTE",
                    reservation.id,
                    reservation.destination,
                    reservation.desiredArrivalTime,
                    now,
                    List.of(),
                    "현재 위치에서 대체 경로를 찾지 못했어."
            );
        }

        List<RouteOption> options = new ArrayList<>();

        for (JsonNode path : paths) {
            int minutes = path.path("info")
                    .path("totalTime").asInt(-1);

            if (minutes <= 0) {
                continue;
            }

            LocalDateTime arrival = now.plusMinutes(minutes);

            boolean late = arrival.isAfter(
                    reservation.desiredArrivalTime
            );

            int lateMinutes = late
                    ? (int) Math.max(
                    1,
                    Math.ceil(
                            Duration.between(
                                    reservation.desiredArrivalTime,
                                    arrival
                            ).toSeconds() / 60.0
                    )
            )
                    : 0;

            String routeType = switch (
                    path.path("pathType").asInt(0)
                    ) {
                case 1 -> "SUBWAY";
                case 2 -> "BUS";
                case 3 -> "BUS_AND_SUBWAY";
                default -> "TRANSIT";
            };

            StringBuilder summary = new StringBuilder();

            for (JsonNode section : path.path("subPath")) {
                int type = section.path("trafficType").asInt();

                if (type == 1) {
                    String station = section.path("startName")
                            .asText("");

                    summary.append("[지하철 ")
                            .append(station)
                            .append("] ");

                } else if (type == 2) {
                    String bus = section.path("lane")
                            .path(0)
                            .path("busNo")
                            .asText("");

                    summary.append("[버스 ")
                            .append(bus)
                            .append("] ");
                }
            }

            options.add(new RouteOption(
                    0,
                    routeType,
                    minutes,
                    arrival,
                    late,
                    lateMinutes,
                    summary.toString().trim()
            ));
        }

        options.sort(
                Comparator.comparingInt(
                        RouteOption::travelMinutes
                )
        );

        List<RouteOption> selected = new ArrayList<>();

        for (int i = 0; i < Math.min(3, options.size()); i++) {
            RouteOption option = options.get(i);

            selected.add(new RouteOption(
                    i + 1,
                    option.routeType(),
                    option.travelMinutes(),
                    option.estimatedArrivalTime(),
                    option.late(),
                    option.lateMinutes(),
                    option.summary()
            ));
        }

        return new RerouteResponse(
                selected.isEmpty()
                        ? "NO_ROUTE"
                        : "ROUTES_FOUND",
                reservation.id,
                reservation.destination,
                reservation.desiredArrivalTime,
                now,
                selected,
                selected.isEmpty()
                        ? "이동 가능한 경로를 찾지 못했어."
                        : "현재 위치 기준 대체 경로를 찾았어. "
                        + "실제 버스·지하철 도착시간은 "
                        + "별도로 확인해야 해."
        );
    }

    private static Double readCoordinate(
            JsonNode node,
            String... fields
    ) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        for (String field : fields) {
            JsonNode value = node.path(field);

            if (value.isNumber()) {
                double coordinate = value.asDouble();

                if (Double.isFinite(coordinate)) {
                    return coordinate;
                }
            }

            if (value.isTextual()) {
                try {
                    double coordinate = Double.parseDouble(
                            value.asText().trim()
                    );

                    if (Double.isFinite(coordinate)) {
                        return coordinate;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return null;
    }
}
