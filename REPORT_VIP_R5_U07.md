# Báo cáo U07 — Một snapshot đã commit cho restore, profile & tính năng (F06/F07: A06, A08)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F06 và F07 (các probe A06, A08) theo kế hoạch Round 5:
  - File: `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Vấn đề:
    - F06 (A06): Khi restore trả về một snapshot cũ hơn (ví dụ snapshot active có version 9, trong khi store đã có bản ghi REVOKED version 10), `store.applySnapshot` giữ lại bản ghi REVOKED theo đúng monotonic versioning. Tuy nhiên, reconciler lại lấy snapshot từ response mạng (`restoreResult.snapshot`) để đánh giá `isVipActive()` và gán cờ `KEY_BILLING_VIP_ACTIVE=true`, đồng thời phát sự kiện `Restored` sai sự thật.
    - F07 (A08): Khi restore trả về `RestoreResult.Partial`, reconciler không gọi `AppAuthManager.applyEntitlementSnapshot`. Do đó quyền VIP đã persist trong store không được project vào profile trong bộ nhớ của người dùng, khiến các tính năng như đóng dấu bản quyền (`WatermarkHelper`) và sao lưu Google Drive (`GoogleDriveBackupWorker`) vẫn coi tài khoản là Free dù store đã có quyền. Ngoài ra, nhánh Partial trước đây phát `Restored` ngay cả khi số lượng active entitlement bằng 0.
  - Khắc phục:
    - Dùng `store.applySnapshotTyped(context, incomingSnapshot)` để lấy `committedSnapshot` đã merge và commit làm Single Source of Truth duy nhất cho cả `RestoreResult.Success` và `RestoreResult.Partial`.
    - Kiểm tra kết quả commit: nếu có xung đột hoặc lỗi lưu (`Conflict`, `PersistenceFailed`), trả về `ReconciliationResult.ProcessingFailed`, không giả mạo thành công.
    - Project `committedSnapshot` vào `AppAuthManager` cho cả `Success` và `Partial` (`AppAuthManager.applyEntitlementSnapshot(context, committedSnapshot)`).
    - Cập nhật cờ `KEY_BILLING_VIP_ACTIVE` và callback `onResult` hoàn toàn dựa trên `committedSnapshot.isVipActive()`. Nếu `isVipActive() == false`, phát `ReconciliationResult.NoActivePurchases`.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (`app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`)
- Trước U07: 9 tests (6 PASS, 3 FAIL).
- Sau U07: **9 tests (8 PASS, 1 FAIL)**
  - ✔ `A01RestoreWrongOwnerMustFail` (**PASS từ U05**)
  - ✔ `A02RestoreUnknownSkuMustFail` (**PASS từ U05**)
  - ✔ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty` (**PASS từ U05**)
  - ✔ `A04Http400MustNotAcceptSuccessBody` (**PASS từ U05**)
  - ✔ `A07ControlValidRestoreParses` (**PASS đối chứng**)
  - ✔ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal` (**PASS từ U06**)
  - ✔ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation` (**ĐÃ SỬA - PASS**)
  - ✔ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile` (**ĐÃ SỬA - PASS**)
  - ✖ `A09ExpiredSessionMustOfferReauthentication` (FAIL - Mục tiêu của U08)

### Suite Kiểm thử Reconciliation và EntitlementStore
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingReconciliationTest --tests com.tscanner.app.BillingEntitlementStoreTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL, 100% PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- `BillingReconciliation.kt`: Thiết lập snapshot đã merge/commit (`committedSnapshot`) làm nguồn chân lý duy nhất cho:
  - Giá trị `KEY_BILLING_VIP_ACTIVE` trong SharedPreferences.
  - Hồ sơ người dùng trong `AppAuthManager` (được sử dụng bởi Watermark và Google Drive Backup).
  - Kết quả trả về qua `onResult` (chỉ phát `Restored` khi `committedSnapshot.isVipActive() == true`).

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao tầng reconciliation và profile projection nhất quán cho **U08 — Hết hạn phiên có đường re-auth rõ (F08: A09)**.
