package com.finance.domain;

import java.util.UUID;

// Returned by SudoTokenService.verify(). Used by D3's gateway filter to
// know which user ids to set on the session variables for the request:
// app.current_user_id ← grantorId (drives RLS, scoping to A's data)
// app.acting_user_id  ← granteeId (drives audit via S5, recording the
//                                   actual actor for created_by / modified_by)
public record SudoTokenVerification(
        UUID grantId,
        UUID grantorId,
        UUID granteeId) {}
