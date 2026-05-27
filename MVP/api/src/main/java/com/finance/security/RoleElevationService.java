package com.finance.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

// Temporarily switches the current transaction's DB role to expense_setup
// (a NOLOGIN BYPASSRLS role created in V17). Used by pre-authentication
// operations — register, login, setupNewUser — that need to access RLS-protected
// tables before a user context exists. SET LOCAL scopes the role change to the
// current transaction; it auto-reverts at COMMIT/ROLLBACK so connection pool
// reuse cannot leak the elevation across requests. See ADR-0011.
@Service
public class RoleElevationService {

    @PersistenceContext
    private EntityManager entityManager;

    public void elevateToSetupRole() {
        entityManager.createNativeQuery("SET LOCAL ROLE expense_setup").executeUpdate();
    }
}
