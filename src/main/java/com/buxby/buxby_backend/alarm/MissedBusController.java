package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/missed-bus")
public class MissedBusController {

    private final CurrentUserService currentUser;
    private final AlarmReservationRepository reservations;
    private final MissedBusEventRepository events;
    private final TransitOutcomeService outcomes;

    public MissedBusController(
            CurrentUserService currentUser,
            AlarmReservationRepository reservations,
            MissedBusEventRepository events,
            TransitOutcomeService outcomes
    ) {
        this.currentUser = currentUser;
        this.reservations = reservations;
        this.events = events;
        this.outcomes = outcomes;
    }

    public record MissedBusRequest(@NotNull Long reservationId) {}

    public record MissedBusResponse(
            String status,
            Long eventId,
            Long reservationId,
            String destination,
            LocalDateTime desiredArrivalTime,
            String message
    ) {}

    @PostMapping
    @Transactional
    public MissedBusResponse report(
            @Valid @RequestBody MissedBusRequest request,
            Authentication authentication
    ) {
        String userId = currentUser.requireUserId(authentication);

        AlarmReservation reservation = reservations
                .findByIdAndUserId(request.reservationId(), userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."
                ));

        // Update the learning outcome first. A failure rolls back this transaction,
        // so a missed-bus event is not recorded without the matching outcome.
        outcomes.missed(userId, reservation.id);

        MissedBusEvent saved = events.save(
                new MissedBusEvent(userId, reservation.id)
        );

        return new MissedBusResponse(
                "LOCATION_REQUIRED",
                saved.id,
                reservation.id,
                reservation.destination,
                reservation.desiredArrivalTime,
                "버스를 놓친 기록을 저장했어. 현재 위치를 알려주면 대체 경로를 찾아볼게."
        );
    }

    @GetMapping("/history")
    public List<MissedBusEvent> history(Authentication authentication) {
        String userId = currentUser.requireUserId(authentication);
        return events.findTop20ByUserIdOrderByOccurredAtDesc(userId);
    }
}
