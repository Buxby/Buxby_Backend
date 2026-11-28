
package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;

import java.util.Map;
import java.util.Optional;

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

        return processKakaoUser(kakaoUser);
    }

    // 카카오 사용자 속성 처리와 회원 생성/보완 로직을 분리해 테스트 가능하게 한다.
    OAuth2User processKakaoUser(OAuth2User kakaoUser) {
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

            if (emailObject instanceof String value && !value.isBlank()) {
                email = value.trim();
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

        // provider + providerId로 기존 카카오 회원 조회
        Optional<AppUser> existingUser =
                userRepository.findByProviderAndProviderId(
                        "KAKAO",
                        providerId
                );

        if (existingUser.isPresent()) {
            // 기존 이메일이 비어 있고 카카오가 새 이메일을 제공한 경우에만 보완한다.
            AppUser user = existingUser.get();
            if (user.updateEmailIfMissing(email)) {
                userRepository.save(user);
            }
        } else {
            // 신규 회원은 카카오에서 받은 이메일이 있을 때 함께 저장한다.
            AppUser user = new AppUser(
                    email,
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
