package com.buxby.buxby_backend.alarm;

import com.buxby.buxby_backend.auth.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    // ODsay 경로 하나(정류장 목록 포함)는 보통 수십 KB라 넉넉하게 잡는다
    private static final int MAX_PATH_JSON_LENGTH = 300_000;

    private final AlarmReservationRepository repository;
    private final CurrentUserService currentUser;
    private final ObjectMapper mapper;
    public AlarmReservationController(AlarmReservationRepository repository, CurrentUserService currentUser,
                                      ObjectMapper mapper) {
        this.repository=repository; this.currentUser=currentUser; this.mapper=mapper;
    }
    // selectedPathJson·bufferMinutes는 선택 항목이다. 사용자가 고른 ODsay 경로를 함께 저장하면
    // 자동 점검(AlarmAutoCheckScheduler)과 버스 놓침 대체 경로(/api/missed-bus/reroute)를 쓸 수 있다.
    public record CreateRequest(@NotBlank String destination,
        @NotNull LocalDateTime desiredArrivalTime, @NotNull LocalDateTime wakeTime,
        @NotNull LocalDateTime leaveHomeTime, int routeMinutes, int preparationMinutes,
        String selectedPathJson, @Min(0) @Max(180) Integer bufferMinutes) {}
    public record StatusRequest(@NotBlank String status) {}
    @PostMapping
    public AlarmReservation create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        String userId=currentUser.requireUserId(authentication);
        if (request.routeMinutes()<1 || request.preparationMinutes()<0 ||
            !request.wakeTime().isAfter(LocalDateTime.now()) ||
            !request.leaveHomeTime().isAfter(request.wakeTime()) ||
            !request.desiredArrivalTime().isAfter(request.leaveHomeTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"알람 시간 또는 이동/준비 시간이 올바르지 않습니다.");
        AlarmReservation reservation = new AlarmReservation(userId,request.destination(),request.desiredArrivalTime(),
            request.wakeTime(),request.leaveHomeTime(),request.routeMinutes(),request.preparationMinutes());
        String path = validPath(request.selectedPathJson());
        if (path != null) {
            reservation.selectedPathJson = path;
            reservation.autoCheckStatus = "WAITING";
        }
        reservation.bufferMinutes = request.bufferMinutes();
        return repository.save(reservation);
    }

    // 경로는 ODsay 경로 객체(subPath 포함)여야 한다. 비어 있으면 저장하지 않는다.
    private String validPath(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > MAX_PATH_JSON_LENGTH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"경로 정보가 너무 큽니다.");
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.path("subPath").isArray())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"경로 정보 형식이 올바르지 않습니다.");
            return node.toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"경로 정보 형식이 올바르지 않습니다.");
        }
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
