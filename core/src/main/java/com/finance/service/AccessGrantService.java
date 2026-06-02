package com.finance.service;

import com.finance.command.CreateAccessGrantCommand;
import com.finance.domain.AccessGrant;

import java.util.List;
import java.util.UUID;

public interface AccessGrantService {

    // Creates a grant where the current user is the grantor. Grantee resolved
    // by username; must exist AND be is_discoverable = TRUE (else
    // GranteeNotDiscoverableException). Self-grants are rejected with
    // SelfGrantNotAllowedException before reaching the DB CHECK.
    AccessGrant create(CreateAccessGrantCommand command);

    // Returns every grant the user is party to — both grants given (grantor)
    // and grants received (grantee). RLS dual-clause does the filtering at
    // the DB level; this method just executes the SELECT.
    List<AccessGrant> listForUser(UUID userId);

    // Soft-revokes (sets revoked_at = NOW). Allowed for either the grantor
    // or the grantee. Throws GrantNotFoundException if the grant doesn't
    // exist OR if the user isn't party to it (the two cases are indistinguishable
    // through RLS — the row is invisible to a non-party caller).
    void revoke(UUID grantId, UUID requestingUserId);
}
