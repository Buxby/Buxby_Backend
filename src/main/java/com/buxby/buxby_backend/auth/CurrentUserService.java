package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;

// 세션의 인증 정보로 회원을 찾는다. 요청 값으로 받은 userId는 신뢰하지 않는다.
@Service
public class CurrentUserService {

    private final AppUserRepository userRepository;

    public CurrentUserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<AppUser> find(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }

        String provider = authentication instanceof OAuth2AuthenticationToken oauth
                ? oauth.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT)
                : "LOCAL";

        return userRepository.findByProviderAndProviderId(provider, authentication.getName());
    }

    public String requireUserId(Authentication authentication) {
        return find(authentication)
                .map(user -> String.valueOf(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }
}
