package com.finance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

// All 401 responses from the filter chain go through here — one place,
// consistent shape, matching the error envelope defined in api_design.md.
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");

        String body = String.format(
                "{\"error\":{\"code\":\"UNAUTHORISED\",\"message\":\"%s\",\"timestamp\":\"%s\"}}",
                authException.getMessage(),
                Instant.now()
        );

        response.getWriter().write(body);
    }
}