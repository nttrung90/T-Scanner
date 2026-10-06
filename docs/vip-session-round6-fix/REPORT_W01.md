# Báo Cáo Gói W01 — Một Nguồn Quản Lý Recovery, Dispatch Không Tiêu Lượt (S01)

Ngày thực hiện: 06/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

---

## 1. Mục Tiêu & Phạm Vi Gói W01

- Khắc phục triệt để khiếm khuyết S01 tại tầng phát sinh sự kiện và quản lý hạn mức (authoritative ledger):
  1. Loại bỏ hoàn toàn việc tiêu sớm ngân sách phục hồi trong `BillingManager` chỉ vì danh sách `authRequiredListeners.isNotEmpty()` hoặc chỉ vì sự kiện đã được dispatch. Dispatch và queue không phải là accepted recovery.
  2. Sự kiện bị bỏ qua trước khi tới consumer (ví dụ: Activity finishing/destroyed hoặc dialog không hiển thị), sự kiện không có consumer, hoặc consumer Defer do UI không active đều không làm thay đổi hạn mức phục hồi và không cần phải chờ cơ chế release thụ động.
  3. Hợp nhất ledger authoritative: `VipPurchaseAuthConsumer` quản lý reservation nguyên tử (`RESERVED`, `STARTED`, `COMPLETED`). Chỉ khi provider thực sự tiếp nhận và gọi `confirmStarted()`, trạng thái `STARTED` mới được xác lập và `event.commitAttempt()` mới được kích hoạt để ghi nhận vào `BillingManager`.
  4. Bắt lỗi tại fallback của `VipUpgradeDialog`: tuyệt đối không tự ý gọi `confirmStarted()` trước callback `Unit` hoặc khi callback `null`. Khi thiếu recovery-capable host, an toàn giải phóng reservation (`decision.release()`).

---

## 2. Chi Tiết Các File Can Thiệp

### 2.1. `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
- Mở rộng `PurchaseAuthRequiredEvent`:
  - Thêm trường `val onCommitAttempt: (() -> Unit)? = null` và phương thức hỗ trợ `fun commitAttempt() = onCommitAttempt?.invoke()`.
- Thêm phương thức đồng bộ ledger:
  ```kotlin
  fun recordRecoveryAttempted(recoveryKey: String) {
      attemptedRecoveryKeys.add(recoveryKey)
  }

  fun isRecoveryAttempted(recoveryKey: String): Boolean {
      return attemptedRecoveryKeys.contains(recoveryKey) ||
             VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted(recoveryKey)
  }
  ```
- Trong `processPurchase`:
  - **Gỡ bỏ hoàn toàn** đoạn code thêm `recoveryKey` vào `attemptedRecoveryKeys` tại thời điểm dispatch:
    ```kotlin
    // REMOVED: if (isInteractive && hasUiListener && !alreadyRetried) { attemptedRecoveryKeys.add(recoveryKey) }
    ```
  - Khởi tạo `PurchaseAuthRequiredEvent` với:
    ```kotlin
    onReleaseAttempt = { releaseRecoveryAttempt(recoveryKey) },
    onCommitAttempt = { recordRecoveryAttempted(recoveryKey) }
    ```

### 2.2. `app/src/main/java/com/tscanner/app/ui/dialogs/VipPurchaseAuthConsumer.kt`
- Bổ sung phương thức tra cứu trạng thái:
  ```kotlin
  fun isRecoveryStartedOrCompleted(recoveryKey: String): Boolean {
      val cur = reservations[recoveryKey] ?: return false
      return cur.state == ReservationState.STARTED || cur.state == ReservationState.COMPLETED
  }
  ```
- Trong `onStarted` của `PurchaseAuthDecision.RequestReauth`:
  - Khi provider cam kết tiếp nhận qua `confirmStarted()`: chuyển `cur.state = ReservationState.STARTED` đồng thời gọi `event.commitAttempt()`.
- Trong `onReleased`:
  - Khi provider từ chối / bận / hủy qua `release()`: gỡ bỏ reservation và gọi `event.releaseAttempt()`.

### 2.3. `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
- Tại nhánh fallback của `executePurchaseAuthDecision`:
  - Nếu `onRequestSignInForRecovery == null`: giải phóng reservation `decision.release()` thay vì gọi sớm `decision.confirmStarted()`.
  - Đảm bảo reservation không bao giờ bị chuyển sang `STARTED` khi host không hỗ trợ cơ chế recovery có handshake hai chiều.

### 2.4. `app/src/test/java/com/tscanner/app/VipPurchaseAuthConsumerTest.kt`
- Bổ sung 2 bài unit tests chuyên sâu:
  1. `confirmStarted_triggersCommitAttemptAndUpdatesLedger`: kiểm chứng `commitAttempt` chỉ được gọi sau khi `confirmStarted()` chạy và ledger được cập nhật.
  2. `release_doesNotTriggerCommitAttempt`: kiểm chứng khi `release()` được gọi, `commitAttempt` không bao giờ bị kích hoạt và hạn mức được trả về nguyên trạng.

---

## 3. Bằng Chứng Kiểm Thử Thực Tế

### 3.1. Kết Quả Chạy 27 Probes Độc Lập
Lệnh: `powershell -NoProfile -File docs/vip-session-round6-fix/run-probes.ps1`  
Mã thoát: `GRADLE_EXIT=0` — **BUILD SUCCESSFUL**.

| Bộ Probe | Số lượng | Kết quả Baseline W00 | Kết quả sau W01 | Trạng thái |
|---|---|---|---|---|
| **Round 6 Probes** (`VipSessionRound6ProbeTest`) | 2 | 1 FAIL, 1 PASS | **2 PASS** | **RED $\to$ GREEN** |
| - `P01_listenerDropsBeforeConsumerMustNotSpendRecovery` | 1 | FAIL | **PASS** | Khắc phục xong S01 |
| - `C01_explicitRefusalThenAcceptanceWorksAndDoesNotRepeat` | 1 | PASS | **PASS** | Giữ vững |
| **Round 5 Regressions** (`VipSessionRound5ProbeTest`) | 4 | 4 PASS | **4 PASS** | Bảo toàn 100% |
| **Round 4 Regressions** (`VipSessionRound4ProbeTest`) | 7 | 7 PASS | **7 PASS** | Bảo toàn 100% |
| **Round 3 Regressions** (`VipSessionRound3ProbeTest`) | 7 | 7 PASS | **7 PASS** | Bảo toàn 100% |
| **Round 2 Regressions** (`VipSessionReauditProbeTest`) | 7 | 7 PASS | **7 PASS** | Bảo toàn 100% |
| **Tổng cộng probes** | **27** | 26 PASS, 1 FAIL | **27 / 27 PASS (100%)** | **ALL GREEN** |

### 3.2. Kết Quả Kiểm Thử Unit Test Suite Mới
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipPurchaseAuthConsumerTest" --offline --console=plain`
- Kết quả: **10 tests completed, 10 passed, 0 failed, 0 errors**.
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipPurchaseRecoveryIntegrationTest" --offline --console=plain`
- Kết quả: **7 tests completed, 7 passed, 0 failed, 0 errors**.

---

## 4. Kết Luận Gói W01

- Lỗi đỏ P01 đã chính thức chuyển thành **GREEN**.
- Việc dispatch hoặc sự tồn tại của listener không còn tiêu tốn recovery budget của người dùng.
- Authoritative ledger vận hành nhất quán và chuẩn xác.
- Sẵn sàng chuyển tiếp sang gói **W02** để khép toàn bộ các entry point còn lại (Home $\to$ More, AccountDetail, CreatePdf).
