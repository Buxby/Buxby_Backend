
package com.buxby.buxby_backend.auth;

import com.buxby.buxby_backend.user.AppUser;
import com.buxby.buxby_backend.user.AppUserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

import java.util.Locale;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;

    public AuthController(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
    }

    // 회원가입 요청
    public record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 30) String nickname
    ) {}

    // 로그인 요청
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    // 사용자 정보 응답
    public record UserResponse(
            Long id,
            String email,
            String nickname,
            String provider
    ) {}

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<?> signup(
            @Valid @RequestBody SignupRequest request
    ) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByProviderAndProviderId(
                "LOCAL", email)) {

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "message", "이미 가입된 이메일입니다."
                    ));
        }

        String encodedPassword =
                passwordEncoder.encode(request.password());

        AppUser user = new AppUser(
                email,
                encodedPassword,
                request.nickname().trim(),
                "LOCAL",
                email
        );

        userRepository.save(user);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "회원가입 성공"
                ));
    }

    // 로그인
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        // 이메일과 비밀번호 인증
        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                email,
                                request.password()
                        )
                );

        // 기존 세션이 있다면 ID 변경
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        // 인증 정보를 SecurityContext에 저장
        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);

        SecurityContextHolder.setContext(context);

        // 인증 정보를 HTTP 세션에 저장
        contextRepository.saveContext(
                context,
                httpRequest,
                httpResponse
        );

        return ResponseEntity.ok(
                Map.of(
                        "message", "로그인 성공",
                        "email", authentication.getName()
                )
        );
    }

    // 현재 로그인한 사용자 정보

    @GetMapping("/me")
    public ResponseEntity<?> me(
            Authentication authentication
    ) {

        if (authentication == null ||
                authentication instanceof AnonymousAuthenticationToken) {

            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "message", "로그인이 필요합니다."
                    ));
        }

        // 기본은 일반 로그인
        String provider = "LOCAL";

        // 일반 로그인 사용자의 식별값은 이메일
        String providerId = authentication.getName();

        // 카카오 로그인이라면 제공자와 사용자 ID 변경
        if (authentication instanceof OAuth2AuthenticationToken oauth) {

            provider = oauth.getAuthorizedClientRegistrationId()
                    .toUpperCase(Locale.ROOT);

            providerId = authentication.getName();
        }

        // 해당 로그인 방식에 맞는 회원 조회
        return userRepository.findByProviderAndProviderId(
                        provider,
                        providerId
                )
                .<ResponseEntity<?>>map(user ->
                        ResponseEntity.ok(
                                new UserResponse(
                                        user.getId(),
                                        user.getEmail(),
                                        user.getNickname(),
                                        user.getProvider()
                                )
                        )
                )
                .orElseGet(() ->
                        ResponseEntity.notFound().build()
                );
    }


    // 로그인 실패 처리
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> authenticationException(
            AuthenticationException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "message",
                        "이메일 또는 비밀번호가 올바르지 않습니다."
                ));
    }
}
