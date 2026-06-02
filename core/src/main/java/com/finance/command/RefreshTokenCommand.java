package com.finance.command;

// Input to AuthService.refresh(). The presented refresh token is the raw
// (unhashed) value the client received at login or at the previous refresh.
// The service hashes it before looking up refresh_tokens.token_hash.
public record RefreshTokenCommand(String refreshToken) {}
