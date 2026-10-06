# REPORT VIP BILLING ROUND 2 — PACKAGE Q09

**Package ID:** Q09  
**Defect Resolved:** Defect R08 / R05 (Acknowledge Outbox & Worker Thật với Durability và Startup Recovery)  
**Date:** 2026-09-26  
**Status:** VERIFIED & COMPLETE  

---

## 1. Executive Summary

Under Defect R08/R05, previous implementations lacked a persistent outbox queue and a resilient background worker for acknowledging purchases with Google Play. If a process crashed immediately after granting an entitlement but before acknowledging it with Google Play, the acknowledge job was lost, causing Google Play to automatically refund/revoke the purchase after 3 days. Furthermore, there was no autonomous background worker with bounded retry, startup recovery, and permanent failure classification.

In Package Q09:
1. **Durability First ("Persist before Ack"):** `verifier.ts` enqueues an acknowledge retry job to the durable SQLite `ack_retry_queue` immediately after verifying an active entitlement. Even if the process crashes immediately following the grant, the job remains persisted on disk.
2. **Crash & Startup Recovery:** `AckWorker` was implemented in `src/ackWorker.ts`. Upon initialization/startup, it immediately performs an outbox scan to recover any pending acknowledge jobs left over from previous process runs or unexpected crashes.
3. **Autonomous Background Polling:** `AckWorker` runs on a configurable background timer without requiring test fixtures or callers to manually invoke `drainPendingQueue()`.
4. **Bounded Retry & Exponential Backoff:** Transient failures from Google Play (e.g. HTTP 503/network timeout) are retried with exponential backoff up to `maxAttempts`. Once bounded retry is exceeded, the job transitions to `FAILED` and halts further churn.
5. **Idempotent Acknowledge & Duplicate Protection:** If Google Play reports that a token has already been acknowledged (HTTP 400 'already acknowledged'), `AcknowledgeService` classifies this as `ALREADY_ACKNOWLEDGED`, marks the job `COMPLETED` in the database, and prevents duplicate retry loops.
6. **Permanent Failure Distinction:** Permanent client errors (e.g. HTTP 404 or non-retryable 400) are recognized immediately, marked `FAILED` with retry delay < 0, and excluded from future polls.
7. **Strict Ownership Check:** Calling acknowledge on unverified tokens or tokens owned by another user is strictly blocked.

---

## 2. Changes Made

### Files Modified:
- `backend/billing-verifier/src/storage/sqliteDriver.ts`:
  - Updated `markAckFailure` to support permanent failure status (`status = 'FAILED'` when `retryDelayMs < 0`).
- `backend/billing-verifier/src/store.ts`:
  - Exported `AckRetryItem`.
  - Added public delegation methods to `EntitlementStore`: `enqueueAckRetry`, `getPendingAckRetries`, `markAckSuccess`, `markAckFailure`.
- `backend/billing-verifier/src/verifier.ts`:
  - Tracked Google Play `acknowledgementState`.
  - Added durable outbox enqueue logic strictly *after* successful active entitlement commit (`bindOrUpdate`).
- `backend/billing-verifier/src/ackService.ts`:
  - Integrated durable store persistence before network calls.
  - Handled Google Play idempotent "already acknowledged" responses as `ALREADY_ACKNOWLEDGED`.
  - Handled permanent failures as `FAILED`.
  - Added support for backoff delay via `retryDelayMs`.
- `backend/billing-verifier/src/index.ts`:
  - Integrated `AckWorker` into HTTP server lifecycle (`start()` on server launch, `stop()` on server shutdown).
  - Exported `AckWorker`, `ackWorker`, `ackService`.

### Files Created:
- `backend/billing-verifier/src/ackWorker.ts`:
  - Full background worker with startup recovery, bounded retry, exponential backoff, and graceful shutdown.
- `backend/billing-verifier/test/ack-restart.test.ts`:
  - 6 dedicated test cases covering crash/restart recovery, already-acknowledged idempotence, permanent errors, bounded retries, unverified token rejection, and autonomous background polling.

---

## 3. Verification Evidence

### 3.1 Q09 Dedicated Test Suite (`test/ack-restart.test.ts`)
```
✔ Q09: Crash after grant before ack - restart preserves job and worker recovers (117.2751ms)
✔ Q09: Acknowledge idempotence - Play returns already acknowledged (99.5451ms)
✔ Q09: Permanent acknowledge error transitions job to FAILED without endless retry (103.5546ms)
✔ Q09: Worker bounded retry stops retrying after maxAttempts is reached (162.9331ms)
✔ Q09: Do not acknowledge token that is not verified in store (0.9938ms)
✔ Q09: Worker autonomously runs in background without manual drain (162.9292ms)
ℹ tests 6
ℹ suites 0
ℹ pass 6
ℹ fail 0
```

### 3.2 Regression Suite (`test/round2-regression.test.ts`)
```
✔ probe RTDN retries same event after temporary Google API failure (2.914ms)
✔ probe authoritative expired verification must not restore stale active receipt (1.607ms)
✔ probe HTTP restore endpoint must require authentication (67.5915ms)
✔ control ownership conflict rejected within one store instance (0.7736ms)
ℹ tests 4
ℹ suites 0
ℹ pass 4
ℹ fail 0
```

### 3.3 Full Backend Test Suite
```
ℹ tests 58
ℹ suites 0
ℹ pass 58
ℹ fail 0
```

---

## 4. Handoff to Q10
Package Q09 is complete and verified. Ready to proceed to **Q10: Triển khai Google Play API adapter (phần server R01)**.
