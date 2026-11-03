
package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class CustomOAuth2UserService
        implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final AppUserRepository userRepository;
    private final KakaoOAuth2UserService kakaoOAuth2UserService;

    // Google과 Naver의 사용자 정보를 가져오는 기본 서비스
    private final DefaultOAuth2UserService delegate =
            new DefaultOAuth2UserService();

    public CustomOAuth2UserService(
            AppUserRepository userRepository,
            KakaoOAuth2UserService kakaoOAuth2UserService
    ) {
        this.userRepository = userRepository;
        this.kakaoOAuth2UserService = kakaoOAuth2UserService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {

        // 어떤 소셜 로그인인지 확인
        String registrationId = userRequest
                .getClientRegistration()
                .getRegistrationId();

        // 기존 카카오 로그인은 기존 서비스에 위임
        if ("kakao".equals(registrationId)) {
            return kakaoOAuth2UserService.loadUser(userRequest);
        }

        // Google 또는 Naver 사용자 정보 가져오기
        OAuth2User oauth2User = delegate.loadUser(userRequest);

        Map<String, Object> attributes =
                oauth2User.getAttributes();

        // 반환할 사용자 정보
        Map<String, Object> principalAttributes =
                new HashMap<>(attributes);

        String provider;
        String providerId;
        String email;
        String nickname;
        String nameAttributeKey;

        // =====================================
        // Google 로그인
        // =====================================

        if ("google".equals(registrationId)) {

            provider = "GOOGLE";

            // Google 고유 사용자 ID
            providerId = getString(attributes, "sub");

            email = getString(attributes, "email");

            nickname = getString(attributes, "name");

            if (nickname == null) {
                nickname = "구글 사용자";
            }

            // Google 사용자 식별 기준
            nameAttributeKey = "sub";

        }

        // =====================================
        // Naver 로그인
        // =====================================

        else if ("naver".equals(registrationId)) {

            provider = "NAVER";

            // 네이버 응답은 response 객체 안에 들어 있음
            Object responseObject = attributes.get("response");

            if (!(responseObject instanceof Map<?, ?> naverResponse)) {
                throw new IllegalStateException(
                        "네이버 사용자 정보를 가져오지 못했습니다."
                );
            }

            // 네이버 고유 사용자 ID
            providerId = getString(naverResponse, "id");

            email = getString(naverResponse, "email");

            nickname = getString(naverResponse, "nickname");

            if (nickname == null) {
                nickname = "네이버 사용자";
            }

            // DefaultOAuth2User가 사용할 고유 식별값 추가
            principalAttributes.put("providerId", providerId);

            nameAttributeKey = "providerId";

        }

        else {

            throw new IllegalArgumentException(
                    "지원하지 않는 소셜 로그인입니다: " + registrationId
            );

        }

        // 사용자 ID 확인
        if (providerId == null) {
            throw new IllegalStateException(
                    registrationId + " 사용자 ID를 가져오지 못했습니다."
            );
        }

        // =====================================
        // 기존 회원 확인 및 신규 회원 저장
        // =====================================

        boolean exists =
                userRepository.existsByProviderAndProviderId(
                        provider,
                        providerId
                );

        if (!exists) {

            AppUser user = new AppUser(
                    email,
                    null,
                    nickname,
                    provider,
                    providerId
            );

            userRepository.save(user);
        }

        // =====================================
        // Spring Security에 사용자 정보 전달
        // =====================================

        return new DefaultOAuth2User(
                oauth2User.getAuthorities(),
                principalAttributes,
                nameAttributeKey
        );
    }

    // Map에서 문자열 데이터를 안전하게 가져오는 메서드
    private String getString(Map<?, ?> attributes, String key) {

        Object value = attributes.get(key);

        if (value == null) {
            return null;
        }

        String result = value.toString().trim();

        return result.isEmpty() ? null : result;
    }
}
