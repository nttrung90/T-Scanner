# Báo cáo U06 — Guard phiên và vòng đời restore sau await (F05: A05)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F05 (probe A05) theo kế hoạch Round 5:
  - File: `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Vấn đề: Trong quá trình restore (`verifier.restorePurchases`), coroutine bất đồng bộ await phản hồi từ server. Khi tài khoản người dùng thay đổi (ví dụ switch user từ A sang B, hoặc đổi session generation) trong lúc đang await, sau khi resume coroutine lại không kiểm tra lại tính hợp lệ của phiên (`isStale`). Hệ quả là kết quả restore cũ của user A bị ghi đè vào store của user A (hoặc làm sai lệch trạng thái của user B) và phát callback UI sai.
  - Khắc phục: Chụp lại `initialUserId` và `initialGen` trước khi suspend; ngay sau khi `verifier.restorePurchases` hoàn tất, lập tức kiểm tra:
    - `operationContext.isStale(currentUserId, currentGen)`
    - `currentUserId != initialUserId`
    - `currentGen != initialGen`
    Nếu phiên đã đổi, huỷ bỏ ngay lập tức (`return@launch`), không gọi `store.applySnapshot`, không cập nhật `SharedPreferences`, không project profile và không kích hoạt callback.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (`app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`)
- Trước U06: 9 tests (5 PASS, 4 FAIL).
- Sau U06: **9 tests (6 PASS, 3 FAIL)**
  - ✔ `A01RestoreWrongOwnerMustFail` (**PASS từ U05**)
  - ✔ `A02RestoreUnknownSkuMustFail` (**PASS từ U05**)
  - ✔ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty` (**PASS từ U05**)
  - ✔ `A04Http400MustNotAcceptSuccessBody` (**PASS từ U05**)
  - ✔ `A07ControlValidRestoreParses` (**PASS đối chứng**)
  - ✔ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal` (**ĐÃ SỬA - PASS**)
  - ✖ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation` (FAIL - Mục tiêu của U07)
  - ✖ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile` (FAIL - Mục tiêu của U07)
  - ✖ `A09ExpiredSessionMustOfferReauthentication` (FAIL - Mục tiêu của U08)

### Suite Kiểm thử Reconciliation Hiện Có
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingReconciliationTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL, 100% PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- `BillingReconciliation.kt`: Mọi side effect (ghi store, lưu preferences, project sang `AppAuthManager`, gọi callback `onResult`) sau khi await tác vụ mạng từ `verifier.restorePurchases` đều phải đi qua chốt chặn stale session guard.
- Ngăn chặn hoàn toàn hiện tượng late restore callback làm hỏng tính toàn vẹn giữa các phiên tài khoản.

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao luồng vòng đời coroutine đã được guard an toàn cho **U07 — Một snapshot đã commit cho restore, profile & tính năng (F06, F07: A06, A08)**.
