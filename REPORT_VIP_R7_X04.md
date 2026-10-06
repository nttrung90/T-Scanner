# Checkpoint Report X04 — Backend Restore Contract & Per-Token Semantics

- **Package:** X04 — Backend restore contract đầy đủ theo từng token (F06 backend)
- **Timestamp:** 2026-10-01T09:41:35+07:00
- **Status:** COMPLETED

---

## 1. Scope & Files Changed
- `backend/billing-verifier/src/types.ts`:
  - Expanded `RestoreResponse.status` to include `'REJECTED'` (`'SUCCESS' | 'TRANSIENT_ERROR' | 'PARTIAL' | 'REJECTED'`).
  - Expanded `RestoreResponse.results[].status` to include `'PENDING'` (`'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED' | 'PENDING'`).
- `backend/billing-verifier/src/verifier.ts`:
  - Preserved itemized `verifyRes.status === 'PENDING'` by pushing `{ purchaseToken: p.purchaseToken, status: 'PENDING' }`.
  - Aggregated overall status to `'REJECTED'` if all candidate purchases were rejected as unknown/invalid tokens (`results.every(r => r.status === 'REJECTED' && r.reason === 'INVALID_SIGNATURE_OR_TOKEN')`).
  - Aggregated overall status to `'PARTIAL'` if candidate purchases contain `'PENDING'` status.

---

## 2. Test Execution & Evidence

### Round 7 Backend Probes
```powershell
node --experimental-strip-types --test backend/billing-verifier/test/round7-regression.test.ts
```
- **Output:**
  - `✔ B701 first-bind linked query must not overwrite a newer authoritative EXPIRED receipt`
  - `✔ B702 canceled yearly upgrade must recover authoritative linked monthly receipt`
  - `✔ B703 unresolved linked upstream failure must not be reported as complete restore SUCCESS`
  - `✔ B704 RTDN after a real pending verification must refresh the canceled purchases linked old receipt`
  - `✔ B705 all-pending restore must preserve per-token PENDING semantics`
  - `✔ B706 all unknown rejected restore candidates must not be reported as full SUCCESS`
  - `✔ B709 control same-SKU linked ACTIVE receipt is resolved and owned correctly`
  - `✔ B710 control linked receipt already owned by another account is preserved`
  - `✔ B711 linked first-bind race must remain protected across two SQLite connections and restart`
- **Result:** 9/9 PASS (100%), 0 FAIL.

### Full Backend Suite
```powershell
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
```
- **Result:** 108/108 PASS, 0 FAIL.

---

## 3. Contract Decisions
- Per-token payment status `PENDING` is preserved directly in `results[].status` instead of being lumped into `REJECTED`.
- In line with B706 policy decisions, restore operations where all submitted candidates are 404/unknown to Google Play return aggregate `status: 'REJECTED'`, while existing user query semantics for non-conflicting accounts remain preserved.

---

## 4. Next Package
- Proceed directly to **X05** — Android parser theo state, strict required fields và metadata (F09, F06 parser).
