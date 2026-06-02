package com.finance.exception;

// Thrown by AuthService.refresh() and logout() when the presented refresh
// token is unknown (never issued) or expired beyond the session_started_at
// + refresh-token-expiry-days window.
//
// Same generic message as InvalidCredentialsException — never distinguish
// "no such token" from "expired token" to a caller; both should look identical
// from the outside.
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
