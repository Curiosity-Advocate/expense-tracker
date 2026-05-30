package com.finance.command;

import java.util.UUID;

// Input to AccessGrantService.create(). grantorId is supplied by the
// controller from the current UserPrincipal; granteeUsername / accessLevel /
// expiresInDays come from the request body. Keeping grantorId in the command
// (rather than reading SecurityContext inside the service) keeps service
// methods as pure functions of their input for testability.
public record CreateAccessGrantCommand(
        UUID grantorId,
        String granteeUsername,
        String accessLevel,
        int expiresInDays) {}
