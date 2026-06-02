package com.finance.exception;

// Thrown by AccessGrantService.create() when grantor and grantee are the
// same user. Service-layer pre-check; the DB CHECK constraint
// chk_no_self_grant is the backstop, not the primary error path.
public class SelfGrantNotAllowedException extends RuntimeException {
    public SelfGrantNotAllowedException() {
        super("Cannot create an access grant to yourself");
    }
}
