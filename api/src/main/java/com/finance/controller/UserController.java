package com.finance.controller;

import com.finance.service.UserService;
import com.finance.domain.UserPrincipal;
import com.finance.domain.UserProfile;
import com.finance.dto.UpdateUserRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile")
    public ResponseEntity<UserProfile> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userService.getProfile(principal.userId()));
    }

    @PatchMapping("/me")
    @Operation(summary = "Update profile settings (discoverability)")
    public ResponseEntity<UserProfile> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateUserRequest req) {
        UserProfile updated = userService.updateDiscoverability(principal.userId(), req.isDiscoverable());
        return ResponseEntity.ok(updated);
    }
}
