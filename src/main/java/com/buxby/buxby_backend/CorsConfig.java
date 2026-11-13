
package com.buxby.buxby_backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        // 허용할 프론트엔드 주소
        config.setAllowedOrigins(List.of(
                "https://buxby.vercel.app",
                "http://localhost:5173"
        ));

        // 허용할 HTTP 메서드
        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        // 허용할 요청 헤더
        config.setAllowedHeaders(List.of(
                "Content-Type",
                "Authorization",
                "Accept",
                "Origin",
                "X-Requested-With"
        ));

        // 세션 쿠키 허용
        config.setAllowCredentials(true);

        // Preflight 결과 캐싱 시간
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
