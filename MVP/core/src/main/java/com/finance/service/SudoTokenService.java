package com.finance.service;

import com.finance.command.CreateSudoTokenCommand;
import com.finance.domain.SudoToken;
import com.finance.domain.SudoTokenVerification;

import java.util.UUID;

public interface SudoTokenService {

    // Mints a sudo token for a specific grant. The granteeId in the command
    // must be the authenticated user; the grant must exist, be unrevoked,
    // unexpired, and have this user as its grantee. Password re-entry is
    // the step-up — proves the JWT holder owns the underlying credential.
    //
    // Throws InvalidCredentialsException if the password is wrong.
    // Throws GrantNotUsableException for any grant-side failure (unknown,
    // not the user's, revoked, or expired) — all unified to prevent
    // enumeration of grant state via probing.
    SudoToken create(CreateSudoTokenCommand command);

    // Used by D3's gateway filter. Hashes the presented raw token, looks
    // it up, JOINs access_grants to confirm the underlying grant is still
    // usable. Returns the grantor / grantee ids the filter needs to set
    // on the session variables for the request.
    //
    // Throws InvalidSudoTokenException for any failure (unknown token,
    // expired token, grant revoked or expired since issuance, granteeId
    // mismatch with the looked-up token).
    SudoTokenVerification verify(String rawToken, UUID granteeId);
}
