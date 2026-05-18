package com.finance.controller;

import com.finance.service.AuthService;
import com.finance.service.UserSetupService;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;
import com.finance.dto.LoginRequest;
import com.finance.dto.LoginResponse;
import com.finance.dto.RegisterRequest;
import com.finance.dto.RegisterResponse;
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
                new com.finance.command.RegisterCommand(req.username(), req.email(), req.password()));
        userSetupService.setupNewUser(user.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(user.userId(), user.username(), user.email(), user.createdAt()));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive a JWT")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        TokenPair token = authService.login(
                new com.finance.command.LoginCommand(req.username(), req.password()));
        return ResponseEntity.ok(new LoginResponse(token.accessToken(), token.expiresAt(), token.tokenType()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current JWT")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        return ResponseEntity.noContent().build();
    }
}
