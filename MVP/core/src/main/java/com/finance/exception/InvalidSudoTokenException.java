package com.finance.exception;

// Thrown by SudoTokenService.verify() when the presented sudo token cannot
// be used for delegation. Covers all failure modes uniformly:
//   - token hash not found
//   - token expired
//   - underlying grant has since been revoked or expired
//   - presented granteeId doesn't match the looked-up token
//
// Single generic error — D3's gateway filter rejects the request without
// disclosing why.
public class InvalidSudoTokenException extends RuntimeException {
    public InvalidSudoTokenException() {
        super("Invalid sudo token");
    }
}
