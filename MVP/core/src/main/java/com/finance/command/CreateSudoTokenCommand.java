package com.finance.command;

import java.util.UUID;

// Input to SudoTokenService.create(). granteeId comes from the authenticated
// UserPrincipal; grantId and password from the request body. Password is the
// step-up authentication — proves the JWT holder is the actual user, not an
// attacker who's just stolen the token.
public record CreateSudoTokenCommand(
        UUID granteeId,
        UUID grantId,
        String password) {}
