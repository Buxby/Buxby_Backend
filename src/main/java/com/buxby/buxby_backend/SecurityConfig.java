package com.buxby.buxby_backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                // 개발 단계에서는 모든 페이지 접근 허용
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )

                // H2 콘솔의 CSRF 검사 제외
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/h2-console",
                                "/h2-console/**"
                        )
                )

                // H2 콘솔의 프레임 표시 허용
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                )

                // 기본 로그인 화면 비활성화
                .formLogin(form -> form.disable());

        return http.build();
    }
}