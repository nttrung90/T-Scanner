# REPORT VIP BILLING ROUND 2 — PACKAGE Q08

**Package ID:** Q08  
**Defect Resolved:** Defect R09 (RTDN Retry, Dedup & Snapshot Hiện Hành)  
**Date:** 2026-09-26  
**Status:** VERIFIED & COMPLETE  

---

## 1. Executive Summary

Defect R09 previously caused Real-Time Developer Notifications (RTDN) to incorrectly record event timestamps before communicating with Google Play APIs. If the Google Play API encountered a transient failure (such as HTTP 503, 429, or network timeout), the event was not committed to the entitlement store, yet its timestamp remained recorded in memory. Subsequent retries of the exact same event were consequently rejected as `SKIPPED_STALE`, leaving the backend out of sync with Google Play until manual restoration.

In Package Q08, we fixed the ordering and retry lifecycle:
1. `lastProcessedEventTimes.set(purchaseToken, eventTime)` now strictly executes *after* the entitlement store successfully commits the updated state (`store.bindOrUpdate`).
2. If Google Play API throws or returns a transient failure, the handler returns status `ERROR` without updating the processed timestamp, ensuring that Google Cloud Pub/Sub retries can be processed smoothly.
3. Added support for Google Play `oneTimeProductNotification` (specifically `notificationType === 2` for cancellations/refunds) to transition one-time products to state `REVOKED` with monotonic snapshot version increment.
4. Updated HTTP endpoint `/api/v1/billing/rtdn` in `backend/billing-verifier/src/index.ts` to return HTTP `503 Service Unavailable` when handler returns `ERROR`, signaling Google Cloud Pub/Sub to apply its dead-letter / retry backoff schedule.

---

## 2. Changes Made

### Files Modified:
- `backend/billing-verifier/src/rtdnHandler.ts`:
  - Moved timestamp recording `this.lastProcessedEventTimes.set(...)` after `this.store.bindOrUpdate(...)`.
  - Added support for `oneTimeProductNotification` cancellations (`notificationType === 2 -> REVOKED`).
  - Gracefully handles unknown tokens (`TOKEN_UNKNOWN`) without polluting the processed timestamp cache.
- `backend/billing-verifier/src/index.ts`:
  - Updated `/api/v1/billing/rtdn` HTTP response code to return HTTP 503 on `result.status === 'ERROR'` (and HTTP 200 on `PROCESSED` or `SKIPPED_*`).

### Files Created:
- `backend/billing-verifier/test/rtdn-recovery.test.ts`:
  - Comprehensive suite with 5 test scenarios:
    1. `RTDN retry: transient Google Play API failure leaves event retryable`
    2. `RTDN deduplication: stale older event arriving after newer event is rejected`
    3. `RTDN one-time product notification: revocation transitions state to REVOKED`
    4. `RTDN unknown token skips gracefully and processes once bound`
    5. `RTDN HTTP endpoint: returns 503 on ERROR and 200 on PROCESSED/SKIPPED`

---

## 3. Verification Evidence

### 3.1 Targeted Probe Verification
`backend/billing-verifier/test/round2-regression.test.ts`:
```
✔ probe RTDN retries same event after temporary Google API failure (2.9252ms)
✔ probe authoritative expired verification must not restore stale active receipt (1.4468ms)
✔ probe HTTP restore endpoint must require authentication (70.2723ms)
✔ control ownership conflict rejected within one store instance (1.0654ms)
ℹ tests 4
ℹ suites 0
ℹ pass 4
ℹ fail 0
```

### 3.2 Q08 Dedicated Test Suite
`backend/billing-verifier/test/rtdn-recovery.test.ts`:
```
✔ RTDN retry: transient Google Play API failure leaves event retryable (3.345ms)
✔ RTDN deduplication: stale older event arriving after newer event is rejected (0.8959ms)
✔ RTDN one-time product notification: revocation transitions state to REVOKED (0.9284ms)
✔ RTDN unknown token skips gracefully and processes once bound (0.9553ms)
✔ RTDN HTTP endpoint: returns 503 on ERROR and 200 on PROCESSED/SKIPPED (49.6517ms)
ℹ tests 5
ℹ suites 0
ℹ pass 5
ℹ fail 0
```

### 3.3 Full Backend Test Suite
```
ℹ tests 52
ℹ suites 0
ℹ pass 52
ℹ fail 0
```

---

## 4. Risks & Mitigations
- **Pub/Sub Retry Storms:** Handled via HTTP 503, leveraging Cloud Pub/Sub's exponential backoff and dead-letter queues.
- **Out-of-order Delivery:** Any notification with timestamp $\le$ last committed event timestamp is rejected with `SKIPPED_STALE`, preventing old events from overwriting fresh state.

---

## 5. Handoff to Q09
Package Q08 is complete. Ready to proceed to **Q09: Outbox Worker & Ack Processing (Defect R08)**.
