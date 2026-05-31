package com.finance.security;

import com.finance.domain.SudoTokenVerification;
import com.finance.domain.UserPrincipal;
import com.finance.exception.InvalidSudoTokenException;
import com.finance.service.SudoTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// D3 — runtime activation of delegation. Detects ?asUserId=<grantor> on
// expense endpoints, validates the X-Sudo-Token header against an active
// D1 grant + D2 sudo token, and substitutes the SecurityContext principal
// so downstream code operates on the grantor's data with the grantee
// recorded as the acting user for audit.
//
// Runs AFTER JwtAuthenticationFilter (we need the current user already in
// SecurityContext) and BEFORE the controller dispatches. Registered in
// SecurityConfig.addFilterAfter(asUserIdFilter, JwtAuthenticationFilter.class).
@Component
public class AsUserIdFilter extends OncePerRequestFilter {

    private static final String AS_USER_ID_PARAM = "asUserId";
    private static final String SUDO_TOKEN_HEADER = "X-Sudo-Token";

    // Delegation is intentionally scoped to expense endpoints only. Auth,
    // profile, access-grant management, and admin operations should never
    // be delegated. Extending the list (categories, targets) is a future
    // policy decision.
    private static final List<String> DELEGATION_ALLOWED_PREFIXES = List.of("/api/v1/expenses");

    private final SudoTokenService sudoTokenService;

    public AsUserIdFilter(SudoTokenService sudoTokenService) {
        this.sudoTokenService = sudoTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String asUserIdRaw = request.getParameter(AS_USER_ID_PARAM);
        if (asUserIdRaw == null || asUserIdRaw.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        UUID asUserId;
        try {
            asUserId = UUID.fromString(asUserIdRaw);
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "VALIDATION_ERROR", "asUserId must be a UUID");
            return;
        }

        if (!isDelegationAllowedOn(request.getRequestURI())) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "ASUSER_NOT_ALLOWED_HERE",
                    "Delegation is not supported on this endpoint");
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal currentPrincipal)) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORISED", "Bearer authentication required");
            return;
        }

        // Self-delegation = no-op. Lets callers include ?asUserId=<self> during
        // development or in code that constructs URLs without conditionals.
        if (asUserId.equals(currentPrincipal.userId())) {
            chain.doFilter(request, response);
            return;
        }

        String sudoToken = request.getHeader(SUDO_TOKEN_HEADER);
        if (sudoToken == null || sudoToken.isBlank()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_SUDO_TOKEN", "Invalid sudo token");
            return;
        }

        SudoTokenVerification verification;
        try {
            verification = sudoTokenService.verify(sudoToken, currentPrincipal.userId());
        } catch (InvalidSudoTokenException e) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_SUDO_TOKEN", "Invalid sudo token");
            return;
        }

        // Sudo token's underlying grantor must match the asUserId claim.
        // Mismatch = same error code as unknown token (enumeration defence).
        if (!verification.grantorId().equals(asUserId)) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_SUDO_TOKEN", "Invalid sudo token");
            return;
        }

        // Substitute the principal. userId = grantor (RLS sees A's data);
        // actingAs = grantee (S5 audit triggers record B). Username carries
        // over from the JWT — it refers to the requestor (B) regardless of
        // delegation, so principal.username() and principal.userId() refer
        // to different people here. Rarely a problem in practice.
        UserPrincipal delegatedPrincipal = new UserPrincipal(
                verification.grantorId(),
                currentPrincipal.username(),
                verification.granteeId());
        UsernamePasswordAuthenticationToken substituted =
                new UsernamePasswordAuthenticationToken(
                        delegatedPrincipal, null, auth.getAuthorities());
        substituted.setDetails(auth.getDetails());
        SecurityContextHolder.getContext().setAuthentication(substituted);

        chain.doFilter(request, response);
    }

    private boolean isDelegationAllowedOn(String uri) {
        return DELEGATION_ALLOWED_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    // Builds the same envelope shape GlobalExceptionHandler.error(...) emits.
    // Duplicated here because @RestControllerAdvice doesn't catch exceptions
    // from filters — they run before the controller dispatch.
    private void sendError(HttpServletResponse response, int status,
                           String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        String traceId = MDC.get("traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        response.getWriter().write(String.format(
                "{\"error\":{\"code\":\"%s\",\"message\":\"%s\","
                        + "\"timestamp\":\"%s\",\"traceId\":\"%s\"}}",
                code, message, Instant.now(), traceId));
    }
}
