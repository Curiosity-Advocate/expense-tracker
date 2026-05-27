** GitHub Actions setup**

GitHub Actions is a CI/CD system built into GitHub. You define workflows as YAML files and GitHub runs them automatically on events like pushes and pull requests.

**Where the file lives**

```
expense-tracker/
└── .github/
    └── workflows/
        └── ci.yml        ← GitHub looks here automatically
```

The `.github/workflows/` path is not configurable — GitHub only scans this exact location. You can have multiple files here for different workflows.

**Setting it up**

Create the directory and file:

```bash
mkdir -p .github/workflows
touch .github/workflows/ci.yml
```

A basic CI pipeline for your project:

```yaml
# .github/workflows/ci.yml

name: CI

# Triggers — when does this run?
on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest   # GitHub provides this machine for free

    # Spin up a PostgreSQL container alongside the build
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: expense_db_test
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: testpassword
        ports:
          - 5432:5432
        # Wait until PostgreSQL is ready before running tests
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      # Check out your code
      - name: Checkout code
        uses: actions/checkout@v4

      # Set up Java 21
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      # Cache Gradle dependencies so they don't re-download every run
      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: ~/.gradle/caches
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle') }}

      # Make gradlew executable (Git doesn't always preserve this)
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      # Run the build and tests
      - name: Build and test
        run: ./gradlew build test
        env:
          DB_URL: jdbc:postgresql://localhost:5432/expense_db_test
          DB_USERNAME: expense_app
          DB_PASSWORD: testpassword
          DB_MIGRATION_USERNAME: postgres
          DB_MIGRATION_PASSWORD: testpassword
          JWT_SECRET: test-secret-key-that-is-long-enough-for-hs256
```

**How secrets work**

You never put real passwords in the YAML file. GitHub has a secrets store — go to your repository, Settings → Secrets and variables → Actions → New repository secret. Add `DB_PASSWORD`, `JWT_SECRET` etc. Then reference them in the YAML:

```yaml
env:
  DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
  JWT_SECRET: ${{ secrets.JWT_SECRET }}
```

GitHub injects the values at runtime and masks them in logs so they never appear in plain text.
---

This command should replace docker's init-deb.sql command when we use GitHib action/Render to initialise DB:

# In your GitHub Actions deploy step
```yml
- name: Create app role
  run: |
    psql ${{ secrets.DB_MIGRATION_URL }} \
      -c "CREATE ROLE expense_app LOGIN PASSWORD '${{ secrets.DB_APP_PASSWORD }}' \
          ON CONFLICT DO NOTHING;"
```
