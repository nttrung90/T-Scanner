# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y00 (Baseline & Regression Suite Porting)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Chuyển tự động sang Y01  
**Phạm vi:** Tests, docs, progress. Không thay đổi bất kỳ file production/resource/config nào.

---

## 1. Mục tiêu & Hợp đồng Y00

- Xác thực baseline hệ thống hiện hành: Android 964 unit tests, backend 108 tests, R7 probes (18 Android + 9 backend), `lintDebug` (0 errors), `assembleDebug` (BUILD SUCCESSFUL).
- Chuyển toàn bộ 33 probes vòng 8 vào regression suites bền vững:
  - 13 backend probes -> `backend/billing-verifier/test/round8-regression.test.ts`
  - 17 Android probes (A801–A812 operations/lifecycle + C801–C803, C806, C808 auth/transport) -> `app/src/test/java/com/tscanner/app/VipRound8RegressionTest.kt`
  - 3 Android SDK integration probes (C804, C805, C807) đi qua production coordinator -> real BillingManager -> BillingFlowParams -> FakeBillingClient giữ trong isolated job với `AuditTextUtils.kt` qua `docs/vip-round8-20261001/audit.init.gradle` để bảo toàn fixture integrity mà không làm ô nhiễm global normal test sourceSet.
- Xác nhận trạng thái pre-fix chuẩn: **23 FAIL / 10 PASS** trên 33 probes (14 Android FAIL / 6 Android PASS; 9 backend FAIL / 4 backend PASS), không có SDK fixture exception.

---

## 2. Kết quả kiểm tra Baseline & Regression

| Bộ kiểm tra | Lệnh thực hiện | Kết quả | Ghi chú |
|---|---|---|---|
| Android Baseline Suite | `./gradlew.bat :app:testDebugUnitTest --offline --console=plain` | **964 tests PASS / 0 FAIL** | 115 test classes |
| Backend Baseline Suite | `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts` | **108 tests PASS / 0 FAIL** | 14 test suites |
| Original R7 Android Probes | `./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain` | **18 tests PASS / 0 FAIL** | Toàn bộ invariant R7 giữ vững |
| Original R7 Backend Probes | `node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts` | **9 tests PASS / 0 FAIL** | B701–B711 PASS |
| Android Lint & Build | `./gradlew.bat :app:lintDebug :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL** | 0 errors, 757 warnings |
| Backend R8 Regression Suite | `node --experimental-strip-types --test backend/billing-verifier/test/round8-regression.test.ts` | **9 FAIL / 4 PASS** (Tổng 13) | B801–B806, B809, B810, B812 FAIL; B807, B808, B811, B813 PASS |
| Android R8 Normal Regression | `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound8RegressionTest --offline --console=plain` | **12 FAIL / 5 PASS** (Tổng 17) | A801–A808, A812, C801–C803 FAIL; A809–A811, C806, C808 PASS |
| Android R8 Isolated SDK Probes | `./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.RootRound8AuthAuditTest --offline --console=plain` | **2 FAIL / 1 PASS** (Tổng 3) | C804, C805 FAIL; C807 PASS |
| **Tổng Probes R8 mới** | Tổng hợp 33 probes | **23 FAIL / 10 PASS** | Đúng 100% đối chiếu audit độc lập |

---

## 3. Danh mục Files thay đổi / bổ sung trong Y00

- `backend/billing-verifier/test/round8-regression.test.ts` (mới): Port 13 probes B801–B813 vào test suite của backend billing verifier.
- `app/src/test/java/com/tscanner/app/VipRound8RegressionTest.kt` (mới): Port 17 probes Android A801–A812 và C801–C803, C806, C808 vào test sourceSet chính thức của Android.
- `REPORT_VIP_R8_Y00.md` (mới): Báo cáo checkpoint Y00.
- `PROGRESS_VIP_R8_AUTORUN.md` (mới): Sổ theo dõi tiến độ tổng thể các gói Y00–Y13.

Production source: **0 file sửa đổi** (Giữ nguyên vẹn mọi thay đổi uncommitted trước đó).

---

## 4. Xác nhận Fixture & Bắt lỗi Production

- **B810 capture lỗi production**: Bắt chính xác `ERR_INVALID_ARG_TYPE: Provided value cannot be bound to SQLite parameter 4` tại `sqliteDriver.ts:205` khi Google SubscriptionNotification không mang `subscriptionId`.
- **C804/C805/C807 fixture**: Không có exception giả lập từ mock TextUtils trong test isolated runner; C807 PASS và C804/C805 fail chính xác tại assertion nghiệp vụ purchase context leak / credential expiry.
- **A801/A812 invariants**: Được giữ nguyên vẹn: pending không được relabel thành full success, và cached entitlement không được tính vào fresh restore count.

---

## 5. Bước kế tiếp

Tự động chuyển sang gói **Y01** (RTDN hoàn tất linked work trước khi consumed, durable retry — G01).
