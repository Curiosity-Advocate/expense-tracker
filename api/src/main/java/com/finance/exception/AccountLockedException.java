package com.finance.exception;

import java.time.Instant;

// Thrown when a user has exceeded the failed login threshold.
// lockedUntil is included so the response can tell the client exactly when to retry.
// This avoids the client hammering the endpoint repeatedly during the lockout window.
public class AccountLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("Account temporarily locked due to too many failed login attempts");
        this.lockedUntil = lockedUntil;
    }

    public Instant getLockedUntil() { return lockedUntil; }
}