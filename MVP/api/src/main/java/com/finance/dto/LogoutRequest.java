package com.finance.dto;

import jakarta.validation.constraints.NotBlank;

// Logout takes the refresh token in the body (RFC 7009 pattern adapted to JSON).
// The Authorization header still authenticates the request via the access token;
// the body specifies which session to end. The access token itself is never
// revoked — it expires naturally within the access-token window.
public record LogoutRequest(@NotBlank String refreshToken) {}
