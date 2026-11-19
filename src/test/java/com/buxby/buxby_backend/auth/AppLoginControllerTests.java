package com.buxby.buxby_backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class AppLoginControllerTests {

    private final AppLoginCodeStore store = new AppLoginCodeStore();
    private final AppLoginController controller =
            new AppLoginController(store, new HttpSessionSecurityContextRepository(), "buxby://, exp://");

    private static MockHttpServletRequest appStarted(String redirect) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(AppLoginController.REDIRECT_ATTRIBUTE, redirect);
        return request;
    }

    @Test
    void webLoginKeepsWebRedirect() {
        Authentication auth = new TestingAuthenticationToken("user", null, "ROLE_USER");
        assertThat(controller.successRedirect(new MockHttpServletRequest(), auth)).isNull();
        assertThat(controller.failureRedirect(new MockHttpServletRequest())).isNull();
    }

    @Test
    void appLoginRedirectsWithOneTimeCode() {
        Authentication auth = new TestingAuthenticationToken("user", null, "ROLE_USER");
        MockHttpServletRequest request = appStarted("buxby://auth");

        String redirect = controller.successRedirect(request, auth);
        assertThat(redirect).startsWith("buxby://auth?socialLogin=success&code=");
        // 앱 주소는 한 번 쓰고 지운다
        assertThat(request.getSession().getAttribute(AppLoginController.REDIRECT_ATTRIBUTE)).isNull();

        String code = redirect.substring(redirect.indexOf("code=") + 5);
        assertThat(store.consume(code)).contains(auth);
        // 같은 코드는 다시 쓸 수 없다
        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void appendsToExistingQuery() {
        String redirect = controller.failureRedirect(appStarted("exp://192.168.0.2:8081/--/auth?x=1"));
        assertThat(redirect).isEqualTo("exp://192.168.0.2:8081/--/auth?x=1&socialLogin=failed");
    }

    @Test
    void rejectsUnknownRedirect() {
        assertThat(controller.isAllowedRedirect("https://evil.example/auth")).isFalse();
        assertThat(controller.isAllowedRedirect("buxby://auth")).isTrue();
        Authentication auth = new TestingAuthenticationToken("user", null, "ROLE_USER");
        assertThat(controller.successRedirect(appStarted("https://evil.example"), auth)).isNull();
    }

    @Test
    void exchangeCreatesSession() {
        Authentication auth = new TestingAuthenticationToken("user", null, "ROLE_USER");
        String code = store.issue(auth);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.exchange(new AppLoginController.ExchangeRequest(code), request, response)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();

        assertThat(controller.exchange(new AppLoginController.ExchangeRequest(code), request, response)
                .getStatusCode().value()).isEqualTo(401);
    }
}
