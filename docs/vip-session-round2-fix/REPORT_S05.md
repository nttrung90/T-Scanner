# Báo Cáo Kiểm Tra & Triển Khai Gói S05 (Khóa Terminal Theo Từng Thao Tác Mua — G04)

**Ngày thực hiện:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** S05 (Theo `PLAN_FIX_VIP_SESSION_ROUND2_GEMINI_2026-10-04.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khắc phục triệt để khiếm khuyết G04: Không dùng `AtomicBoolean` cờ đơn toàn cục (`isProcessing`) làm identity duy nhất cho thao tác mua.
  - Cung cấp monotonic per-operation state (`OperationState`) với `operationId` định danh độc lập cho từng lần nhấn "Mua VIP".
  - Khóa terminal guard (`isTerminal.compareAndSet(false, true)`) đồng nhất cho toàn bộ các nhánh kết thúc: `onLaunchSuccess`, `onError`, `onRequestSignIn`, `onShowSignInPrompt`, và lỗi đồng bộ `launchBillingFlow`.
  - Loại bỏ hoàn toàn khả năng callback trễ hoặc callback trùng lặp từ kết nối (`startConnection`) hay truy vấn sản phẩm (`queryProducts`) làm reset trạng thái bận của thao tác mới hoặc kích hoạt flow thanh toán thừa (`launchBillingFlow`).
- **Phạm vi file:**
  - Production: `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`
  - Unit Tests: `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt`
  - Probe Verification: `docs/vip-session-reaudit-20261004/VipSessionReauditProbeTest.kt` (P04 & P05 chuyển từ RED sang GREEN).

---

## 2. Bằng Chứng Trước & Sau Khi Sửa (Evidence)

### 2.1. Trước khi sửa (Baseline S04 Checkpoint)
- Khi chạy `VipSessionReauditProbeTest`:
  - `P04_duplicateProductCallbackMustNotLaunchTwice`: **FAILED** (Callback `queryProducts` bị kích hoạt 2 lần dẫn tới `launcher.launches == 2` và 2 terminal events).
  - `P05_oldProductCallbackMustNotFinishNewAction`: **FAILED** (Callback trễ từ action cũ bị kích hoạt sau khi action mới đã bắt đầu, làm xóa cờ bận của action mới và kích hoạt launch thừa).

### 2.2. Sau khi sửa (S05 Implementation)
- Cấu trúc `OperationState`:
  ```kotlin
  private class OperationState(
      val operationId: Long,
      val initialUserId: String?,
      val initialGen: Long,
      val hasSignInCallback: Boolean
  ) {
      val connectHandled = AtomicBoolean(false)
      val queryHandled = AtomicBoolean(false)
      val launchedFlow = AtomicBoolean(false)
      val isTerminal = AtomicBoolean(false)
  }
  ```
- Terminal guard thống nhất:
  ```kotlin
  private fun terminate(
      state: OperationState,
      terminalBlock: () -> Unit
  ) {
      if (activeOperationId.get() != state.operationId) return
      if (state.isTerminal.compareAndSet(false, true)) {
          activeOperationId.compareAndSet(state.operationId, 0L)
          terminalBlock()
      }
  }
  ```
- Mọi callback (`startConnection`, `queryProducts`, `launchBillingFlow`) đều kiểm tra `activeOperationId.get() == state.operationId` và các cờ `connectHandled`, `queryHandled`, `launchedFlow` trước khi xử lý tiếp.
- Kết quả chạy probe:
  - `P04_duplicateProductCallbackMustNotLaunchTwice`: **PASSED** (1 launch, 1 terminal duy nhất).
  - `P05_oldProductCallbackMustNotFinishNewAction`: **PASSED** (action mới vẫn giữ `isActionInProgress() == true`, 0 launch từ action cũ).
  - `P01`: **PASSED**
  - `P02`: **PASSED**
  - `P06`: **PASSED**
  - `C01`: **PASSED**
  - `C02`: **PASSED**
  - (Chỉ còn P03 FAILED theo thiết kế: synthetic unbound probe đã được giải thích trong S02).

---

## 3. Các File Đã Thay Đổi

1. `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`:
   - Thêm `currentOperationId = AtomicLong(0L)` và `activeOperationId = AtomicLong(0L)`.
   - Thêm private class `OperationState`.
   - Triển khai hàm bảo vệ `terminate(state, terminalBlock)`.
   - Bổ sung kiểm tra `operationId` và `AtomicBoolean` guard tại các điểm nối callback bất đồng bộ: `startConnection`, `queryProducts`, và `launchBillingFlow`.
2. `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt`:
   - Bổ sung 4 bài test hồi quy chuyên biệt:
     - `testUpgrade_duplicateProductCallback_mustNotLaunchTwiceAndTerminalExactlyOnce`
     - `testUpgrade_lateProductCallbackFromOldAction_mustNotClearNewActionBusyOrLaunch`
     - `testUpgrade_duplicateStartConnectionCallback_executesFlowOnlyOnce`
     - `testUpgrade_lateErrorOrAuthCallbackAfterLaunchSuccess_doesNotEmitDuplicateTerminal`

---

## 4. Kết Quả Kiểm Thử Thực Tế (Test Execution & Commands)

1. **Focused Coordinator Tests:**
   - Command:
     ```powershell
     .\gradlew.bat :app:testDebugUnitTest --tests "*VipPurchaseActionCoordinatorTest*" --offline --console=plain
     ```
   - Kết quả: **BUILD SUCCESSFUL**, 15/15 tests PASS (0 failures, 0 errors).
2. **Reaudit Probes:**
   - Command:
     ```powershell
     .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests "com.tscanner.app.VipSessionReauditProbeTest" --console=plain
     ```
   - Kết quả: P01 PASS, P02 PASS, P04 PASS, P05 PASS, P06 PASS, C01 PASS, C02 PASS. (P04 và P05 chính thức GREEN).

---

## 5. Tiêu Chí Nghiệm Thu (Acceptance)

- [x] Mỗi thao tác mua có `operationId` và `OperationState` độc lập.
- [x] Đảm bảo tối đa 1 lần gọi `launchBillingFlow` và 1 terminal event duy nhất cho mỗi thao tác.
- [x] Callback trễ từ thao tác cũ không thể xóa trạng thái bận (`isActionInProgress`) của thao tác mới.
- [x] Callback lặp (duplicate callback) từ Google Play Billing client bị nuốt an toàn bởi atomic CAS.
- [x] Toàn bộ test bộ điều phối (15/15) và probes (P04, P05) đều đạt kết quả PASS.

---

## 6. Trạng Thái & Bàn Giao

- **S05 Hoàn thành đạt chuẩn.**
- Chuyển tiếp sang **S06**: Kiểm tra và hoàn thiện chuỗi reauth đa ngôn ngữ (Locale strings) theo G06 (`res/values/strings.xml`, `res/values-vi/strings.xml` và các locale liên quan).
