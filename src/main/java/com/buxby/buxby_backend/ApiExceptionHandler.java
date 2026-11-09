package com.buxby.buxby_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // 4xx 사유는 사용자에게 보여주고, 5xx 사유는 외부 API 오류 등 내부 정보라 로그에만 남긴다.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String reason = exception.getReason();

        if (status.is5xxServerError()) {
            log.warn("{}: {}", status, reason);
            return ResponseEntity.status(status).build();
        }

        return reason == null
                ? ResponseEntity.status(status).build()
                : ResponseEntity.status(status).body(Map.of("message", reason));
    }
}
