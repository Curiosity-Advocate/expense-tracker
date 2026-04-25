package com.finance.security;

import com.finance.domain.UserPrincipal;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

// Hibernate calls inspect() before every SQL statement it executes.
// We use it to inject the SET LOCAL command that tells PostgreSQL
// which user's rows are visible for this transaction.
//
// SET LOCAL scopes the variable to the current transaction only.
// When the transaction ends the variable resets automatically.
// This is critical for connection pool safety — a reused connection
// never carries a previous user's ID into a new request.
@Component
public class RlsInterceptor implements StatementInspector {

    @Override
    public String inspect(String sql) {
        Authentication auth = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserPrincipal principal) {

            // Prepend SET LOCAL before the actual SQL.
            // Hibernate receives this as one combined string and
            // PostgreSQL executes the SET before the query.
            return "SET LOCAL app.current_user_id = '"
                    + principal.userId()
                    + "'; "
                    + sql;
        }

        // No authenticated user — public endpoints, Flyway migrations,
        // system jobs. Let the query through without setting the variable.
        // RLS will return empty results for any tenant-scoped table,
        // which is the correct behaviour for an unauthenticated context.
        return sql;
    }
}