package com.finance.controller;

import com.finance.command.LoginCommand;
import com.finance.command.RefreshTokenCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.dto.LoginRequest;
import com.finance.dto.LogoutRequest;
import com.finance.dto.RefreshTokenRequest;
import com.finance.dto.RegisterRequest;
import com.finance.dto.RegisterResponse;
import com.finance.dto.TokenResponse;
import com.finance.service.AuthService;
import com.finance.service.UserSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;
    private final UserSetupService userSetupService;

    public AuthController(AuthService authService, UserSetupService userSetupService) {
        this.authService = authService;
        this.userSetupService = userSetupService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        RegisteredUser user = authService.register(
                new RegisterCommand(req.username(), req.email(), req.password()));
        userSetupService.setupNewUser(user.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(user.userId(), user.username(), user.email(), user.createdAt()));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive an access + refresh token pair")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        TokenPair token = authService.login(new LoginCommand(req.username(), req.password()));
        return ResponseEntity.ok(toResponse(token));
    }

    // Refresh token in the body authenticates the call (RFC 6749 §6 — refresh
    // grant). No Authorization header required: the access token has expired
    // by the time the client needs to refresh, so requiring one would defeat
    // the purpose. Permitted in SecurityConfig via /api/v1/auth/**.
    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token and issue a new access + refresh pair")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        TokenPair token = authService.refresh(new RefreshTokenCommand(req.refreshToken()));
        return ResponseEntity.ok(toResponse(token));
    }

    // Logout takes the refresh token in the body (RFC 7009 pattern). No
    // Authorization header is required — possession of the refresh token is
    // the credential for ending its session. Silent 204 on stale/unknown
    // tokens (see api-contract.md).
    @PostMapping("/logout")
    @Operation(summary = "Revoke the presented refresh token")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static TokenResponse toResponse(TokenPair pair) {
        return new TokenResponse(
                pair.accessToken(),
                pair.accessTokenExpiresAt(),
                pair.refreshToken(),
                pair.refreshTokenExpiresAt(),
                pair.tokenType());
    }
}
