# Deployment

> **Context:** Expands [overview.md §7](../overview.md#7-how-the-system-stays-healthy). See also [local-development.md](local-development.md) and [scheduled-jobs.md](scheduled-jobs.md).

How the system gets from a `git push` to a running production deployment. Three moving parts: Dockerfiles (build), GitHub Actions (validate), Render (deploy).

---

## The high-level flow

```
git push main
    → GitHub Actions runs: compile → test → build Docker images (validation only)
    → Render detects the push and builds the API image using the Dockerfile
    → Render builds the worker image using Dockerfile.worker
    → On first API container startup: Flyway runs migrations
    → Render calls /actuator/health on the API; when it returns UP, traffic is routed in
    → Worker container starts separately, registers cron schedule, sits idle until firings
```

GitHub Actions does **validation only** — it does not push images anywhere and does not deploy. Render deploys directly from the source via `autoDeploy: true`.

---

## Dockerfiles — multi-stage builds

Both Dockerfiles use the same two-stage pattern. **Stage 1 (build)** uses the full JDK + Gradle to produce the JAR. **Stage 2 (runtime)** is JRE-only and copies just the JAR from stage 1. The final runtime image carries no build tools — smaller, smaller attack surface.

### `Dockerfile` (API)

```dockerfile
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle settings.gradle
COPY build.gradle build.gradle
COPY core/build.gradle core/build.gradle
COPY api/build.gradle api/build.gradle
COPY worker/build.gradle worker/build.gradle

RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY core core
COPY api api

RUN ./gradlew :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/api/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xmx400m -Xss512k -XX:+UseContainerSupport"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Layer-cache trick.** Only the `*.gradle` files are copied before `RUN ./gradlew dependencies`. Docker caches each instruction layer based on its inputs. By copying *only* build files first, the dependency download layer's cache key depends on the build files. Source-code changes do not invalidate it — they invalidate only the later `COPY core core` and beyond.

**`-Xmx400m`** caps the JVM heap because Render's free tier has 512 MB total RAM. Without this the JVM might grab more and get OOM-killed.

**`-XX:+UseContainerSupport`** makes the JVM read its memory limit from Docker cgroups rather than the host machine's total RAM. Without it a JVM in a 512 MB container might see the host's 16 GB and allocate accordingly.

### `Dockerfile.worker`

Same shape; produces `worker.jar` instead of `api.jar`. `-Xmx200m` because the worker has no request load.

---

## `docker-compose.yml` — local only

Lives in the repo root but is not used in production. Brings up Postgres for local development. The API and Worker run via Gradle locally; only the production deployment runs them in containers.

```yaml
services:
  db:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./docker/init-db.sh:/docker-entrypoint-initdb.d/01_init.sh
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_SUPERUSER_USERNAME} -d expense_db"]
      interval: 10s
      timeout: 5s
      retries: 5
```

The `init-db.sh` runs once on first start (when the volume is empty) and creates the lower-privilege application role.

---

## `render.yaml` — Infrastructure as Code

Declares to Render: one PostgreSQL database, one web service (the API), one worker service. Lives in the repo so the infrastructure definition is version-controlled and reviewable.

```yaml
databases:
  - name: expense-tracker-db
    plan: free
    region: oregon

services:
  - type: web
    name: expense-tracker-api
    runtime: docker
    dockerfilePath: ./Dockerfile
    healthCheckPath: /actuator/health
    autoDeploy: true
    envVars:
      - key: DB_URL
        fromDatabase:
          name: expense-tracker-db
          property: connectionString
      - key: JWT_SECRET
        generateValue: true
      - key: DB_USERNAME
        sync: false
      # ...

  - type: worker
    name: expense-tracker-worker
    runtime: docker
    dockerfilePath: ./Dockerfile.worker
    autoDeploy: true
    envVars:
      - key: DB_HOST
        fromDatabase:
          name: expense-tracker-db
          property: host
      # ...
```

Three injection patterns to understand:

- **`fromDatabase`** — Render looks up the database service by name and injects the chosen property (connection string, host, port, etc.). You never hardcode database URLs.
- **`generateValue: true`** — Render generates a cryptographically random value on first deploy and stores it. Used for `JWT_SECRET` so no human ever sees or manages it.
- **`sync: false`** — Render will not include this env var in the YAML. You set it manually in the Render dashboard. Used for secrets that should not live in version control.

`autoDeploy: true` — every push to `main` triggers a new deploy. CI does not gate this — CI and deploy are independent.

---

## GitHub Actions — `.github/workflows/ci.yml`

Validation pipeline only — runs on every push to `main` and every PR targeting `main`.

```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle') }}

      - run: chmod +x gradlew
      - run: ./gradlew :api:bootJar :worker:bootJar --no-daemon -x test
      - run: ./gradlew test --no-daemon
      - run: docker build -t expense-tracker-api:${{ github.sha }} .
      - run: docker build -f Dockerfile.worker -t expense-tracker-worker:${{ github.sha }} .
```

Order matters — fail fast at the cheapest layer:
1. **Compile** — fail immediately if the code does not build.
2. **Test** — fail if any test breaks.
3. **Docker build** — fail if the image cannot be assembled.

CI does not push images or notify Render. Render's own build picks up the source from the same push and produces its own image. The Docker build step in CI is purely a "does this image still build?" check.

`${{ github.sha }}` tags each image with the exact commit, so you can always identify which code is in which image — useful when CI saves the image as an artefact.

---

## Free-tier caveats

> **Note:** Cold-start timing is illustrative — re-measure before quoting.

- **API container spins down after 15 minutes of inactivity.** First request after spin-down takes ~30–60 seconds (Spring + Flyway + DB connection pool). Acceptable for a demo.
- **PostgreSQL is deleted after 90 days** on the free tier. Re-seed required. Use paid tier for anything you actually want to keep.
- **Worker is not on the free tier.** Free Render plans do not include a persistent background-worker slot. The README notes that the worker can be downgraded to a Render Cron Job if the free worker slot is unavailable — same JAR, different trigger.

See [../roadmap.md](../roadmap.md) for alternative hosting options where the worker model works on a free or low-cost plan.
