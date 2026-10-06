# Báo cáo W00 — Baseline và regression bền vững (Round 6)

## 1. Mục tiêu và phạm vi
- Thiết lập bộ regression test vĩnh viễn cho Vòng 6:
  - Backend: `backend/billing-verifier/test/round6-regression.test.ts` (3 tests: B01, B02, B03)
  - Android: `app/src/test/java/com/tscanner/app/VipRound6RegressionTest.kt` (9 tests: A01–A09)
- Xác nhận trạng thái Red Baseline trước khi sửa đổi bất kỳ mã nguồn production nào:
  - 12 probes Vòng 6: Dự kiến chính xác 10 FAIL / 2 PASS.
  - 16 probes Vòng 5: Đảm bảo 100% 16/16 PASS (không có hồi quy từ vòng trước).
  - Không sửa đổi mã nguồn production trong gói W00.

## 2. Kết quả kiểm tra trước sửa đổi (Baseline Evidence)

### A. Probes Vòng 6 Backend (`round6-regression.test.ts`)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/round6-regression.test.ts`
- Kết quả: **3 tests (1 PASS, 2 FAIL)**
  - ✖ `B01 unresolved known receipt must not return fresh full restore success` (FAIL)
  - ✖ `B02 canonical pending-purchase-canceled state must be parsed for linked-token recovery` (FAIL)
  - ✔ `B03 control mixed restore accurately declares PARTIAL on transient refresh` (PASS đối chứng)

### B. Probes Vòng 6 Android (`VipRound6RegressionTest.kt`)
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest --offline --console=plain`
- Kết quả: **9 tests (1 PASS, 8 FAIL)**
  - ✖ `A01PendingPlayPurchaseMustNotSuppressKnownAccountRefresh` (FAIL)
  - ✖ `A02NonemptyPlayListMustStillRefreshOtherServerReceipts` (FAIL)
  - ✖ `A03PartialMustNotBePresentedAsCompleteRestore` (FAIL)
  - ✖ `A04DestroyedManagerMustNotCommitSuspendedRestore` (FAIL)
  - ✖ `A05ProjectionFailureMustNotProduceSuccessfulRestore` (FAIL)
  - ✖ `A06MissingIdTokenMustRequestReauthentication` (FAIL)
  - ✖ `A07RestoreWrongCatalogTypeMustFail` (FAIL)
  - ✖ `A08RestoreWrongProviderSourceMustFail` (FAIL)
  - ✔ `A09ControlGoodRestoreUpdatesWatermarkGate` (PASS đối chứng)

### C. Probes Vòng 5 Cố Định (Bảo Toàn Hồi Quy)
- Backend (`round5-regression.test.ts`): **7/7 PASS (100%)**
- Android (`VipRound5RegressionTest.kt`): **9/9 PASS (100%)**
- Tổng probe Vòng 5: **16/16 PASS (100%)**

### D. Bản đồ Probe ↔ Yêu cầu lỗi (R01–R07)
| Probe | Loại | Lỗi đối ứng | Mô tả ngắn |
|---|---|---|---|
| **B01** | Backend | **R02** | Unresolved known receipt (404) trả về fresh SUCCESS chứa stale ACTIVE |
| **B02** | Backend | **R07** | Subscriptions V2 `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` bị throw 400 |
| **B03** | Backend | Đối chứng R02 | Mixed restore khai báo PARTIAL chính xác khi có lỗi transient |
| **A01** | Android | **R01** | Local pending bỏ qua refresh server receipts đã biết |
| **A02** | Android | **R01** | Sync khi Play có item không rỗng bỏ qua các server-only receipts |
| **A03** | Android | **R02** | Restore Partial bị trình bày thành Restored đầy đủ |
| **A04** | Android | **R03** | Restore outlived manager scope và commit sau khi manager đã destroy |
| **A05** | Android | **R04** | Lỗi ghi profile bị bỏ qua, vẫn báo khôi phục thành công |
| **A06** | Android | **R05** | User có ID token null rơi xuống ActivateVip thay vì RequestSignIn |
| **A07** | Android | **R06** | Restore nhận sai productType (inapp cho yearly subscription) |
| **A08** | Android | **R06** | Restore nhận sai provider source (PROMOTIONAL thay vì Play) |
| **A09** | Android | Đối chứng | Restore hợp lệ cập nhật Watermark gate chính xác |

## 3. Handoff cho Gói Tiếp Theo
- Đã xác lập baseline bền vững với Red Evidence xuất phát 100% từ production logic.
- Tự động chuyển sang **W01 — Lifecycle canceled pending và linked receipt (R07: B02)**.
