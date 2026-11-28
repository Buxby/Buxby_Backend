package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoOAuth2UserServiceTests {

    @Test
    void 기존_회원의_이메일이_null이면_새로_받은_이메일을_보완한다() {
        AppUser existing = new AppUser(null, null, "기존 닉네임", "KAKAO", "kakao-100");
        Map<String, AppUser> users = new HashMap<>();
        users.put(key("KAKAO", "kakao-100"), existing);
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        OAuth2User result = service.processKakaoUser(kakaoUser("kakao-100", "new@example.com"));

        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(existing.getNickname()).isEqualTo("기존 닉네임");
        assertThat(result.getName()).isEqualTo("kakao-100");
        assertThat(saved).containsExactly(existing);
    }

    @Test
    void 기존_회원의_이메일이_공백이면_새_이메일로_보완한다() {
        AppUser existing = new AppUser("   ", null, "기존 닉네임", "KAKAO", "kakao-101");
        Map<String, AppUser> users = new HashMap<>();
        users.put(key("KAKAO", "kakao-101"), existing);
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        service.processKakaoUser(kakaoUser("kakao-101", "  member@example.com  "));

        assertThat(existing.getEmail()).isEqualTo("member@example.com");
        assertThat(saved).containsExactly(existing);
    }

    @Test
    void 기존_회원의_이메일이_이미_있으면_덮어쓰지_않는다() {
        AppUser existing = new AppUser("old@example.com", null, "기존 닉네임", "KAKAO", "kakao-102");
        Map<String, AppUser> users = new HashMap<>();
        users.put(key("KAKAO", "kakao-102"), existing);
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        service.processKakaoUser(kakaoUser("kakao-102", "new@example.com"));

        assertThat(existing.getEmail()).isEqualTo("old@example.com");
        assertThat(saved).isEmpty();
    }

    @Test
    void 기존_회원에게_카카오_이메일이_없으면_기존_정보를_유지한다() {
        AppUser existing = new AppUser(null, null, "기존 닉네임", "KAKAO", "kakao-103");
        Map<String, AppUser> users = new HashMap<>();
        users.put(key("KAKAO", "kakao-103"), existing);
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        service.processKakaoUser(kakaoUser("kakao-103", null));

        assertThat(existing.getEmail()).isNull();
        assertThat(saved).isEmpty();
    }

    @Test
    void 신규_회원은_이메일과_함께_생성하고_저장한다() {
        Map<String, AppUser> users = new HashMap<>();
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        service.processKakaoUser(kakaoUser("kakao-104", "new@example.com"));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getEmail()).isEqualTo("new@example.com");
        assertThat(saved.get(0).getProvider()).isEqualTo("KAKAO");
        assertThat(saved.get(0).getProviderId()).isEqualTo("kakao-104");
    }

    @Test
    void 신규_회원에게_이메일이_없어도_회원은_저장한다() {
        Map<String, AppUser> users = new HashMap<>();
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));

        service.processKakaoUser(kakaoUser("kakao-105", null));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getEmail()).isNull();
    }

    @Test
    void 카카오_고유_ID가_없으면_회원_조회나_저장을_하지_않는다() {
        Map<String, AppUser> users = new HashMap<>();
        List<AppUser> saved = new ArrayList<>();
        KakaoOAuth2UserService service = new KakaoOAuth2UserService(repository(users, saved));
        OAuth2User missingId = userWithoutId();

        assertThatThrownBy(() -> service.processKakaoUser(missingId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 ID");
        assertThat(saved).isEmpty();
    }

    private static OAuth2User kakaoUser(String id, String email) {
        Map<String, Object> profile = Map.of("nickname", "카카오 테스트 사용자");
        Map<String, Object> account = email == null
                ? Map.of("profile", profile)
                : Map.of("email", email, "profile", profile);
        Map<String, Object> attributes = Map.of(
                "id", id,
                "kakao_account", account
        );

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "id"
        );
    }

    private static OAuth2User userWithoutId() {
        Map<String, Object> attributes = Map.of(
                "kakao_account", Map.of("email", "member@example.com")
        );
        return (OAuth2User) Proxy.newProxyInstance(
                OAuth2User.class.getClassLoader(),
                new Class<?>[]{OAuth2User.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getAuthorities" -> List.of();
                    case "getName" -> "missing-id";
                    case "toString" -> "OAuth2UserWithoutId";
                    default -> null;
                }
        );
    }

    private static AppUserRepository repository(
            Map<String, AppUser> users,
            List<AppUser> saved
    ) {
        return (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByProviderAndProviderId" -> Optional.ofNullable(
                            users.get(key((String) args[0], (String) args[1]))
                    );
                    case "save" -> {
                        AppUser user = (AppUser) args[0];
                        saved.add(user);
                        users.put(key(user.getProvider(), user.getProviderId()), user);
                        yield user;
                    }
                    case "toString" -> "FakeAppUserRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "Unexpected repository call: " + method.getName()
                    );
                }
        );
    }

    private static String key(String provider, String providerId) {
        return provider + ":" + providerId;
    }
}
