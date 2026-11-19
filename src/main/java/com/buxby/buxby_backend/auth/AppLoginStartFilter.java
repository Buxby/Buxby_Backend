package com.buxby.buxby_backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.time.Duration;

import org.springframework.web.filter.OncePerRequestFilter;

// 소셜 로그인이 시작될 때(/oauth2/authorization/{provider}) 이번 로그인이 앱에서 온 것인지 정한다.
// - 바로 앞에서 /api/auth/app/social/{provider} 를 거쳐 왔으면(대기 값이 있고, 같은 provider, 1분 이내) 앱 주소를 확정한다
// - 그 밖의 시작(웹 로그인)은 남아 있던 앱 주소를 지운다
//   → 앱 로그인을 중간에 그만둔 브라우저(안드로이드는 Chrome과 쿠키 공유)에서 웹으로 로그인해도 웹으로 돌아간다
// SecurityConfig 에서 OAuth2AuthorizationRequestRedirectFilter 앞에 둔다 (빈으로 등록하지 않음: 서블릿 필터로 두 번 걸리지 않게)
public class AppLoginStartFilter extends OncePerRequestFilter {

    private static final String START_PREFIX = "/oauth2/authorization/";
    private static final long PENDING_TTL_MILLIS = Duration.ofMinutes(1).toMillis();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(START_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String provider = request.getRequestURI().substring(START_PREFIX.length());
            Object pending = session.getAttribute(AppLoginController.PENDING_ATTRIBUTE);
            session.removeAttribute(AppLoginController.PENDING_ATTRIBUTE);

            if (pending instanceof AppLoginController.PendingLogin login
                    && login.provider().equals(provider)
                    && System.currentTimeMillis() - login.startedAtMillis() <= PENDING_TTL_MILLIS) {
                session.setAttribute(AppLoginController.REDIRECT_ATTRIBUTE, login.redirectUri());
            } else {
                session.removeAttribute(AppLoginController.REDIRECT_ATTRIBUTE);
            }
        }
        filterChain.doFilter(request, response);
    }
}
