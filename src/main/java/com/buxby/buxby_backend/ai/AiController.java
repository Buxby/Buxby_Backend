
package com.buxby.buxby_backend.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final GeminiService service;

    public AiController(GeminiService service) {
        this.service = service;
    }

    @PostMapping("/interpret")
    public Map<String, Object> interpret(
            @Valid @RequestBody ChatRequest request
    ) {
        return service.interpret(request.message());
    }

    public record ChatRequest(
            @NotBlank String message
    ) {}
}
