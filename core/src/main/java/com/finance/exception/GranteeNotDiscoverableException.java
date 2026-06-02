package com.finance.exception;

// Thrown by AccessGrantService.create() when the grantee username either
// doesn't exist OR refers to a user with is_discoverable = FALSE. The two
// cases are indistinguishable from the caller's perspective to prevent
// username enumeration via probing grant creation.
public class GranteeNotDiscoverableException extends RuntimeException {
    public GranteeNotDiscoverableException() {
        super("Grantee is not available for delegation");
    }
}
