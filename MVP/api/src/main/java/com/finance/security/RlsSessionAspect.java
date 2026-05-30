package com.finance.security;

import com.finance.config.DataSourceConfig;
import com.finance.domain.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

// Second layer of RLS enforcement (the DB policy is the third).
// Runs AFTER @EnableTransactionManagement (order = 1) opens the transaction,
// and BEFORE the repository method executes — that ordering is why ApiApplication
// sets @EnableTransactionManagement(order = 1) and this aspect is @Order(2).
//
// SET LOCAL scopes app.current_user_id to the current transaction only.
// HikariCP returns the connection to the pool when the transaction ends and
// the variable is automatically cleared — no risk of one user's ID leaking
// into the next request's connection.
//
// Setup-pool methods (@Transactional("setupTransactionManager")) are explicitly
// skipped — they connect as expense_setup which has BYPASSRLS, so the session
// variable is irrelevant, and the aspect's EntityManager is bound to the app
// pool anyway. Running it would do work on the wrong connection. See ADR-0011.
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
    public void setRlsContext(JoinPoint joinPoint) {
        if (isOnSetupTransactionManager(joinPoint)) {
            return;
        }

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

    // Reads @Transactional from the method first, then falls back to the declaring
    // class. Returns true when value() targets the setup TX manager — in which
    // case the aspect should skip its principal injection entirely.
    private boolean isOnSetupTransactionManager(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();

        Transactional tx = method.getAnnotation(Transactional.class);
        if (tx == null) {
            tx = method.getDeclaringClass().getAnnotation(Transactional.class);
        }

        return tx != null && DataSourceConfig.SETUP_TX_MANAGER.equals(tx.value());
    }
}
