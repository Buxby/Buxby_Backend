package com.buxby.buxby_backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

// 앱(React Native) 간편 로그인
// 1) 앱이 시스템 브라우저로 GET /api/auth/app/social/{provider}?redirect_uri=buxby://auth 를 연다
//    (웹과 같은 주소 https://buxby.vercel.app 에서 시작해야 소셜 콜백 주소·브라우저 세션이 맞는다)
// 2) 돌아올 앱 주소를 브라우저 세션에 적어 두고, 기존 /oauth2/authorization/{provider} 흐름으로 보낸다
// 3) 로그인이 끝나면 SecurityConfig 성공 처리기가 앱 주소로 일회용 코드를 붙여 보낸다
// 4) 앱이 POST /api/auth/app/exchange 로 코드를 내면 앱 쪽 세션(JSESSIONID)을 만들어 준다
@RestController
@RequestMapping("/api/auth/app")
public class AppLoginController {

    // 브라우저 세션에 적어 두는 "로그인이 끝나면 돌아갈 앱 주소"
    public static final String REDIRECT_ATTRIBUTE = "APP_LOGIN_REDIRECT";

    private static final Set<String> PROVIDERS = Set.of("kakao", "naver", "google");

    private final AppLoginCodeStore codeStore;
    private final SecurityContextRepository contextRepository;
    private final List<String> allowedRedirectPrefixes;

    public AppLoginController(
            AppLoginCodeStore codeStore,
            SecurityContextRepository contextRepository,
            @Value("${app.mobile-redirect-prefixes:buxby://}") String allowedRedirectPrefixes
    ) {
        this.codeStore = codeStore;
        this.contextRepository = contextRepository;
        this.allowedRedirectPrefixes = Arrays.stream(allowedRedirectPrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
    }

    public record ExchangeRequest(String code) {}

    // 등록된 앱 주소로만 돌려보낸다 (아무 주소로나 코드를 보내지 않게)
    public boolean isAllowedRedirect(String redirectUri) {
        return redirectUri != null && allowedRedirectPrefixes.stream().anyMatch(redirectUri::startsWith);
    }

    @GetMapping("/social/{provider}")
    public void start(
            @PathVariable String provider,
            @RequestParam("redirect_uri") String redirectUri,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (!PROVIDERS.contains(provider)) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "지원하지 않는 로그인 방식입니다.");
            return;
        }
        if (!isAllowedRedirect(redirectUri)) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "허용되지 않은 앱 주소입니다.");
            return;
        }
        request.getSession(true).setAttribute(REDIRECT_ATTRIBUTE, redirectUri);
        response.sendRedirect("/oauth2/authorization/" + provider);
    }

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(
            @RequestBody ExchangeRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication = codeStore.consume(body == null ? null : body.code()).orElse(null);
        if (authentication == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "로그인 코드가 만료되었어요. 다시 시도해 주세요."));
        }

        // 이메일 로그인과 같은 방식으로 세션에 인증 정보를 저장한다
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(Map.of("message", "로그인 성공"));
    }

    // 성공 처리기: 앱에서 시작한 로그인이면 일회용 코드를 붙인 앱 주소, 아니면 null (웹 흐름 그대로)
    public String successRedirect(HttpServletRequest request, Authentication authentication) {
        String redirectUri = takeRedirect(request);
        if (redirectUri == null) {
            return null;
        }
        return withQuery(redirectUri, "socialLogin=success&code=" + codeStore.issue(authentication));
    }

    // 실패 처리기: 앱에서 시작한 로그인이면 실패를 알리는 앱 주소, 아니면 null
    public String failureRedirect(HttpServletRequest request) {
        String redirectUri = takeRedirect(request);
        return redirectUri == null ? null : withQuery(redirectUri, "socialLogin=failed");
    }

    private String takeRedirect(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(REDIRECT_ATTRIBUTE);
        session.removeAttribute(REDIRECT_ATTRIBUTE);
        return value instanceof String uri && isAllowedRedirect(uri) ? uri : null;
    }

    private static String withQuery(String uri, String query) {
        return uri + (uri.contains("?") ? "&" : "?") + query;
    }
}
