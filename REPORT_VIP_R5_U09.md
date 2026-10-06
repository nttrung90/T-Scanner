# Báo cáo U09 — Regression tổng hợp & kiểm tra độc lập (Full 16 Probes + Suites)

## 1. Mục tiêu và phạm vi
- Thực hiện kiểm tra toàn diện độc lập (end-to-end regression testing):
  - Chạy toàn bộ 16 probes cố định (7 backend probes trong `round5-regression.test.ts` và 9 Android probes trong `VipRound5RegressionTest.kt`).
  - Chạy toàn bộ test suites hiện có của backend billing-verifier (96 tests).
  - Chạy toàn bộ test suites hiện có của Android unit tests.
  - Chạy kiểm tra tĩnh `lintDebug` và build artifact `assembleDebug`.
  - Đảm bảo 100% test xanh mà không hạ thấp bất kỳ assertion nào, bảo toàn toàn bộ sửa đúng từ các vòng trước.

## 2. Kết quả kiểm tra tổng hợp

### A. Focused Probes Backend (`round5-regression.test.ts`)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/round5-regression.test.ts`
- Kết quả: **7/7 PASS (100%)**
  - ✔ B07: `late RTDN must not overwrite newer verify expiry`
  - ✔ B01: `first-binding late active must not resurrect committed expiry`
  - ✔ B02: `conflict must not discard authoritative expiry and return active`
  - ✔ B03: `on-hold V2 must invalidate stored active grant`
  - ✔ B04: `missing V2 lifecycle state must not grant active`
  - ✔ B05: `client metadata must not suppress refresh of known refunded receipt`
  - ✔ B06: `control empty restore refreshes refund with no conflicting metadata`

### B. Toàn Bộ Suite Backend (`backend/billing-verifier/test/*.test.ts`)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **96/96 PASS (100%), 0 FAIL, 0 CANCELLED** across:
  - `round5-regression.test.ts`
  - `round4-regression.test.ts`
  - `round2-regression.test.ts`
  - `verifier.test.ts`
  - `storage.test.ts`
  - `restore-revocation.test.ts`
  - `rtdn_and_lifecycle.test.ts`
  - `rtdn-recovery.test.ts`
  - `pubsubAuth.test.ts`
  - `googlePlayTransport.test.ts`
  - `authMiddleware.test.ts`

### C. Focused Probes Android (`VipRound5RegressionTest.kt`)
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound5RegressionTest --offline --console=plain`
- Kết quả: **9/9 PASS (100%), 0 FAIL**
  - ✔ `A01RestoreWrongOwnerMustFail`
  - ✔ `A02RestoreUnknownSkuMustFail`
  - ✔ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty`
  - ✔ `A04Http400MustNotAcceptSuccessBody`
  - ✔ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal`
  - ✔ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation`
  - ✔ `A07ControlValidRestoreParses`
  - ✔ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile`
  - ✔ `A09ExpiredSessionMustOfferReauthentication`

### D. Toàn Bộ Android Unit Tests
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL, 100% PASS, 0 FAIL** across all test classes.

### E. Kiểm tra Lint & Assemble Debug
- Lệnh: `./gradlew.bat :app:lintDebug :app:assembleDebug --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, không có lỗi biên dịch hoặc lint blocker nào.

## 3. Tổng kết chuyển tiếp
- Toàn bộ 16/16 probe regression và tất cả các suite kiểm thử backend / client đã đạt trạng thái xanh tuyệt đối.
- Chuyển tiếp sang **U10 — Các gate thiết bị / môi trường thật**.
