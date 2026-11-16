
package com.buxby.buxby_backend;

import com.buxby.buxby_backend.auth.CustomOAuth2UserService;
import com.buxby.buxby_backend.user.AppUserRepository;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.config.Customizer;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public UserDetailsService userDetailsService(
            AppUserRepository repository
    ) {
        return email -> repository
                .findByProviderAndProviderId(
                        "LOCAL",
                        email.trim().toLowerCase(Locale.ROOT)
                )
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPassword())
                        .roles("USER")
                        .build()
                )
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "사용자를 찾을 수 없습니다."
                        )
                );
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CustomOAuth2UserService customOAuth2UserService,
            @Value("${app.frontend-url}") String frontendUrl
    ) throws Exception {

        http
                .cors(Customizer.withDefaults())

                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/h2-console",
                                "/h2-console/**"
                        ).permitAll()

                        .requestMatchers("/api/auth/me").authenticated()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,
                                "/api/seoul/bus/positions/collect",
                                "/api/seoul/bus/positions/collect-batch"
                        ).authenticated()

                        .anyRequest().permitAll()
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (request, response, exception) ->
                                        response.setStatus(401)
                        )
                )

                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/h2-console",
                                "/h2-console/**",
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/logout",

                                "/api/ai/**",
                                "/api/journey/**",
                                "/api/alarm/**",
                                "/api/alarms/**",
                                "/api/alarm-reservations",
                                "/api/alarm-reservations/**",

                                // V11 신규 API
                                "/api/alarm-operations/**",
                                "/api/alarm-sync",
                                "/api/alarm-sync/**",

                                "/api/preparation/**",
                                "/api/route/**",
                                "/api/bus/**",
                                "/api/seoul/bus/positions/collect",
                                "/api/seoul/bus/positions/collect-batch"
                        )
                )

                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                )

                .formLogin(form -> form.disable())

                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )

                        .successHandler((request, response, authentication) -> {
                            response.sendRedirect(
                                    frontendUrl + "/?socialLogin=success"
                            );
                        })

                        .failureHandler((request, response, exception) -> {
                            exception.printStackTrace();

                            response.sendRedirect(
                                    frontendUrl + "/?socialLogin=failed"
                            );
                        })
                )

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(204)
                        )
                );

        return http.build();
    }
}
