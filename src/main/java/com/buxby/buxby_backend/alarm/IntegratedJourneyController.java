
package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.ai.GeminiService;
import com.buxby.buxby_backend.route.OdsayRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/journey")
public class IntegratedJourneyController {

    private final GeminiService gemini;
    private final OdsayRouteService odsay;
    private final PreparationController preparation;
    private final AlarmReservationRepository reservations;
    private final ObjectMapper mapper;

    public IntegratedJourneyController(
            GeminiService gemini,
            OdsayRouteService odsay,
            PreparationController preparation,
            AlarmReservationRepository reservations,
            ObjectMapper mapper
    ) {
        this.gemini = gemini;
        this.odsay = odsay;
        this.preparation = preparation;
        this.reservations = reservations;
        this.mapper = mapper;
    }

    // 좌표는 지도 UI/지오코딩에서 전달
    public record JourneyRequest(
            @NotBlank String userId,
            @NotBlank String message,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
            Double startLongitude,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
            Double startLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
            Double endLongitude,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
            Double endLatitude,
            LocalDateTime desiredArrivalTime,
            @Min(0) @Max(180) Integer bufferMinutes,
            String destination,
            Boolean saveReservation
    ) {}

    // JsonNode 대신 Map으로 응답하여 JSON 직렬화 문제 해결
    public record JourneyResponse(
            String status,
            String message,
            Map<String, Object> interpreted,
            Map<String, Object> route,
            Integer routeMinutes,
            Integer preparationMinutes,
            LocalDateTime desiredArrivalTime,
            LocalDateTime leaveHomeTime,
            LocalDateTime wakeTime,
            Long reservationId,
            String clientAction
    ) {}

    @PostMapping("/plan")
    public JourneyResponse plan(
            @Valid @RequestBody JourneyRequest req
    ) {

        Map<String, Object> interpreted = gemini.interpret(req.message());
        JsonNode intent = mapper.valueToTree(interpreted);

        LocalDateTime arrival = req.desiredArrivalTime();

        if (arrival == null &&
                intent.path("arrivalDateTime").isTextual()) {
            try {
                arrival = LocalDateTime.parse(
                        intent.path("arrivalDateTime").asText()
                );
            } catch (Exception e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "도착시간 형식이 올바르지 않습니다."
                );
            }
        }

        if (arrival == null) {
            return new JourneyResponse(
                    "NEEDS_ARRIVAL_TIME",
                    "도착 희망 날짜와 시간을 알려주세요.",
                    interpreted, null,
                    null, null, null, null, null, null,
                    "ASK_USER"
            );
        }

        if (!arrival.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "미래 도착시간을 입력하세요."
            );
        }

        JsonNode routes = odsay.search(
                req.startLongitude(),
                req.startLatitude(),
                req.endLongitude(),
                req.endLatitude()
        );

        Map<String, Object> routeData = mapper.convertValue(
                routes,
                new com.fasterxml.jackson.core.type.TypeReference<
                        Map<String, Object>>() {}
        );

        JsonNode paths = routes.path("result").path("path");

        if (!paths.isArray() || paths.isEmpty()) {
            return new JourneyResponse(
                    "NO_ROUTE",
                    "검색된 대중교통 경로가 없습니다.",
                    interpreted, routeData,
                    null, null, arrival, null, null, null,
                    "ASK_USER"
            );
        }

        int minutes = Integer.MAX_VALUE;

        for (JsonNode path : paths) {
            int candidate = path.path("info")
                    .path("totalTime")
                    .asInt(-1);

            if (candidate > 0 && candidate < minutes) {
                minutes = candidate;
            }
        }

        if (minutes == Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 이동시간을 확인할 수 없습니다."
            );
        }

        var predicted = preparation.predict(req.userId());

        int buffer = req.bufferMinutes() == null
                ? 5
                : req.bufferMinutes();

        LocalDateTime leave = arrival.minusMinutes(
                minutes + buffer
        );

        LocalDateTime wake = leave.minusMinutes(
                predicted.predictedMinutes()
        );

        if (!wake.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "기상 예정 시각이 지났습니다. 도착시간을 조정하세요."
            );
        }

        Long reservationId = null;

        if (Boolean.TRUE.equals(req.saveReservation())) {

            String destination =
                    (req.destination() == null ||
                            req.destination().isBlank())
                            ? intent.path("destination").asText("목적지")
                            : req.destination();

            reservationId = reservations.save(
                    new AlarmReservation(
                            req.userId(),
                            destination,
                            arrival,
                            wake,
                            leave,
                            minutes,
                            predicted.predictedMinutes()
                    )
            ).id;
        }

        return new JourneyResponse(
                "PLANNED",
                "알람 시각 계산 완료. 실제 알람 예약은 클라이언트에서 처리해야 합니다.",
                interpreted,
                routeData,
                minutes,
                predicted.predictedMinutes(),
                arrival,
                leave,
                wake,
                reservationId,
                "SCHEDULE_ON_CLIENT"
        );
    }
}
