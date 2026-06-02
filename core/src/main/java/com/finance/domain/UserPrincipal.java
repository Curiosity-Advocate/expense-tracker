package com.finance.domain;

import java.util.UUID;

// Stored in Spring's SecurityContext after JWT validation. Passed via
// @AuthenticationPrincipal in controllers — avoids hitting the database
// on every request just to find out who the caller is.
//
// Three fields:
//   userId    — the user whose data the request operates on. Drives RLS
//                via app.current_user_id. For a normal request this is
//                the JWT subject; for a delegated request (D3) this is
//                the *grantor* (the data owner).
//   username  — display name from the JWT. Refers to the JWT subject
//                regardless of delegation, so does NOT always match userId.
//                Rarely used in service code; primarily for token issuance
//                and UI hints.
//   actingAs  — null in non-delegated requests. When delegation is active
//                (D3 set it from a sudo-token verification), this carries
//                the *grantee* / actor id. RlsSessionAspect propagates it
//                to app.acting_user_id so the S5 audit triggers record
//                the actor in created_by / modified_by while RLS continues
//                to scope to the grantor.
public record UserPrincipal(UUID userId, String username, UUID actingAs) {

    // Convenience factory for non-delegated callers (JwtAuthenticationFilter,
    // tests). The actingAs slot defaults to null.
    public static UserPrincipal of(UUID userId, String username) {
        return new UserPrincipal(userId, username, null);
    }

    public boolean isDelegated() {
        return actingAs != null;
    }
}
