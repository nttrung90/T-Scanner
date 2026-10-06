# REPORT VIP BILLING ROUND 2 — PACKAGE Q10

**Package ID:** Q10  
**Defect Resolved:** Defect R01 (Triển khai Google Play Developer API HTTP Transport Adapter)  
**Date:** 2026-09-26  
**Status:** VERIFIED & COMPLETE  

---

## 1. Executive Summary

Defect R01 noted that previous server-side code used placeholder stubs (`throw new Error('Not implemented: requires live Google Service Account')`) rather than an executable HTTP transport implementation communicating with the Google Play Developer API (Android Publisher v3).

In Package Q10:
1. **Android Publisher v3 HTTP Adapter:** Implemented `ProductionGooglePlayBillingApi` with executable HTTP transport methods calling:
   - Subscription purchases: `GET /androidpublisher/v3/applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}`
   - One-time in-app purchases: `GET /androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{token}`
   - Subscription acknowledge: `POST /androidpublisher/v3/applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}:acknowledge`
   - In-app product acknowledge: `POST /androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{token}:acknowledge`
2. **Server-Side ADC / OAuth2 Token Minting:** Built OAuth2 Service Account assertion exchange using RSA-SHA256 (`node:crypto.createSign`), exchanging with Google OAuth token endpoint (`https://oauth2.googleapis.com/token`) and caching valid access tokens.
3. **Transport Error Mapping:** Mapped HTTP 401/403 (unauthorized/forbidden), 404 (not found), 429 (rate-limited / transient), 5xx (server error / transient), and malformed JSON to `GooglePlayApiError` with appropriate `isTransient` classification.
4. **Idempotent Acknowledge:** Mapped HTTP 400 responses with `"The purchase has already been acknowledged"` to idempotent already-acknowledged status.
5. **Catalog Aliases Alignment:** Unified server-side allowlists with Android catalog (`ALLOWED_SUBSCRIPTION_IDS` includes `tscanner_vip_yearly`, `tscanner_vip_monthly`, `vip_yearly`, `vip_monthly`; `ALLOWED_INAPP_IDS` includes `tscanner_vip_lifetime`, `vip_lifetime`).

---

## 2. Changes Made

### Files Modified:
- `backend/billing-verifier/src/googlePlayClient.ts`:
  - Replaced stubs with full HTTP transport adapter for Android Publisher v3.
  - Added support for `tokenProvider`, `fetchFn`, and `baseUrl` for robust dependency injection and unit/contract testing.
- `backend/billing-verifier/src/verifier.ts`:
  - Updated `ALLOWED_SUBSCRIPTION_IDS` and `ALLOWED_INAPP_IDS` to include legacy aliases.
  - Exported `PRODUCT_ALIASES` mapping.

### Files Created:
- `backend/billing-verifier/test/googlePlayTransport.test.ts`:
  - 6 dedicated test cases covering:
    1. `Production client without credentials throws MissingCredentialsError`
    2. `HTTP Transport - Request path, package, token, and Bearer auth`
    3. `HTTP Transport - In-app product mapping`
    4. `HTTP Transport - Error mapping (401, 403, 404, 429, 503, malformed)`
    5. `HTTP Transport - Acknowledge endpoints and idempotent already-acknowledged`
    6. `Catalog aliases consistency with Android catalog`

---

## 3. Verification Evidence

### 3.1 Q10 Dedicated Test Suite (`test/googlePlayTransport.test.ts`)
```
✔ Q10: Production client without credentials throws MissingCredentialsError (1.8623ms)
✔ Q10: HTTP Transport - Request path, package, token, and Bearer auth (41.8621ms)
✔ Q10: HTTP Transport - In-app product mapping (11.3171ms)
✔ Q10: HTTP Transport - Error mapping (401, 403, 404, 429, 503, malformed) (16.02ms)
✔ Q10: HTTP Transport - Acknowledge endpoints and idempotent already-acknowledged (13.484ms)
✔ Q10: Catalog aliases consistency with Android catalog (0.2276ms)
ℹ tests 6
ℹ suites 0
ℹ pass 6
ℹ fail 0
```

### 3.2 Regression Suite (`test/round2-regression.test.ts`)
```
✔ probe RTDN retries same event after temporary Google API failure (3.2446ms)
✔ probe authoritative expired verification must not restore stale active receipt (1.4884ms)
✔ probe HTTP restore endpoint must require authentication (72.1483ms)
✔ control ownership conflict rejected within one store instance (1.1837ms)
ℹ tests 4
ℹ suites 0
ℹ pass 4
ℹ fail 0
```

### 3.3 Full Backend Test Suite
```
ℹ tests 64
ℹ suites 0
ℹ pass 64
ℹ fail 0
```

---

## 4. Handoff to Q11
Package Q10 is complete and verified. Ready to proceed to **Q11: Android HTTP verifier, readiness và parity test/release (phần client R01/R11)**.
