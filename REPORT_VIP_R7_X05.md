# Checkpoint Report X05 — Android Parser State Validation, Strict Required Fields & Metadata

- **Package:** X05 — Android parser theo state, strict required fields và metadata (F09, F06 parser)
- **Timestamp:** 2026-10-01T09:47:00+07:00
- **Status:** COMPLETED

---

## 1. Scope & Files Changed
- `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`:
  - Added `RestoreItemResult(val purchaseToken: String, val status: String, val reason: String? = null)`.
  - Added `results: List<RestoreItemResult> = emptyList()` to `RestoreResult.Success`, `RestoreResult.Partial`, and `RestoreResult.Rejected`.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - `parseEntitlementStrict`:
    - Strict `source` validation: empty/blank `source` logs an error and returns `null` (no fallback to default).
    - Strict `ownerAppUserId`: resolved from `entObj` or `snapshotOwner`; if missing from both, fails closed (`null`). Never synthesizes owner from `request.ownerAppUserId`.
    - State-based expiry validation: only `VERIFIED_ACTIVE`, `CANCELED_ACTIVE`, and `IN_GRACE_PERIOD` subscriptions require `expiryTimeMillis > 0L`. Canceled pending tombstones (`REVOKED`) accept 0 or null expiry.
  - `parseRestoreResponse`:
    - Validates `snapshotObj.ownerAppUserId` strictly; rejects payload with `TransientError` if missing or blank.
    - Parses itemized `results[]` into `List<RestoreItemResult>` and attaches them to `RestoreResult`.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Calculates `failedCount` and active restored count using itemized `results` from `RestoreResult` instead of hardcoding `failedCount = 1`.
- `app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`:
  - Updated `response()` test fixture helper to include `"source":"GOOGLE_PLAY_SUBSCRIPTION"`.

---

## 2. Test Execution & Evidence

### Round 7 Android Probes
```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound7RegressionTest --offline --console=plain
```
- **Passed Probes:**
  - `D01MissingRequiredSourceMustNotBecomeAcceptedPlayEntitlement`: **PASS**
  - `D02MissingSnapshotAndItemOwnerMustNotBeSynthesizedFromRequest`: **PASS**
  - `D03CanceledPendingTombstoneMustNotRejectValidLinkedActiveSnapshot`: **PASS**
  - `D04ControlCompleteActivePayloadRemainsAccepted`: **PASS**
  - `A04ParserMustPreserveActualPerTokenFailureCount`: **PASS**
  - `A05CachedUnresolvedEntitlementMustNotCountAsFreshRestoreSuccess`: **PASS**
  - `A08ControlSuccessfulRestoreUpdatesProfileAndBillingFlags`: **PASS**
  - `C06ControlUnexpiredTokenIsReady`: **PASS**
- Total passing probes in Round 7 suite increased from 3 to **8**.

### Previous Rounds Regression Suites
```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound5RegressionTest --tests com.tscanner.app.VipRound6RegressionTest --offline --console=plain
```
- **Result:** BUILD SUCCESSFUL (all 16 Round 5 probes and 12 Round 6 probes PASS).

---

## 3. Next Package
- Proceed directly to **X06** — Credential thật, default model và readiness (F07 phần preflight).
