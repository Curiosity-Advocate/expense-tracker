package com.finance.exception;

// Thrown by AccessGrantService.revoke() when the requested grant id either
// doesn't exist or is invisible to the current user (RLS-filtered because
// they aren't the grantor or grantee). The two cases are deliberately
// indistinguishable from the caller's perspective — same enumeration-defence
// rationale as InvalidCredentialsException.
public class GrantNotFoundException extends RuntimeException {
    public GrantNotFoundException() {
        super("Access grant not found");
    }
}
