package com.finance.security;

import com.finance.domain.UserPrincipal;
import com.finance.repository.RevokedTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService             jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                    RevokedTokenRepository revokedTokenRepository) {
        this.jwtService             = jwtService;
        this.revokedTokenRepository = revokedTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        // No Authorization header — pass through.
        // SecurityConfig rejects the request if the endpoint requires authentication.
        // Public endpoints (/auth/*, /swagger-ui/**) reach their controller without a token.
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7); // strip "Bearer "

        try {
            Claims claims = jwtService.parseToken(token);

            // Check revocation — token may be structurally valid but logged out.
            if (revokedTokenRepository.existsByTokenJti(claims.getId())) {
                throw new BadCredentialsException("Token has been revoked");
            }

            // Token is valid and not revoked — build UserPrincipal and set it.
            UserPrincipal principal = new UserPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get("username", String.class)
            );

            // UsernamePasswordAuthenticationToken with a non-null authorities list
            // signals to Spring Security that the request is fully authenticated.
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            Collections.emptyList()
                    )
            );

        } catch (JwtException ex) {
            // Expired, malformed, or bad signature — all produce the same 401.
            // Never leak which specific check failed.
            throw new BadCredentialsException("Invalid or expired token", ex);
        }

        filterChain.doFilter(request, response);
    }
}