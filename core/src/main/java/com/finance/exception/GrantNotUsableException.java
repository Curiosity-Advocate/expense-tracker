package com.finance.exception;

// Thrown by SudoTokenService.create() when the referenced grant cannot be
// used to mint a sudo token. Covers four conditions, deliberately unified
// to prevent enumeration via probing:
//   - grant does not exist
//   - grant exists but the current user is not its grantee
//   - grant is revoked
//   - grant is expired
//
// The caller cannot distinguish between these; the response is always a
// single GRANT_NOT_USABLE error code.
public class GrantNotUsableException extends RuntimeException {
    public GrantNotUsableException() {
        super("Grant is not usable");
    }
}
