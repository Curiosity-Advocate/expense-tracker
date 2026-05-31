package com.finance.security;

import com.finance.domain.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

// Runs once per request. Extracts the JWT, validates signature + expiry,
// and sets the UserPrincipal in the SecurityContext for the request.
//
// No per-request DB lookup: S4 introduced 15-minute access tokens, so the
// stolen-token window is bounded by expiry alone. Revocation (logout, reuse
// detection) is enforced on the refresh-token side, not on access tokens.
// See ADR-0009 (superseded) and the S4 design.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        if (!jwtService.isValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId    = jwtService.getUserId(token);
        String username = jwtService.getUsername(token);

        // Non-delegated: actingAs = null. D3's AsUserIdFilter swaps this
        // principal with a 3-arg variant when ?asUserId= + a valid sudo token
        // are present on a delegation-allowed endpoint.
        UserPrincipal principal = UserPrincipal.of(userId, username);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of()
        );
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
