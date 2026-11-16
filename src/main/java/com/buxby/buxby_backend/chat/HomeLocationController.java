package com.buxby.buxby_backend.chat;

import com.buxby.buxby_backend.auth.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/ai/home")
public class HomeLocationController {
    private final CurrentUserService users;
    private final HomeLocationRepository homes;
    private final PlaceSearchService places;
    public HomeLocationController(CurrentUserService users, HomeLocationRepository homes, PlaceSearchService places) {
        this.users = users; this.homes = homes; this.places = places;
    }
    public record HomeRequest(@NotBlank String placeName, String selectedAddress) {}
    public record HomeResponse(String status, String message, String name, Double latitude,
                               Double longitude, List<PlaceSearchService.Place> candidates) {}
    @PostMapping
    public HomeResponse save(@Valid @RequestBody HomeRequest req, Authentication authentication) {
        String userId = users.requireUserId(authentication);
        List<PlaceSearchService.Place> found = places.search(req.placeName());
        if (found.isEmpty()) return new HomeResponse("NOT_FOUND", "집 위치를 찾지 못했어.", null, null, null, found);
        PlaceSearchService.Place chosen = null;
        if (req.selectedAddress() != null && !req.selectedAddress().isBlank()) {
            for (var p : found) if (p.address().equals(req.selectedAddress())) { chosen = p; break; }
            if (chosen == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 결과에 없는 주소입니다.");
        } else if (found.size() == 1) chosen = found.get(0);
        if (chosen == null) return new HomeResponse("SELECT_PLACE", "집 주소가 여러 개야. selectedAddress로 하나를 선택해 줘.", null, null, null, found);
        homes.save(new HomeLocation(userId, chosen.name(), chosen.latitude(), chosen.longitude()));
        return new HomeResponse("SAVED", "기본 출발지를 저장했어.", chosen.name(), chosen.latitude(), chosen.longitude(), List.of());
    }
    @GetMapping
    public HomeResponse get(Authentication authentication) {
        return homes.findById(users.requireUserId(authentication))
                .map(h -> new HomeResponse("SAVED", "기본 출발지", h.label, h.latitude, h.longitude, List.of()))
                .orElse(new HomeResponse("NOT_SET", "기본 출발지가 등록되지 않았어.", null, null, null, List.of()));
    }
}
