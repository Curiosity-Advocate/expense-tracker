# C4 Architecture Diagrams

> **Context:** Expands [overview.md §4](../overview.md#4-the-shape-of-the-system). For dependency rules and Gradle module structure see [module-boundaries.md](module-boundaries.md). For schema, see [data-model.md](data-model.md). Implements: N1, N2.

Three levels of zoom — System Context, Container, and Component. Each level answers a different question. Read top-down: the higher levels frame the lower ones.

---

## Level 1 — System Context

**Question:** Who uses the system and what does it depend on?

```mermaid
flowchart TB
    user(["👤 User<br/><sub>Personal or demo user</sub>"])

    et["<b>Expense Tracker</b><br/><sub>Tracks expenses, targets, and predictions</sub>"]

    render[("Render<br/><sub>Cloud hosting</sub>")]

    user ==>|"HTTPS / JSON"| et
    et -.->|"Hosted on"| render

    classDef person fill:#08427b,stroke:#052e56,color:#fff,stroke-width:2px
    classDef system fill:#1168bd,stroke:#0a4f8e,color:#fff,stroke-width:2px
    classDef external fill:#999999,stroke:#666666,color:#fff,stroke-width:2px

    class user person
    class et system
    class render external
```

One user actor, one system, one hosting platform. No external integrations in v1.0 — Basiq (bank data, including its hosted consent UI for per-user enrolment) appears at this level only when v2.0 brings it in. The app-level Basiq API key sits in env config; v3.0 may migrate it to Bitwarden Secrets Manager (ADR-0019).

---

## Level 2 — Container

**Question:** What deployable processes make up the system, and how do they communicate?

```mermaid
flowchart TB
    user(["👤 User"])

    subgraph system[" Expense Tracker "]
        direction TB
        api["<b>API Process</b><br/><sub>Spring Boot · Java 21</sub><br/><sub>HTTP, business logic, RLS</sub>"]
        worker["<b>Worker Process</b><br/><sub>Spring Boot · Java 21</sub><br/><sub>Scheduled housekeeping</sub>"]
        db[("<b>PostgreSQL 16</b><br/><sub>All data · RLS enforced</sub>")]
    end

    user ==>|"HTTPS / JSON"| api
    api ==>|"JDBC"| db
    worker ==>|"JDBC"| db

    classDef person fill:#08427b,stroke:#052e56,color:#fff,stroke-width:2px
    classDef container fill:#438dd5,stroke:#2e6da4,color:#fff,stroke-width:2px
    classDef database fill:#438dd5,stroke:#2e6da4,color:#fff,stroke-width:2px
    classDef boundary fill:none,stroke:#999,stroke-width:1px,stroke-dasharray:5 5

    class user person
    class api,worker container
    class db database
    class system boundary
```

Three containers. API and Worker communicate exclusively through the database — no HTTP between them.

---

## Level 3 — API Process Components

**Question:** What lives inside the API process and how do the pieces fit together?

```mermaid
flowchart LR
    user(["👤 User"])
    db[("PostgreSQL")]

    subgraph api[" API Process "]
        direction TB
        security["<b>Security</b><br/><sub>JWT · TraceId · Sudo token · Grant</sub>"]
        controllers["<b>Controllers</b><br/><sub>HTTP translation</sub>"]
        aspects["<b>Aspects</b><br/><sub>RLS · MV refresh</sub>"]

        subgraph core[" Core (no infra deps) "]
            direction TB
            ports["<b>Service Interfaces</b><br/><sub>AuthService, ExpenseService, ...</sub>"]
            engines["<b>Prediction Strategies</b><br/><sub>NaiveDailyRateStrategy</sub>"]
            domain["<b>Domain</b><br/><sub>Commands, queries, value objects, exceptions</sub>"]
        end

        subgraph adapters[" Adapters "]
            services["<b>Service Impls</b><br/><sub>PostgresExpenseService, ...</sub>"]
            repos["<b>Repositories + Entities</b><br/><sub>Spring Data JPA</sub>"]
        end

        security --> controllers
        controllers --> ports
        services -.->|"implements"| ports
        services --> repos
        services --> engines
        aspects -.->|"intercepts"| repos
    end

    user ==>|"HTTPS"| security
    repos ==>|"JDBC"| db

    classDef person fill:#08427b,stroke:#052e56,color:#fff,stroke-width:2px
    classDef component fill:#85bbf0,stroke:#5a9bd4,color:#000,stroke-width:1px
    classDef database fill:#438dd5,stroke:#2e6da4,color:#fff,stroke-width:2px
    classDef boundary fill:none,stroke:#999,stroke-width:1px,stroke-dasharray:5 5
    classDef corebox fill:#fafafa,stroke:#666,stroke-width:1px,stroke-dasharray:3 3

    class user person
    class security,controllers,aspects,services,engines,ports,domain,repos component
    class db database
    class api,adapters boundary
    class core corebox
```

**Key things visible.** Security is the entry point — every request passes through it. Controllers depend on service *interfaces* in core; the concrete implementations and the JPA repositories live in adapters. Aspects sit outside the main flow and intercept repositories: one for RLS session injection, one for materialised view refresh (planned). Core has no infrastructure dependencies — ArchUnit tests enforce this at build time.

---

## Level 3 — Worker Process Components

**Question:** What lives inside the Worker process and how does it differ from the API?

```mermaid
flowchart LR
    db[("PostgreSQL")]

    subgraph worker[" Worker Process "]
        direction TB
        scheduler["<b>Scheduler</b><br/><sub>@Scheduled cron</sub>"]
        jobs["<b>Cron Jobs</b><br/><sub>Cleanup · Partition · MV refresh</sub>"]
        alerter["<b>JobFailureAlerter</b><br/><sub>Retry, persist state, email on failure</sub>"]
        jdbc["<b>JdbcTemplate</b><br/><sub>Raw SQL — superuser role</sub>"]
    end

    scheduler --> jobs
    jobs -.->|"wrapped by"| alerter
    jobs --> jdbc
    alerter --> jdbc
    jdbc ==>|"JDBC"| db

    classDef component fill:#85bbf0,stroke:#5a9bd4,color:#000,stroke-width:1px
    classDef database fill:#438dd5,stroke:#2e6da4,color:#fff,stroke-width:2px
    classDef boundary fill:none,stroke:#999,stroke-width:1px,stroke-dasharray:5 5

    class scheduler,jobs,alerter,jdbc component
    class db database
    class worker boundary
```

**Key things visible.** The Worker is headless — no HTTP, no security filter, no JPA. Cron jobs hold their own SQL and talk directly to PostgreSQL via `JdbcTemplate` using superuser credentials (which bypass RLS — see [module-boundaries.md](module-boundaries.md)). Every job is wrapped by `JobFailureAlerter`, which retries on failure, persists state in `job_execution_state`, and emails a configured recipient when all attempts fail. The Worker depends only on `core`; it does *not* link the JPA adapter implementations.
