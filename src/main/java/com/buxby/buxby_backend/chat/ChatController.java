package com.buxby.buxby_backend.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/ai")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(
            @NotBlank @Size(max = 1000) String message,
            String sessionId,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double startLongitude,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double startLatitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double endLongitude,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double endLatitude,
            LocalDateTime desiredArrivalTime,
            @Min(0) @Max(180) Integer bufferMinutes,
            String destination,
            Boolean confirmReservation,
            String selectedDestinationAddress
    ) {}

    @PostMapping("/chat")
    public ChatService.ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        return chatService.chat(request, authentication);
    }
}
