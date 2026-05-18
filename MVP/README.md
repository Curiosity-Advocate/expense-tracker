# Expense Tracker API

A personal finance backend built with Spring Boot 3.3 / Java 21 and PostgreSQL 16.

**Live demo:** [https://expense-tracker-api.onrender.com/swagger-ui.html](https://expense-tracker-api.onrender.com/swagger-ui.html)
*(First request may take ~30 s to wake the free Render instance)*

---

## What it does

| Area | Highlights |
|---|---|
| **Auth** | Register, login, JWT (7-day), token revocation, BCrypt, brute-force lockout (5 attempts → 15 min) |
| **Expenses** | Full CRUD, multi-category with even/custom split amounts, idempotency key, soft delete, paginated list |
| **Categories** | 16 system defaults + user-defined, immutable system categories enforced at DB trigger level |
| **Targets** | Per-category, multi-category, and total monthly budgets with INCLUSIVE/EXCLUSIVE scopes |
| **Prediction** | End-of-month spend projection via `NaiveDailyRateStrategy` (daily rate × days remaining) |
| **Data** | PostgreSQL partitioned table (expenses by year), Row Level Security on all user tables, materialized views |

---

## Architecture

```
┌─────────────────────────┐
│  Client / Swagger UI    │
└────────────┬────────────┘
             │ HTTPS
┌────────────▼────────────┐
│  api module             │  Spring Boot 3.3, port 8080
│  JWT filter → RLS aspect│
│  Controllers / Services │
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│  PostgreSQL 16          │  RLS policies, partitioned expenses table
│  Flyway migrations      │  Superuser runs DDL, app role has DML only
└─────────────────────────┘
             ▲
┌────────────┴────────────┐
│  worker module          │  Nightly cleanup + materialized view refresh
└─────────────────────────┘
```

**Multi-module Gradle:** `core` (domain, commands, service interfaces) → `api` + `worker` depend on it.

---

## Local development

### Prerequisites
- Docker Desktop
- Java 21

### Start the database
```bash
cd MVP
docker compose up -d
```

### Run the API
```bash
./gradlew :api:bootRun
```

Open Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

### Environment variables (copy `.env.example` → `.env`)

| Variable | Description |
|---|---|
| `DB_URL` | JDBC connection string |
| `DB_SUPERUSER_USERNAME` | Postgres superuser (Flyway migrations) |
| `DB_SUPERUSER_PASSWORD` | Superuser password |
| `DB_USERNAME` | App role username |
| `DB_APP_PASSWORD` | App role password |
| `JWT_SECRET` | HS256 signing key (min 32 chars) |

---

## Deploying to Render (one-click)

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy)

Or manually:

1. Fork this repo
2. Create a new **Web Service** on Render, connect your fork
3. Set **Dockerfile path** to `MVP/Dockerfile`
4. Create a **PostgreSQL** database and link it via `DB_URL`
5. Set the remaining env vars (see `render.yaml`)
6. Deploy — Flyway runs migrations automatically on startup

---

## API overview

All endpoints (except `/api/v1/auth/**`) require `Authorization: Bearer <token>`.

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/auth/register` | Register |
| POST | `/api/v1/auth/login` | Login → JWT |
| POST | `/api/v1/auth/logout` | Revoke token |
| GET | `/api/v1/users/me` | My profile |
| PATCH | `/api/v1/users/me` | Update discoverability |
| POST | `/api/v1/expenses` | Create expense |
| GET | `/api/v1/expenses` | List (paginated, filterable) |
| GET | `/api/v1/expenses/{id}?expenseDate=` | Get one |
| PATCH | `/api/v1/expenses/{id}?expenseDate=` | Update |
| DELETE | `/api/v1/expenses/{id}?expenseDate=` | Soft delete |
| GET | `/api/v1/expenses/summary` | Grouped totals |
| GET | `/api/v1/categories` | List categories |
| POST | `/api/v1/categories` | Create user category |
| PATCH | `/api/v1/categories/{id}` | Rename/describe |
| POST | `/api/v1/targets` | Create target |
| GET | `/api/v1/targets` | List targets |
| GET | `/api/v1/targets/{id}/status` | Live status + prediction |
| DELETE | `/api/v1/targets/{id}` | Delete target |

---

## Design decisions

**Why does a single-expense lookup require `expenseDate`?**
The `expenses` table is partitioned by `expense_date` (one partition per year). PostgreSQL requires the partition key in the primary key, making the composite PK `(id, expense_date)`. The date is cheap to pass and allows the planner to scan only one partition.

**Why is the category weight stored as an absolute amount (not a percentage)?**
`expense_categories.weight_amount` stores the pre-computed dollar split. This eliminates recomputation on every aggregation query and keeps the materialized view refresh simple.

**Why does `NaiveDailyRateStrategy` never get modified?**
Open/Closed Principle — once a strategy is live, historical predictions must stay reproducible. Adding v1.1 means creating a new class, not editing this one.
