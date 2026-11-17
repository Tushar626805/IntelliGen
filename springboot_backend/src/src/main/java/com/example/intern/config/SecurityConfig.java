package com.example.intern.config;

import com.example.intern.security.CsrfCookieFilter;
import com.example.intern.security.SessionRefreshFilter;
import com.example.intern.security.UserDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Session-based security. The login endpoint creates a server-side session (HttpOnly cookie).
 * Every /api request is authorized by role here, and object-level ownership/college checks are
 * enforced inside the controllers using the session identity (never client-supplied identity).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserDirectory userDirectory) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // CSRF protection: token lives in the XSRF-TOKEN cookie; api.js sends it as X-XSRF-TOKEN.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new SessionRefreshFilter(userDirectory), SecurityContextHolderFilter.class)

                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public API endpoints
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/api/auth/forgot-password", "/api/auth/reset-password",
                                "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()

                        // Any logged-in user
                        .requestMatchers("/api/auth/me", "/api/notifications/**").authenticated()

                        // Role-restricted API areas
                        .requestMatchers("/api/auth/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/principal/**", "/api/principal/**").hasRole("PRINCIPAL")
                        .requestMatchers("/api/hod/**").hasRole("HOD")
                        .requestMatchers("/api/exam/student/**", "/api/exam/results/student/**").hasRole("STUDENT")
                        .requestMatchers("/api/exam/**", "/api/ai/**").hasAnyRole("TEACHER", "HOD", "PRINCIPAL")

                        // Any other API path is closed by default
                        .requestMatchers("/api/**").denyAll()

                        // Static pages/assets (they contain no data; all data comes through the protected API)
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    /** No cross-origin access unless explicitly listed in app.cors.allowed-origins. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
            config.setAllowCredentials(true);
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
