package com.buxby.buxby_backend.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

// 앱 간편 로그인용 일회용 코드 보관소
// 소셜 로그인은 시스템 브라우저에서 끝나서 로그인 세션도 브라우저에만 생긴다.
// 브라우저 → 앱(buxby://)으로 이 코드만 넘기고, 앱이 /api/auth/app/exchange 로 바꿔 앱의 세션을 만든다.
// 서버 메모리에만 두므로 서버를 여러 대로 늘리면 공용 저장소(DB·Redis)로 옮겨야 한다.
@Component
public class AppLoginCodeStore {

    private static final Duration TTL = Duration.ofMinutes(2);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    private record Entry(Authentication authentication, Instant expiresAt) {}

    public String issue(Authentication authentication) {
        removeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codes.put(code, new Entry(authentication, Instant.now().plus(TTL)));
        return code;
    }

    // 한 번 쓰면 사라진다. 없거나 만료됐으면 빈 값
    public Optional<Authentication> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Entry entry = codes.remove(code);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.authentication());
    }

    private void removeExpired() {
        Instant now = Instant.now();
        codes.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    }
}
