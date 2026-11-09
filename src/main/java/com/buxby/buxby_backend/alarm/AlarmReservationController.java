package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/alarm-reservations")
public class AlarmReservationController {
    private final AlarmReservationRepository repository;
    private final CurrentUserService currentUser;
    public AlarmReservationController(AlarmReservationRepository repository, CurrentUserService currentUser) { this.repository=repository; this.currentUser=currentUser; }
    public record CreateRequest(@NotBlank String destination,
        @NotNull LocalDateTime desiredArrivalTime, @NotNull LocalDateTime wakeTime,
        @NotNull LocalDateTime leaveHomeTime, int routeMinutes, int preparationMinutes) {}
    public record StatusRequest(@NotBlank String status) {}
    @PostMapping
    public AlarmReservation create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        String userId=currentUser.requireUserId(authentication);
        if (request.routeMinutes()<1 || request.preparationMinutes()<0 ||
            !request.wakeTime().isAfter(LocalDateTime.now()) ||
            !request.leaveHomeTime().isAfter(request.wakeTime()) ||
            !request.desiredArrivalTime().isAfter(request.leaveHomeTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"알람 시간 또는 이동/준비 시간이 올바르지 않습니다.");
        return repository.save(new AlarmReservation(userId,request.destination(),request.desiredArrivalTime(),
            request.wakeTime(),request.leaveHomeTime(),request.routeMinutes(),request.preparationMinutes()));
    }
    @GetMapping
    public List<AlarmReservation> list(Authentication authentication) {
        return repository.findByUserIdOrderByDesiredArrivalTimeDesc(currentUser.requireUserId(authentication));
    }
    @PatchMapping("/{id}/status")
    public AlarmReservation status(@PathVariable Long id, @Valid @RequestBody StatusRequest request, Authentication authentication) {
        AlarmReservation alarm=repository.findByIdAndUserId(id,currentUser.requireUserId(authentication))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"알람을 찾을 수 없습니다."));
        if (!List.of("PENDING_CLIENT","SCHEDULED","FAILED","CANCELLED").contains(request.status()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하지 않는 상태입니다.");
        alarm.status=request.status();return repository.save(alarm);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        AlarmReservation alarm=repository.findByIdAndUserId(id,currentUser.requireUserId(authentication))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"알람을 찾을 수 없습니다."));
        repository.delete(alarm);
    }
}
