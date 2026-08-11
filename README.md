# SentinelOps — Autonomous Financial Resilience Engine

![Java 21](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot 3.3](https://img.shields.io/badge/Spring_Boot-3.3-green?style=flat-square&logo=springboot)
![Gemini 3.5](https://img.shields.io/badge/Gemini-3.5_Pro-blue?style=flat-square&logo=google)
![SQLite WAL](https://img.shields.io/badge/SQLite-WAL_Mode-lightblue?style=flat-square&logo=sqlite)
![Resilience4j](https://img.shields.io/badge/Resilience4j-Circuit_Breaker-yellow?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-brightgreen?style=flat-square)

> **SentinelOps** is a locally-hosted, autonomous multi-agent AI system for real-time financial fraud detection. Each financial transaction is processed through a hardened pipeline of specialized AI agents — from initial risk triage to forensic behavioral analysis and final compliance arbitration — all governed by Zero-Trust principles, Model Armor guardrails, and an Agent Gateway with Human-in-the-Loop capabilities.

---

## ⚡ What makes SentinelOps different?

| Feature | Description |
|---|---|
| 🤖 **True Multi-Agent Pipeline** | Three independent agents (TriageAgent → ForensicAgent → ResolutionAgent), each with its own transactional boundary and Zero-Trust identity |
| 🛡️ **Model Armor** | Input sanitization against Prompt Injection attacks + PII inspection on all AI outputs before persistence |
| 🏛️ **Policy Gateway** | Governance layer enforcing compliance rules with Human-in-the-Loop escalation (e.g., amounts ≥ R$10k auto-escalate) |
| 🔒 **Zero-Trust Data Minimization** | ForensicAgent operates exclusively on anonymized data — it never sees raw account IDs |
| ⚡ **Resilience4j** | Circuit Breaker + Retry with exponential backoff ensures graceful degradation when Gemini API is unavailable |
| 🔍 **Full Observability** | Every agent decision is logged, timestamped, and queryable via the tracing API or the live dashboard |
| 🖥️ **Local Dashboard** | Built-in tactical UI served by Spring Boot — no external infrastructure required |

---

## Architecture

```
  POST /api/v1/transactions
            │
            ▼
  ┌─────────────────────────┐
  │  TransactionIngestionService  │   Persists as PENDING in SQLite (WAL mode)
  └────────────┬────────────┘
               │
               │  Scheduler polls every 5s
               ▼
  ┌────────────────────────────────────────────────────┐
  │              TriageAgent [RISK_ANALYST]             │   [T1 — own transaction]
  │                                                    │
  │  1. ModelArmor.sanitizeInput(merchantCategory,     │
  │                               accountId)           │
  │  2. Gemini 3.5 Pro → JSON { decision,              │
  │                                riskScore, reason } │
  │  3. ModelArmor.inspectOutput(rawResponse)          │
  │  4. Commit AuditLog + status update                │
  └──────────────┬─────────────────────────────────────┘
                 │
        if MANUAL_REVIEW
                 │
                 ▼
  ┌────────────────────────────────────────────────────┐
  │            ForensicAgent [DATA_ANALYST]             │   [T2 — own transaction]
  │                                                    │
  │  1. Zero-Trust check (permissions must include     │
  │     READ_ANONYMIZED_HISTORY)                       │
  │  2. AnonymizationService.maskAccountId()           │
  │  3. Gemini behavioral analysis on masked history   │
  │  4. Commit AuditLog                               │
  └──────────────┬─────────────────────────────────────┘
                 │
                 ▼
  ┌────────────────────────────────────────────────────┐
  │         ResolutionAgent [COMPLIANCE_OFFICER]        │   [T3 — own transaction]
  │                                                    │
  │  PolicyGateway.enforce():                          │
  │    R1: amount ≥ R$10,000 + BLOCKED → MANUAL_REVIEW │
  │    R2: riskScore > 0.95  → BLOCKED allowed         │
  │  Commit final AuditLog                             │
  └────────────────────────────────────────────────────┘
```

> **Critical architectural note on SQLite concurrency:** `processTriage()` is a **pure orchestrator** (no `@Transactional`). Each agent stage commits its own JPA transaction before the next begins. This eliminates `SQLITE_BUSY` errors caused by Hibernate's auto-flush in single-writer mode.

---

## Agent Identity & Zero-Trust

Each agent operates under a strict identity with minimal permissions:

| Agent | Identity ID | Role | Permissions |
|---|---|---|---|
| TriageAgent | `TRIAGE_AGENT_01` | `RISK_ANALYST` | `READ_TRANSACTION`, `WRITE_AUDIT_LOG` |
| ForensicAgent | `FORENSIC_AGENT_01` | `DATA_ANALYST` | `READ_ANONYMIZED_HISTORY`, `WRITE_AUDIT_LOG` |
| ResolutionAgent | `RESOLUTION_AGENT_01` | `COMPLIANCE_OFFICER` | `UPDATE_TRANSACTION_STATUS`, `WRITE_AUDIT_LOG` |

Permission violations throw `SecurityException` and halt the offending agent without affecting the pipeline.

---

## Model Armor — Guardrails

```java
// Input sanitization (Prompt Injection prevention)
String safeCategory = modelArmorService.sanitizeInput(rawMerchantCategory);
// Removes patterns: "ignore previous instructions", "system:", "override rules", etc.

// Output inspection (PII leak detection)
String safeResponse = modelArmorService.inspectOutput(rawGeminiResponse);
// Masks patterns matching SSN, CPF, credit card numbers, email addresses
// before any AI response is persisted to the AuditLog
```

---

## Tech Stack

```
Runtime    │ Java 21 (Virtual Threads ready)
Framework  │ Spring Boot 3.3.2 · Spring Data JPA · Spring Web · Spring Scheduler
AI         │ Google Gemini 3.5 Pro (via REST) · Structured JSON output
Database   │ SQLite 3 in WAL mode (HikariCP connection pool)
ORM        │ Hibernate 6.5
Resilience │ Resilience4j 2 — CircuitBreaker + Retry (exponential backoff, 429 handling)
Tooling    │ Lombok · Maven 3 · Spring Actuator
UI         │ Tailwind CSS CDN · Font Awesome · Vanilla JS (zero dependencies)
```

---

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- Internet access (for Gemini API calls)

```bash
# 1. Clone
git clone https://github.com/Antonio7s/SentinelOps.git
cd SentinelOps

# 2. Build
mvn clean package -DskipTests

# 3. Run (SQLite DB created automatically at first boot)
java -jar target/sentinelops-core-0.1.0-SNAPSHOT.jar
```

The application starts on **http://localhost:8080**.  
Open the browser to access the **tactical dashboard** at `http://localhost:8080/`.

---

## 🖥️ Dashboard

The built-in dashboard is served directly by Spring Boot (no external server needed).

**Open `http://localhost:8080` in any browser.**

| Section | Description |
|---|---|
| **Metrics Bar** | 6 KPI cards auto-refreshed every 3s: Total · Approved · Blocked · Review · Approval Rate · Avg Latency |
| **Circuit Breaker Badge** | Live status of Gemini API circuit breaker: `CLOSED` (green) · `OPEN` (red) · `HALF_OPEN` (amber) |
| **Quick Inject** | 3 preset scenario buttons + manual form — instantly submits test transactions for live demo |
| **Live Feed** | Real-time transaction table with masked account IDs and color-coded status pills |
| **Trace Viewer** | Click **Inspect** on any transaction to open the full agent execution timeline modal |

---

## API Reference

### Ingest Transaction
```http
POST /api/v1/transactions
Content-Type: application/json

{
  "accountId": "ACC-00123",
  "amount": 15000.00,
  "merchantCategory": "JEWELRY"
}
```

**Response `202 Accepted`:**
```json
{
  "transactionId": "24df2f40-f3a8-490c-ac09-7c6e8f81509a",
  "status": "PENDING",
  "statusUrl": "/api/v1/transactions/24df2f40-f3a8-490c-ac09-7c6e8f81509a"
}
```

### Query Status
```http
GET /api/v1/transactions/{transactionId}
```

### List Transactions
```http
GET /api/v1/transactions
GET /api/v1/transactions?status=MANUAL_REVIEW
```

---

## Observability API

### Transaction Trace — Full E2E audit trail
```http
GET /api/v1/observability/trace/{transactionId}
```

**Response:**
```json
{
  "transactionId": "24df2f40-f3a8-490c-ac09-7c6e8f81509a",
  "accountIdMasked": "VIP-***01",
  "amount": 15000.00,
  "merchantCategory": "JEWELRY",
  "finalStatus": "MANUAL_REVIEW",
  "totalLatencyMs": 21511,
  "policyOverridden": false,
  "overrideReason": null,
  "executionChain": [
    {
      "stepOrder": 1,
      "agentName": "TriageAgent",
      "identityRole": "RISK_ANALYST",
      "inputSummary": "Transaction of R$15000.00 in category JEWELRY — account VIP-***01",
      "thoughtProcess": "{\"model\":\"gemini-3.5-pro\",\"riskScore\":0.68,\"reason\":\"...\",\"armorApplied\":true}",
      "decision": "MANUAL_REVIEW",
      "latencyMs": 16134,
      "timestamp": "2026-08-10T10:26:38"
    },
    {
      "stepOrder": 2,
      "agentName": "ResolutionAgent",
      "identityRole": "COMPLIANCE_OFFICER",
      "thoughtProcess": "{\"policyOverridden\":false,\"zeroTrustVerified\":true,...}",
      "decision": "POLICY_CONFIRMED_MANUAL_REVIEW",
      "latencyMs": 5377,
      "timestamp": "2026-08-10T10:26:44"
    }
  ]
}
```

### System Metrics
```http
GET /api/v1/observability/metrics
```

**Response:**
```json
{
  "totalTransactions": 42,
  "approvedCount": 31,
  "blockedCount": 4,
  "manualReviewCount": 6,
  "pendingCount": 1,
  "circuitBreakerStatus": "CLOSED",
  "averageProcessingTimeMs": 14230.5,
  "approvalRate": 0.756
}
```

**Circuit Breaker states:**

| Status | Meaning |
|---|---|
| `CLOSED` | ✅ Gemini API operational — full pipeline active |
| `OPEN` | 🔴 Gemini API unavailable — fallback to MANUAL_REVIEW |
| `HALF_OPEN` | 🟡 Recovering — probing API with test calls |

---

## Database Schema

SQLite database (`sentinelops.db`) is auto-created on first boot via Hibernate DDL.

```sql
-- Ingested financial transactions
CREATE TABLE transactions (
  id                TEXT PRIMARY KEY,  -- UUID
  account_id        TEXT NOT NULL,
  amount            REAL NOT NULL,
  merchant_category TEXT NOT NULL,
  status            TEXT NOT NULL,     -- PENDING | APPROVED | BLOCKED | MANUAL_REVIEW
  timestamp         TEXT NOT NULL
);

-- Immutable agent decision log (append-only)
CREATE TABLE agent_audit_logs (
  id              TEXT PRIMARY KEY,   -- UUID
  transaction_id  TEXT NOT NULL,
  agent_name      TEXT NOT NULL,
  thought_process TEXT,               -- Full JSON reasoning (Model Armor applied)
  decision        TEXT NOT NULL,
  timestamp       TEXT NOT NULL
);
```

---

## Project Structure

```
src/main/java/com/sentinelops/
│
├── agents/                          # AI Agent implementations
│   ├── TriageAgent.java             # Orchestrator + Gemini triage + Model Armor
│   ├── ForensicAgent.java           # Behavioral analysis (Zero-Trust, anonymized data only)
│   ├── ResolutionAgent.java         # Final compliance arbitration
│   ├── TriageScheduler.java         # @Scheduled dispatcher (5s polling)
│   ├── TriageDecision.java          # Parsed Gemini JSON response record
│   └── ForensicAnalysisResult.java  # Forensic result record
│
├── api/                             # REST Controllers + DTOs
│   ├── IngestionController.java     # POST + GET /api/v1/transactions
│   ├── ObservabilityController.java # GET /api/v1/observability/trace + /metrics
│   ├── GlobalExceptionHandler.java  # Centralized error handling
│   └── dto/                         # Response/Observability DTOs
│
├── core/                            # Domain + Services
│   ├── AgentIdentity.java           # Zero-Trust identity record per agent
│   ├── PolicyGateway.java           # Agent Gateway + compliance rules
│   ├── ObservabilityService.java    # Trace reconstruction + metrics aggregation
│   ├── TransactionIngestionService.java
│   ├── Transaction.java             # JPA Entity
│   └── AgentAuditLog.java           # JPA Entity (append-only)
│
└── infrastructure/
    ├── ai/                          # Gemini API client + Resilience4j config
    │   ├── GeminiApiClient.java     # REST client with Circuit Breaker + Retry
    │   └── GeminiRestClientConfig.java
    ├── db/                          # SQLite WAL configuration (HikariCP)
    └── security/
        ├── ModelArmorService.java   # Prompt Injection sanitizer + PII inspector
        └── AnonymizationService.java # PII masking (Zero-Trust)

src/main/resources/
├── application.yml                  # Spring + Resilience4j + Gemini config
└── static/
    └── index.html                   # Self-contained tactical dashboard (Tailwind + Vanilla JS)
```

---

## Security Notes

| Concern | Implementation |
|---|---|
| **API Key** | Injected via environment variable (`GEMINI_API_KEY`) with a safe placeholder default (`${GEMINI_API_KEY:YOUR_GEMINI_API_KEY_HERE}`) — never hardcoded or committed to the repository. |
| **PII Protection** | Account IDs are never sent to Gemini without masking |
| **Prompt Injection** | `ModelArmorService.sanitizeInput()` strips injection patterns before every API call |
| **AI Output Leaks** | `ModelArmorService.inspectOutput()` censures PII in AI responses before logging |
| **Git Protection** | `sentinelops.db`, `target/`, and IDE files are excluded via `.gitignore` |

---

## Hackathon Pillars Coverage

| Pillar | Implementation | Status |
|---|---|---|
| **Agent Identity** | `AgentIdentity` record with role + minimal permissions per agent | ✅ |
| **Zero-Trust Data Minimization** | ForensicAgent operates exclusively on `maskAccountId()` output | ✅ |
| **Model Armor** | Input sanitization (Prompt Injection) + Output inspection (PII) | ✅ |
| **Agent Gateway / Policy Gateway** | `PolicyGateway` with R1/R2 compliance rules + Human-in-the-Loop | ✅ |
| **Resilience** | Resilience4j Circuit Breaker (CLOSED/OPEN/HALF_OPEN) + Retry (4 attempts, exponential backoff) | ✅ |
| **Observability & Traceability** | Full E2E trace per transaction + live system metrics dashboard | ✅ |

---

*Built with ❤️ for the All Things Agentic Hackathon — SentinelOps demonstrates that enterprise-grade agentic AI systems can be built lean, fast, and auditable.*
