package com.buxby.buxby_backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AppLoginStartFilterTests {

    private final AppLoginStartFilter filter = new AppLoginStartFilter();

    private static void pending(HttpSession session, String provider, long startedAt) {
        session.setAttribute(
                AppLoginController.PENDING_ATTRIBUTE,
                new AppLoginController.PendingLogin(provider, startedAt, "buxby://auth")
        );
    }

    // 같은 브라우저 세션으로 소셜 로그인 시작 주소를 연다
    private MockHttpServletRequest start(MockHttpSession session, String provider) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/" + provider);
        request.setSession(session);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // 다음 필터(OAuth 이동)로 계속 넘긴다
        return request;
    }

    private static Object redirect(HttpSession session) {
        return session.getAttribute(AppLoginController.REDIRECT_ATTRIBUTE);
    }

    @Test
    void appStartArmsRedirect() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pending(session, "kakao", System.currentTimeMillis());

        start(session, "kakao");

        assertThat(redirect(session)).isEqualTo("buxby://auth");
        assertThat(session.getAttribute(AppLoginController.PENDING_ATTRIBUTE)).isNull();
    }

    @Test
    void webLoginAfterAbandonedAppLoginGoesToWeb() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // 앱 로그인을 시작해서 앱 주소가 확정된 뒤, 카카오 화면에서 그만뒀다
        pending(session, "kakao", System.currentTimeMillis());
        start(session, "kakao");
        assertThat(redirect(session)).isEqualTo("buxby://auth");

        // 같은 브라우저에서 웹으로 다시 로그인 시작
        start(session, "google");

        assertThat(redirect(session)).isNull();
    }

    @Test
    void ignoresPendingForOtherProvider() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pending(session, "kakao", System.currentTimeMillis());

        start(session, "naver");

        assertThat(redirect(session)).isNull();
        assertThat(session.getAttribute(AppLoginController.PENDING_ATTRIBUTE)).isNull();
    }

    @Test
    void ignoresStalePending() throws Exception {
        MockHttpSession session = new MockHttpSession();
        pending(session, "kakao", System.currentTimeMillis() - 5 * 60_000);

        start(session, "kakao");

        assertThat(redirect(session)).isNull();
    }

    @Test
    void otherPathsUntouched() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppLoginController.REDIRECT_ATTRIBUTE, "buxby://auth");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // 로그인 콜백에서는 지우지 않는다 (성공 처리기가 써야 한다)
        assertThat(redirect(session)).isEqualTo("buxby://auth");
    }
}
