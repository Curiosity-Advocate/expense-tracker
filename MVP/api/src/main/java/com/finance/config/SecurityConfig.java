package com.finance.config;

import com.finance.security.AsUserIdFilter;
import com.finance.security.JwtAuthenticationEntryPoint;
import com.finance.security.JwtAuthenticationFilter;
import com.finance.security.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final TraceIdFilter traceIdFilter;
    private final AsUserIdFilter asUserIdFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          JwtAuthenticationEntryPoint entryPoint,
                          TraceIdFilter traceIdFilter,
                          AsUserIdFilter asUserIdFilter) {
        this.jwtFilter = jwtFilter;
        this.entryPoint = entryPoint;
        this.traceIdFilter = traceIdFilter;
        this.asUserIdFilter = asUserIdFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // More-specific rule first: /sudo-tokens requires Bearer
                        // even though it lives under /api/v1/auth/**. Spring Security
                        // matches first rule that fits, so this catches before the
                        // blanket permitAll below.
                        .requestMatchers("/api/v1/auth/sudo-tokens").authenticated()
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api-docs/**",
                                "/v3/api-docs/**",
                                "/actuator/health"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                // TraceIdFilter must run before JwtAuthenticationFilter so the trace ID
                // is set in MDC for every log line, including auth failures.
                .addFilterBefore(traceIdFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // D3 — AsUserIdFilter substitutes the principal for delegated
                // requests. Runs AFTER JwtAuthenticationFilter (needs the JWT-derived
                // principal in SecurityContext to validate against).
                .addFilterAfter(asUserIdFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
