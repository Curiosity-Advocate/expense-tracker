package com.finance.controller;

import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.dto.RegisterRequest;
import com.finance.dto.RegisterResponse;
import com.finance.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// The controller's only job is HTTP translation:
// JSON in → command out, domain object in → JSON out.
// No business logic lives here. No BCrypt. No repository calls.
// If you find yourself writing an if-statement here, it belongs in the service.
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        RegisterCommand command = new RegisterCommand(
                request.username(),
                request.email(),
                request.password()
        );

        RegisteredUser result = authService.register(command);

        RegisterResponse response = new RegisterResponse(
                result.userId(),
                result.username(),
                result.email(),
                result.createdAt()
        );

        // Wrapped in "data" envelope per api_design.md contract
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", response));
    }
}