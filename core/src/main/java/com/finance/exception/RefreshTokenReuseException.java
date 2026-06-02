package com.finance.exception;

// Thrown by AuthService.refresh() when a refresh token that has already been
// rotated is presented again — a strong signal that either the legitimate
// client and an attacker both hold the same token, or that the client is
// replaying the wrong cached value.
//
// The service has already revoked the entire chain (every active refresh
// token for the user) as a side effect of throwing this. Callers should
// treat it as forced re-authentication: the user must log in again.
public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException() {
        super("Refresh token reuse detected — session terminated");
    }
}
