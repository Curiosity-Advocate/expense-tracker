package com.finance.security;

import com.finance.domain.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Second layer of RLS enforcement (the DB policy is the third).
// Runs AFTER @EnableTransactionManagement (order = 1) opens the transaction,
// and BEFORE the repository method executes — that ordering is why ApiApplication
// sets @EnableTransactionManagement(order = 1) and this aspect is @Order(2).
//
// SET LOCAL scopes app.current_user_id to the current transaction only.
// HikariCP returns the connection to the pool when the transaction ends and
// the variable is automatically cleared — no risk of one user's ID leaking
// into the next request's connection.
@Aspect
@Component
@Order(2)
public class RlsSessionAspect {

    @PersistenceContext
    private EntityManager entityManager;

    // Matches @Transactional declared on the method directly (@annotation) OR on
    // the enclosing class (@within) — Spring's class-level @Transactional applies
    // to all methods but AspectJ's @annotation only sees method-level annotations.
    @Before("@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    public void setRlsContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserPrincipal principal) {

            entityManager
                    .createNativeQuery("SELECT set_config('app.current_user_id', :uid, true)")
                    .setParameter("uid", principal.userId().toString())
                    .getSingleResult();
        }
    }
}
