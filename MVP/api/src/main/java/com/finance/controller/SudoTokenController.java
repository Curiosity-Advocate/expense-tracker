package com.finance.controller;

import com.finance.command.CreateSudoTokenCommand;
import com.finance.domain.SudoToken;
import com.finance.domain.UserPrincipal;
import com.finance.dto.CreateSudoTokenRequest;
import com.finance.dto.SudoTokenResponse;
import com.finance.service.SudoTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Step-up authentication. Endpoint requires Bearer auth (configured in
// SecurityConfig with a more-specific rule than the auth/** permitAll)
// PLUS password re-entry in the body — proves the JWT holder is the
// actual user, not an attacker who's just stolen the access token.
@RestController
@RequestMapping("/api/v1/auth/sudo-tokens")
@Tag(name = "Auth")
public class SudoTokenController {

    private final SudoTokenService sudoTokenService;

    public SudoTokenController(SudoTokenService sudoTokenService) {
        this.sudoTokenService = sudoTokenService;
    }

    @PostMapping
    @Operation(summary = "Mint a step-up sudo token to exercise a delegation grant")
    public ResponseEntity<SudoTokenResponse> create(@Valid @RequestBody CreateSudoTokenRequest req,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        SudoToken token = sudoTokenService.create(
                new CreateSudoTokenCommand(principal.userId(), req.grantId(), req.password()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SudoTokenResponse(token.rawToken(), token.expiresAt()));
    }
}
