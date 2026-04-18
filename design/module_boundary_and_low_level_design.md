## Module Boundaries and Low-Level Design

Let me define this in two passes. First, the module boundary rules — what each module owns, exposes,
and is forbidden from touching. Second, the low-level design of the non-trivial patterns inside each
module.

A boundary violation is when Module A reaches into Module B's internals directly — querying B's
tables, instantiating B's classes, or bypassing B's interface. The goal is that you can rewrite the
internals of any module without touching any other module, as long as the interface contract stays
the same.

---

## Module Boundary Rules

### The Dependency Rule

Before listing modules, one overarching rule governs all of them:

**Dependencies only point inward — toward the domain, never outward toward infrastructure.**

```
[HTTP / Basiq / LLM / DB]   ← infrastructure layer
         ↓
   [Service interfaces]      ← module boundary layer
         ↓
   [Domain logic]            ← core, no external dependencies
```

Domain logic (prediction algorithms, duplicate detection heuristics, target calculations) must never
import a Spring annotation, a JDBC class, or an HTTP client. If you find yourself importing
`org.springframework` inside a pure calculation class, that's a boundary violation.

---

### Module 1 — Auth Module

**Owns:**

- User credential storage (`users` table:
  `id, username, email, password_hash, email_verified, is_discoverable, created_at`)
- Token issuance and revocation (`revoked_tokens` table)
- Sudo token issuance and validation
- Login attempt tracking and lockout logic
- Session lifecycle

**Exposes (public interface):**

```java
public interface AuthService {
    UserPrincipal register(RegisterCommand command);
    TokenPair login(LoginCommand command);
    void logout(UUID tokenId);
    SudoToken issueSudoToken(UUID userId, String password);
    boolean validateSudoToken(UUID userId, String sudoToken);
    UserPrincipal validateAccessToken(String token);
}
```

**Forbidden from:**

- Knowing anything about expenses, targets, or bank accounts
- Calling any other module's service interface
- Querying any table it does not own

**Key rule:** Every other module receives a `UserPrincipal` object (containing `userId` and `roles`)
injected by the API gateway filter. No module ever calls `AuthService` to look up a user during a
request — that was already done at the gateway layer before the request reached the module.

---

### Module 2 — User Management Module

**Owns:**

- User profile data (`user_profiles` table: `user_id, display_name, preferences_json`)
- Access grant records (`access_grants` table:
  `id, grantor_user_id, grantee_user_id, access_level, expires_at, revoked_at`)
- Discoverability flag (lives on `users` table — shared ownership with Auth, accessed via Auth's
  interface, never queried directly)

**Exposes:**

```java
public interface UserService {
    UserProfile getProfile(UUID userId);
    UserProfile updateProfile(UUID userId, UpdateProfileCommand command);
    List<AccessGrant> createGrants(UUID grantorId, List<CreateGrantCommand> commands);
    void revokeGrant(UUID grantorId, UUID grantId);
    Optional<AccessGrant> findActiveGrant(UUID grantorId, UUID granteeId);
    boolean isDiscoverable(UUID userId);
}
```

**Forbidden from:**

- Querying expense or target data
- Calling `BankIntegrationService` or `AiCategorizationService`
- Knowing about Basiq or any external API

**Key rule:** `findActiveGrant` is the method the Expense Module calls before serving cross-user
data. The Expense Module never queries the `access_grants` table directly.

---

### Module 3 — Expense Module

**Owns:**

- Expense records (`expenses` table)
- Category junction (`expense_categories` table)
- Category weights (`expense_category_weights` table — computed on write, stored for query
  performance)
- Materialized views for summaries
- Soft delete logic
- Idempotency key tracking (`expense_idempotency_keys` table)

**Exposes:**

```java
public interface ExpenseService {
    Expense createExpense(UUID userId, CreateExpenseCommand command);
    Expense getExpense(UUID userId, UUID expenseId);
    Page<Expense> queryExpenses(UUID userId, ExpenseQuery query);
    ExpenseSummary getSummary(UUID userId, SummaryQuery query);
    Expense updateExpense(UUID userId, UUID expenseId, UpdateExpenseCommand command);
    void softDeleteExpense(UUID userId, UUID expenseId);

    // Called by Bank Integration worker via service interface only
    Expense importBankTransaction(UUID userId, BankTransactionCommand command);

    // Called by AI worker via service interface only
    void applyCategorizationSuggestion(UUID expenseId, CategorizationResult result);
    List<Expense> findUncategorised(UUID userId);
}
```

**Forbidden from:**

- Calling `BankIntegrationService` or `AiCategorizationService`
- Knowing about Basiq API response shapes
- Calling `TargetService` — the target module reads from expense data, not the reverse
- Bypassing RLS — every query must include `userId` as a filter parameter, enforced at the
  repository layer

**Key rule — category weight computation:** The even-split weight calculation happens inside
`createExpense` in this module, never in the caller. The caller sends `["GROCERIES", "HOUSEHOLD"]`.
The module computes `{GROCERIES: 21.25, HOUSEHOLD: 21.25}` and persists both. No other module ever
recomputes this.

---

### Module 4 — Target and Prediction Module

**Owns:**

- Target records (`expense_targets` table)
- Prediction strategy registry and execution
- No tables for prediction results — predictions are computed on-demand, never persisted (they'd go
  stale immediately)

**Exposes:**

```java
public interface TargetService {
    ExpenseTarget createTarget(UUID userId, CreateTargetCommand command);
    List<ExpenseTarget> getTargets(UUID userId, TargetQuery query);
    void deleteTarget(UUID userId, UUID targetId);
    TargetStatus getTargetStatus(UUID userId, UUID targetId);
}
```

**Internal — not exposed across module boundary:**

```java
// Lives entirely inside this module
public interface PredictionStrategy {
    PredictionResult predict(PredictionContext context);
    String strategyName();
    String strategyVersion();
    boolean canHandle(PredictionContext context);
}
```

**Forbidden from:**

- Querying the `expenses` table directly
- Knowing about materialized view names or SQL
- Calling any other module except `ExpenseService` (to fetch summary data for prediction context)

**Key rule:** `TargetService.getTargetStatus` calls `ExpenseService.getSummary` to build its
`PredictionContext`. It never touches expense data directly. The dependency is one-way: Target
depends on Expense, Expense never depends on Target.

---

### Module 5 — Bank Integration Module

**Owns:**

- Raw ingestion store (`raw_bank_transactions` table — append-only, hash-chained)
- Bank account registry (`bank_accounts` table)
- Duplicate detection records (`duplicate_flags` table)
- Basiq OAuth token references (token values stored in Bitwarden, only token IDs stored locally)
- Job queue entries for sync jobs (`job_queue` table — shared with other async modules but owned
  operationally by this module for BANK_SYNC type jobs)
- Partition registry (`partition_registry` table)

**Exposes:**

```java
public interface BankIntegrationService {
    BankSyncJob triggerSync(UUID userId, List<UUID> bankAccountIds);
    BankSyncJob getSyncStatus(UUID jobId);
    List<BankAccount> getConnectedAccounts(UUID userId);
    ConnectAccountResult initiateAccountConnection(UUID userId);
    BankAccount completeAccountConnection(UUID userId, String authCode, String state);
    void disconnectAccount(UUID userId, UUID bankAccountId);
    List<DuplicateFlag> getPendingDuplicates(UUID userId);
    ResolvedExpense resolveDuplicate(UUID userId, ResolveDuplicateCommand command);
}
```

**Forbidden from:**

- Calling `AiCategorizationService`
- Querying `expense_targets` or any Target module table
- Writing directly to the `expenses` table — it calls `ExpenseService.importBankTransaction` through
  the service interface

**Key rule — raw ingestion is always first:** The worker must write to `raw_bank_transactions` in
one transaction before calling `ExpenseService.importBankTransaction`. If `importBankTransaction`
fails, the raw record is already safe. This enforces the Outbox pattern at the module level.

---

### Module 6 — AI Categorisation Module

**Owns:**

- Suggestion records (`ai_suggestions` table:
  `id, expense_id, suggested_categories, confidence, strategy_name, strategy_version, status, created_at`)
- LLM API client configuration
- Prompt templates

**Exposes:**

```java
public interface AiCategorizationService {
    List<AiSuggestion> getPendingSuggestions(UUID userId);
    ResolvedExpense resolveSuggestion(UUID userId, UUID suggestionId,
                                      ResolveSuggestionCommand command);
}
```

**Forbidden from:**

- Calling `BankIntegrationService`
- Calling `TargetService`
- Writing directly to the `expenses` table — always goes through
  `ExpenseService.applyCategorizationSuggestion`

**Key rule — suggestion as optimistic lock:** The `original` field in the resolve command is
validated against the expense's current categories before applying. If they don't match,
`409 Conflict` is returned. This check lives inside `ExpenseService.applyCategorizationSuggestion`,
not in the AI module. The AI module passes the full command through; the Expense module owns the
conflict detection.

---

### Module 7 — Archival Module

**Owns:**

- Partition management logic
- Partition registry (`partition_registry` table)
- Scheduled job execution (December partition creation, January archival)

**Exposes:**

```java
public interface ArchivalService {
    List<PartitionInfo> getPartitionRegistry();
    void runArchivalCycle(); // normally scheduled, but exposable for manual trigger
}
```

**Forbidden from:**

- Calling any business module (Expense, Target, Bank, AI)
- Modifying expense records — it only reclassifies partition metadata
- Running outside its scheduled window without explicit invocation

**Key rule:** The archival module never reads expense data. It only manages partition metadata in
the registry table. The partition boundary decision is made by `LocalDate.now().getYear() - 5` — no
hardcoded years anywhere.

---

### Module 8 — Observability and Dead Letter Module

**Owns:**

- Dead letter records (`dead_letter_jobs` table)
- Structured log event publishing
- Retry orchestration for dead letter jobs

**Exposes:**

```java
public interface ObservabilityService {
    void recordDeadLetter(DeadLetterCommand command);
    List<DeadLetterJob> getDeadLetters(UUID userId);
    BankSyncJob retryDeadLetter(UUID userId, UUID deadLetterId);
    void logStructuredEvent(LogEvent event);
}
```

**Forbidden from:**

- Calling any business module directly
- Knowing about expense schema or bank account details beyond what's in the job payload

**Key rule — every module writes to this one, nothing reads from it except the API:** When any
worker exhausts its retry cycles, it calls `ObservabilityService.recordDeadLetter`. This is the only
direction of dependency. The Observability module never calls back into the module that generated
the failure.

---

## Module Dependency Map

```
                    [API Gateway Filter]
                           │
          ┌────────────────┼─────────────────┐
          │                │                 │
     [AuthModule]   [UserModule]      [ExpenseModule]
                           │                 │
                    checks grants      ◄─────┤
                                      [TargetModule]

[BankIntegration] ──calls──► [ExpenseService interface]
[AiCategorization] ─calls──► [ExpenseService interface]

[ArchivalModule] ──► [PartitionRegistry only]

[All modules] ──► [ObservabilityService interface]
```

The critical observation: **ExpenseService is the most depended-upon interface in the system.** Bank
Integration and AI Categorisation both call it. This makes it the most important contract to get
right and the most important one to keep stable.

---

## Low-Level Design — Non-Trivial Patterns

### 1. Hash Chaining in Raw Ingestion

Each row in `raw_bank_transactions` contains a hash of its own content combined with the previous
row's hash. Tampering with any row breaks the chain from that point forward — detectable by a
verification scan.

```java
public class RawBankTransaction {
    private UUID id;
    private UUID userId;
    private String externalTransactionId;
    private JsonNode rawPayload;           // Basiq response verbatim
    private Instant ingestedAt;
    private String contentHash;            // SHA-256 of this row's content
    private String chainHash;              // SHA-256(contentHash + previousChainHash)
    private UUID previousRecordId;         // null for first record per user
}
```

The chain hash computation:

```java
private String computeChainHash(RawBankTransaction current,
                                  String previousChainHash) {
    String content = current.getExternalTransactionId()
        + current.getRawPayload().toString()
        + current.getIngestedAt().toString()
        + current.getUserId().toString();

    String contentHash = sha256(content);
    current.setContentHash(contentHash);

    String chainInput = contentHash + (previousChainHash != null
                                       ? previousChainHash : "GENESIS");
    return sha256(chainInput);
}
```

**Verification scan** (run on demand or scheduled):

```java
public ChainVerificationResult verifyChain(UUID userId) {
    List<RawBankTransaction> records =
        rawIngestionRepository.findByUserIdOrderByIngestedAt(userId);

    String expectedPreviousHash = "GENESIS";
    for (RawBankTransaction record : records) {
        String recomputed = computeChainHash(record, expectedPreviousHash);
        if (!recomputed.equals(record.getChainHash())) {
            return ChainVerificationResult.tamperDetected(record.getId());
        }
        expectedPreviousHash = record.getChainHash();
    }
    return ChainVerificationResult.intact();
}
```

**Important constraint:** inserts into `raw_bank_transactions` must be **serialised per user** — two
concurrent sync jobs for the same user could both read the same "latest chain hash" and produce
conflicting chain links. This is enforced by the `SELECT FOR UPDATE` on the latest record before
insert, or by the 409 Conflict check that prevents concurrent syncs per user (which you already
have).

---

### 2. Row Level Security — Enforcement Pattern

RLS is defined once at the database layer and is invisible to application code. Here's the full
setup:

```sql
-- Enable RLS on all tenant-scoped tables
ALTER TABLE expenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_targets ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_accounts ENABLE ROW LEVEL SECURITY;

-- Policy: users see only their own rows
CREATE POLICY expense_isolation ON expenses
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- The application sets this at the start of every DB session
SET app.current_user_id = '<authenticated-user-uuid>';
```

In Spring Boot, this is set via a Hibernate interceptor that fires before every query:

```java
@Component
public class RlsInterceptor implements StatementInspector {

    private final UserContextHolder contextHolder;

    @Override
    public String inspect(String sql) {
        UUID userId = contextHolder.getCurrentUserId();
        if (userId != null) {
            // Sets the PostgreSQL session variable before query execution
            entityManager.createNativeQuery(
                "SET LOCAL app.current_user_id = '" + userId + "'"
            ).executeUpdate();
        }
        return sql;
    }
}
```

**The defence-in-depth model:**

- Layer 1: Application code always passes `userId` in service method signatures (convention)
- Layer 2: Repository implementations include `userId` in all WHERE clauses (redundant filter)
- Layer 3: PostgreSQL RLS policy rejects any query that would return another user's rows, regardless
  of what the application sent (enforcement)

Layer 3 means a bug in layers 1 or 2 results in an empty result set rather than a data leak. This is
the property you want.

---

### 3. Outbox Pattern — Transaction Guarantee

The outbox ensures raw data is safe before processing begins. The key is that the outbox write and
the job queue write happen in **the same database transaction** as the acknowledgement of the Basiq
response.

```java
@Transactional
public void ingestBasiqTransactions(UUID userId,
                                     List<BasiqTransaction> transactions) {
    for (BasiqTransaction tx : transactions) {
        // Step 1: Write raw record to outbox (append-only)
        RawBankTransaction raw = buildRawRecord(tx, userId);
        raw.setChainHash(computeChainHash(raw, getLatestChainHash(userId)));
        rawIngestionRepository.save(raw);

        // Step 2: Write job to queue for normalisation
        JobQueueEntry job = JobQueueEntry.builder()
            .jobType(JobType.NORMALISE_TRANSACTION)
            .payload(Map.of("rawRecordId", raw.getId()))
            .status(JobStatus.PENDING)
            .build();
        jobQueueRepository.save(job);
    }
    // Both writes committed atomically — if either fails, neither commits
}
```

A separate normalisation worker picks up `NORMALISE_TRANSACTION` jobs:

```java
@Scheduled(fixedDelay = 5000)
@Transactional
public void processNormalisationJobs() {
    List<JobQueueEntry> jobs = jobQueueRepository
        .findPendingWithLock(JobType.NORMALISE_TRANSACTION, 10);

    for (JobQueueEntry job : jobs) {
        job.setStatus(JobStatus.PROCESSING);
        jobQueueRepository.save(job);

        try {
            UUID rawId = job.getPayload().get("rawRecordId");
            RawBankTransaction raw = rawIngestionRepository.findById(rawId);
            expenseService.importBankTransaction(raw.getUserId(),
                                                  toCommand(raw));
            job.setStatus(JobStatus.COMPLETED);
        } catch (Exception e) {
            handleJobFailure(job, e); // retry logic or dead letter
        }
        jobQueueRepository.save(job);
    }
}
```

---

### 4. Duplicate Detection — Heuristic Design

Duplicate detection runs after each transaction is normalised. It's a scoring function, not a binary
check:

```java
public class DuplicateDetectionService {

    private static final double DUPLICATE_THRESHOLD = 0.85;

    public Optional<DuplicateFlag> detectDuplicate(Expense incoming,
                                                     List<Expense> candidates) {
        return candidates.stream()
            .map(candidate -> score(incoming, candidate))
            .filter(result -> result.getScore() >= DUPLICATE_THRESHOLD)
            .max(Comparator.comparing(ScoringResult::getScore))
            .map(result -> buildDuplicateFlag(incoming, result));
    }

    private ScoringResult score(Expense a, Expense b) {
        double score = 0.0;

        // Amount match — highest weight, financial amounts are precise
        if (a.getAmount().compareTo(b.getAmount()) == 0) score += 0.40;

        // Merchant name similarity — fuzzy match for slight variations
        double nameSimilarity = levenshteinSimilarity(
            a.getMerchantName(), b.getMerchantName());
        score += nameSimilarity * 0.30;

        // Date proximity — within 5 days (covers PENDING → POSTED lag)
        long daysBetween = ChronoUnit.DAYS.between(a.getDate(), b.getDate());
        if (daysBetween == 0) score += 0.20;
        else if (daysBetween <= 2) score += 0.10;
        else if (daysBetween <= 5) score += 0.05;

        // Source diversity — MANUAL + BANK_IMPORT is more suspicious than two BANKs
        if (a.getSource() != b.getSource()) score += 0.10;

        // Pending/Posted flag — specific Basiq pattern
        boolean pendingPostedPair =
            (a.getBankStatus() == PENDING && b.getBankStatus() == POSTED) ||
            (a.getBankStatus() == POSTED  && b.getBankStatus() == PENDING);
        if (pendingPostedPair) score += 0.15; // can push over threshold alone with amount match

        return new ScoringResult(b, score, deriveDuplicateType(a, b, score));
    }
}
```

The `DUPLICATE_THRESHOLD` of 0.85 is a configurable constant, not hardcoded logic. You will want to
tune it based on false positive rates once you have real data. Externalise it to application config
from day one.

---

### 5. Prediction Engine — Strategy Wiring

```java
@Service
public class PredictionEngine {

    // Spring injects ALL beans implementing PredictionStrategy, ordered by @Order
    private final List<PredictionStrategy> strategies;

    public PredictionResult predict(PredictionContext context) {
        return strategies.stream()
            .filter(strategy -> strategy.canHandle(context))
            .findFirst()
            .map(strategy -> {
                PredictionResult result = strategy.predict(context);
                result.setStrategyName(strategy.strategyName());
                result.setStrategyVersion(strategy.strategyVersion());
                return result;
            })
            .orElseThrow(() -> new InsufficientDataException(
                "No strategy could handle the provided context"));
    }
}

@Component
@Order(1) // Tried last — simplest fallback
public class NaiveDailyRateStrategy implements PredictionStrategy {

    @Override
    public boolean canHandle(PredictionContext context) {
        // Can always handle if we have the minimum two fields
        return context.getTotalSpent() != null
            && context.getDaysElapsed() > 0;
    }

    @Override
    public PredictionResult predict(PredictionContext context) {
        BigDecimal dailyRate = context.getTotalSpent()
            .divide(BigDecimal.valueOf(context.getDaysElapsed()),
                    2, RoundingMode.HALF_UP);
        BigDecimal projected = dailyRate.multiply(
            BigDecimal.valueOf(context.getDaysInMonth()));

        return PredictionResult.builder()
            .projectedAmount(projected)
            .willExceedTarget(projected.compareTo(context.getTargetAmount()) > 0)
            .confidence(deriveConfidence(context.getDaysElapsed()))
            .build();
    }

    private Confidence deriveConfidence(int daysElapsed) {
        if (daysElapsed < 5)  return Confidence.LOW;
        if (daysElapsed < 15) return Confidence.MEDIUM;
        return Confidence.HIGH;
    }

    @Override public String strategyName()    { return "NAIVE_DAILY_RATE"; }
    @Override public String strategyVersion() { return "v1.0"; }
}
```

Future strategies (`ScheduledExpenseAwareStrategy`, `HistoricalPatternStrategy`) are added with
`@Order(2)`, `@Order(3)` — higher order means higher capability, tried first. No existing code
changes.

---

### 6. Job Queue — `FOR UPDATE SKIP LOCKED` Pattern

This is worth showing explicitly because it's the mechanism that makes the DB-based queue safe for
concurrent workers:

```sql
-- Worker polls every 5 seconds
SELECT * FROM job_queue
WHERE status = 'PENDING'
  AND job_type = 'NORMALISE_TRANSACTION'
ORDER BY created_at ASC
LIMIT 10
FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE` locks the selected rows. `SKIP LOCKED` means if a row is already locked by another
worker, skip it rather than wait. Two worker instances running simultaneously will each get a
different set of 10 rows, never the same row twice. No message is processed twice. No worker blocks
another.

This is the behaviour you'd pay for in RabbitMQ or Redis Streams — and you get it from PostgreSQL
for free at your scale.

---

## Maven/Gradle — The Build Tool Context

Maven and Gradle are **build tools** for Java projects. Their job is to compile your code, manage
external dependencies (like Spring Boot, HikariCP, Bucket4j), run tests, and package everything into
a deployable JAR file. Think of them as the equivalent of `npm` in the Node.js world, or `pip` +
`setuptools` in Python. Gradle is the more modern of the two — Spring Boot projects default to
Gradle now and it's what I'd recommend.

This is not the interesting part. The interesting part is how they handle **multi-module projects**.

---

## The Problem We're Solving

You have two deployable processes: the API and the Worker. They are separate JARs, separate Docker
containers, separate running processes. But they share things:

- The `PredictionStrategy` interface — both the API (serving the result) and the worker (running the
  strategies) need this definition
- The `ExpenseService` interface — the worker calls it, the API implements and serves it
- Domain objects like `Expense`, `BankAccount`, `PredictionContext` — both processes pass these
  around
- The `ObservabilityService` interface — every module in both processes calls it

If you have two completely separate projects, you'd have to copy these shared definitions into both.
That means when you change `ExpenseService`, you change it in two places. They drift apart. Bugs
appear that only exist in one process because the other has a stale copy. This is the classic
**duplication problem** in a multi-process system.

---

## The Solution — A Multi-Module Gradle Project

Gradle lets you structure one repository as multiple sub-projects, each producing its own JAR, with
explicit declared dependencies between them.

Your project structure would look like this:

```
expense-tracker/                    ← root project (no code, just config)
├── build.gradle                    ← shared dependency versions
├── settings.gradle                 ← declares which modules exist
│
├── core/                           ← shared library, produces core.jar
│   └── src/main/java/
│       ├── service/
│       │   ├── ExpenseService.java         (interface)
│       │   ├── BankIntegrationService.java (interface)
│       │   ├── AiCategorizationService.java(interface)
│       │   └── AuthService.java            (interface)
│       ├── domain/
│       │   ├── Expense.java
│       │   ├── BankAccount.java
│       │   ├── PredictionContext.java
│       │   └── AccessGrant.java
│       └── command/
│           ├── CreateExpenseCommand.java
│           └── ResolveDuplicateCommand.java
│
├── api/                            ← API process, produces api.jar
│   └── src/main/java/
│       ├── controller/
│       │   ├── ExpenseController.java
│       │   └── AuthController.java
│       └── service/impl/
│           ├── PostgresExpenseService.java  (implements ExpenseService)
│           └── PostgresAuthService.java     (implements AuthService)
│
└── worker/                         ← Worker process, produces worker.jar
    └── src/main/java/
        ├── job/
        │   ├── BankSyncJob.java
        │   └── AiCategorizationJob.java
        └── strategy/
            ├── NaiveDailyRateStrategy.java
            └── HistoricalPatternStrategy.java
```

The `settings.gradle` at the root simply declares the modules:

```groovy
rootProject.name = 'expense-tracker'
include 'core', 'api', 'worker'
```

And each module's `build.gradle` declares what it depends on:

```groovy
// core/build.gradle
// No dependency on api or worker — core knows nothing about them
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
}

// api/build.gradle
dependencies {
    implementation project(':core')   // api depends on core
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
}

// worker/build.gradle
dependencies {
    implementation project(':core')   // worker also depends on core
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.batch:spring-batch-core'
}
```

---

## What This Gives You Concretely

**Single source of truth for interfaces.** `ExpenseService` is defined once in `core`. When you
change its signature, both `api` and `worker` fail to compile immediately if either one is out of
sync. The compiler enforces the contract. You cannot accidentally have a stale copy.

**Enforced dependency direction.** `core` cannot import anything from `api` or `worker` — the
dependency only goes one way. If you try to import an `api` class from `core`, Gradle refuses to
compile it. This enforces the boundary rule we defined: domain logic has no dependency on
infrastructure.

**Independent deployment.** Running `./gradlew :api:bootJar` produces the API JAR. Running
`./gradlew :worker:bootJar` produces the Worker JAR. They are separate deployable artefacts. Your
`docker-compose.yml` runs them as separate containers. But they were compiled from one repository,
sharing one version of every interface.

**One repository, coherent history.** All changes to all three modules are in one Git repository. A
single pull request can change an interface in `core`, update the implementation in `api`, and
update the caller in `worker` — all atomically. You review one PR, you merge one commit, both
processes are updated together.

---

## What This Looks Like at Runtime

```
docker-compose.yml
├── api container        ← runs api.jar (contains core.jar compiled in)
├── worker container     ← runs worker.jar (contains core.jar compiled in)
└── postgres container   ← shared database
```

The `core.jar` is not a separately running process. It's compiled into both the API and worker JARs
at build time — its classes are physically present in both JARs. Think of it as a shared library,
not a service.

---

## The Evolution Path to Microservices

This structure is also the natural path to microservices when you're ready. Today the worker calls
`ExpenseService` as a direct Java method call (same JVM or separate JVM sharing the interface). When
you extract the Expense module into its own microservice, you write an
`HttpExpenseService implements ExpenseService` in the worker module — it makes HTTP calls instead of
direct calls. The worker's job code never changes. Only the injected implementation changes.

```java
// Today — direct call, shared library
@Bean
public ExpenseService expenseService(ExpenseRepository repo) {
    return new PostgresExpenseService(repo);
}

// Future — HTTP call to microservice
@Bean
public ExpenseService expenseService(RestTemplate http) {
    return new HttpExpenseService(http, "http://expense-service:8080");
}
```

One configuration change. Zero changes to any job that calls `ExpenseService`. This is exactly the
seam you need for the trading/payments extension you mentioned earlier.

---

## Summary

| Concept                           | What it means for your project                          |
| --------------------------------- | ------------------------------------------------------- |
| Gradle                            | Build tool — compiles, packages, manages dependencies   |
| Multi-module project              | One repo, three sub-projects: `core`, `api`, `worker`   |
| `core` module                     | Shared interfaces and domain objects, no infrastructure |
| `api` module                      | Spring Boot web app, implements `core` interfaces       |
| `worker` module                   | Spring Boot worker, calls `core` interfaces             |
| `implementation project(':core')` | Gradle syntax for "this module depends on core"         |

Does this make the packaging structure clear? If so we can move straight to the data model.
