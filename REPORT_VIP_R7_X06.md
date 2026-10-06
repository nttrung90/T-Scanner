# Checkpoint Report X06 — Real Credentials, Default Model & Auth Readiness Preflight

- **Package:** X06 — Credential thật, default model và readiness (F07 phần preflight)
- **Timestamp:** 2026-10-01T09:51:00+07:00
- **Status:** COMPLETED

---

## 1. Scope & Files Changed
- `app/src/main/java/com/tscanner/app/data/model/UserProfile.kt`:
  - Reset default `idToken` from `"mock_valid_token"` to `null`.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Updated `isTokenExpired`: Fails closed on null/blank, non-JWT shape (not 3 dot-delimited parts), corrupted base64 payloads, missing `exp` claim, non-positive `exp`, or expired tokens.
  - Updated `isAuthReady`: Requires non-null `tokenProvider`, non-blank token, and `!isTokenExpired(token)`.
- `app/src/test/java/com/tscanner/app/BillingReadinessTest.kt`:
  - Provided explicit synthetic unexpired JWT for test `loggedInUser`.
- `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt`:
  - Provided explicit synthetic unexpired JWT for test `loggedInUser`.

---

## 2. Test Execution & Evidence

### Round 7 Android Probes
```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound7RegressionTest --offline --console=plain
```
- **Passed Probes:**
  - `C01DefaultProfileMustNotInventCredential`: **PASS**
  - `C02MalformedProductionTokenMustNotBeAuthReady`: **PASS**
  - `C03JwtWithoutExpiryMustNotBeAuthReady`: **PASS**
  - `C06ControlUnexpiredTokenIsReady`: **PASS**
  - Probes from X05 (`D01`, `D02`, `D03`, `D04`, `A04`, `A05`, `A08`) continue to pass cleanly.
- Total passing probes in Round 7 suite reached **11/18**.

### Readiness and Purchase Coordinator Suites
```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingReadinessTest --tests com.tscanner.app.VipPurchaseActionCoordinatorTest --offline --console=plain
```
- **Result:** BUILD SUCCESSFUL (all unit tests PASS).

---

## 3. Next Package
- Proceed directly to **X07** — Restore transport guard và auth recovery (F08, F07 phần restore).
