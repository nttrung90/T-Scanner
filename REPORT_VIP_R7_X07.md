# Checkpoint Report X07 — Restore Transport Guard & Auth Recovery Flow

- **Package:** X07 — Restore transport guard và auth recovery (F08, F07 restore)
- **Timestamp:** 2026-10-01T09:55:30+07:00
- **Status:** COMPLETED

---

## 1. Scope & Files Changed
- `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`:
  - Added `RestoreResult.AuthRequired(val message: String)`.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Enforced `isBackendConfigured()` check prior to initiating network transport in `restorePurchases`.
  - Added session token availability check prior to transport.
  - Mapped HTTP 401 responses explicitly to `RestoreResult.AuthRequired` with the server's explanatory message (instead of generic transient 503).
  - Mapped HTTP 403 responses to `RestoreResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT, msg)`.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Added `ReconciliationResult.AuthRequired(val message: String)`.
  - Propagated `RestoreResult.AuthRequired` directly as `ReconciliationResult.AuthRequired`.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Handled `ReconciliationResult.AuthRequired` by passing the authentication error message directly to restore UI callbacks without mislabeling as Google Play network 503 errors.

---

## 2. Test Execution & Evidence

### Round 7 Android Probes
```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound7RegressionTest --offline --console=plain
```
- **Passed Probes:**
  - `C04RestoreMustEnforceHttpsBeforeSendingBearer`: **PASS** (Zero transport calls made when URL is insecure `http://`)
  - `C05Restore401MustExposeAuthenticationRecovery`: **PASS** (Callback message exposes session authentication failure rather than generic Play 503 error)
  - All previously passing probes (`C01`, `C02`, `C03`, `C06`, `D01`, `D02`, `D03`, `D04`, `A04`, `A05`, `A08`) continue to pass.
- Total passing probes in Round 7 suite reached **13/18**.

---

## 3. Next Package
- Proceed directly to **X08** — Owner/generation trước await và cancellation sau blocking HTTP (F04).
