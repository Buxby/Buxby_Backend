
package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;

import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class KakaoOAuth2UserService
        implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final AppUserRepository userRepository;

    private final DefaultOAuth2UserService delegate =
            new DefaultOAuth2UserService();

    public KakaoOAuth2UserService(
            AppUserRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(
            OAuth2UserRequest userRequest
    ) {

        // 카카오에서 사용자 정보 가져오기
        OAuth2User kakaoUser =
                delegate.loadUser(userRequest);

        Map<String, Object> attributes =
                kakaoUser.getAttributes();

        // 카카오 고유 사용자 ID
        Object idObject = attributes.get("id");

        if (idObject == null) {
            throw new IllegalStateException(
                    "카카오 사용자 ID를 가져오지 못했습니다."
            );
        }

        String providerId = String.valueOf(idObject);

        // 닉네임 기본값
        String nickname = "카카오 사용자";

        // 이메일 기본값
        String email = null;

        // 카카오 계정 정보
        Object accountObject =
                attributes.get("kakao_account");

        if (accountObject instanceof Map<?, ?> account) {

            Object emailObject = account.get("email");

            if (emailObject instanceof String value) {
                email = value;
            }

            Object profileObject = account.get("profile");

            if (profileObject instanceof Map<?, ?> profile) {

                Object nicknameObject =
                        profile.get("nickname");

                if (nicknameObject instanceof String value
                        && !value.isBlank()) {

                    nickname = value;
                }
            }
        }

        // 기존 카카오 회원인지 확인
        boolean exists =
                userRepository.existsByProviderAndProviderId(
                        "KAKAO",
                        providerId
                );

        // 최초 로그인이라면 회원 정보 저장
        if (!exists) {

            AppUser user = new AppUser(
                    null,
                    null,
                    nickname,
                    "KAKAO",
                    providerId
            );

            userRepository.save(user);
        }

        // Spring Security에 사용자 정보 전달
        return new DefaultOAuth2User(
                kakaoUser.getAuthorities(),
                attributes,
                "id"
        );
    }
}
