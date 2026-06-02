package com.finance.controller;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.domain.AccessGrant;
import com.finance.domain.UserPrincipal;
import com.finance.dto.AccessGrantResponse;
import com.finance.dto.CreateAccessGrantRequest;
import com.finance.service.AccessGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/access-grants")
@Tag(name = "Access Grants")
public class AccessGrantController {

    private final AccessGrantService accessGrantService;

    public AccessGrantController(AccessGrantService accessGrantService) {
        this.accessGrantService = accessGrantService;
    }

    @PostMapping
    @Operation(summary = "Grant another discoverable user temporary access to your data")
    public ResponseEntity<AccessGrantResponse> create(@Valid @RequestBody CreateAccessGrantRequest req,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        AccessGrant grant = accessGrantService.create(new CreateAccessGrantCommand(
                principal.userId(),
                req.granteeUsername(),
                req.accessLevel(),
                req.expiresInDays()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(grant));
    }

    @GetMapping
    @Operation(summary = "List grants where you are either the grantor or the grantee")
    public ResponseEntity<List<AccessGrantResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(accessGrantService.listForUser(principal.userId()).stream()
                .map(AccessGrantController::toResponse)
                .toList());
    }

    @DeleteMapping("/{grantId}")
    @Operation(summary = "Revoke a grant (allowed for both grantor and grantee)")
    public ResponseEntity<Void> revoke(@PathVariable UUID grantId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        accessGrantService.revoke(grantId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    private static AccessGrantResponse toResponse(AccessGrant g) {
        return new AccessGrantResponse(
                g.id(),
                g.grantorId(), g.grantorUsername(),
                g.granteeId(), g.granteeUsername(),
                g.accessLevel(),
                g.expiresAt(),
                g.revokedAt());
    }
}
