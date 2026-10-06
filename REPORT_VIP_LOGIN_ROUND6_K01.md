# BÁO CÁO GÓI K01 — OPERATION KHÔNG TỰ KẾT THÚC KHI BỊ THAY THẾ

Ngày thực hiện: 26/09/2026.  
Phạm vi: Gói K01 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)

1. **Không tự động coi thao tác cũ đã hoàn tất:**
   - Trong `LogoutCoordinator.startLogout()`, việc khởi tạo một thao tác logout mới (Operation B) tuyệt đối **không được ghi nhận `COMPLETED`** cho bất kỳ thao tác cũ nào (Operation A) đang ở trạng thái `RUNNING`.
   - Một thao tác chỉ đạt trạng thái kết thúc (Terminal State) khi chính Job/Coroutine sở hữu nó hoặc caller phát tín hiệu tường minh qua `onCleanupCompleted`, `onCleanupCancelled`, hoặc `onCleanupFailed`.
2. **Theo dõi độc lập giữa Coroutine và Provider Task:**
   - Mỗi thao tác duy trì danh sách Task của riêng mình (`pendingTasks`, `completedTasks`). Callback hoàn tất của Task thuộc operation nào chỉ cập nhật đúng operation đó.
   - Thao tác cũ dù chưa kịp đăng ký Task (ví dụ đang chờ tại `CredentialManager.clearCredentialState`) vẫn được giữ nguyên trạng thái `RUNNING` và tiếp tục gate mọi yêu cầu đăng nhập tiếp theo.
3. **Giải phóng bản ghi (Record Cleanup) an toàn:**
   - Bản ghi thao tác chỉ được dọn khỏi `operations` khi và chỉ khi thỏa mãn đồng thời: `coroutineState != RUNNING && pendingTasks.isEmpty()`.
   - Waiter timeout hoặc cancellation tuyệt đối không làm thay đổi trạng thái của bất kỳ operation nào.
4. **Phạm vi can thiệp tối thiểu:**
   - Chỉ chỉnh sửa `app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt` và cập nhật suite test `LogoutCoordinatorTest.kt`. Không can thiệp `AppAuthManager` hay wiring SDK trong gói này.

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. `app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt`
- **Trước (dòng 58–68):**
  ```kotlin
  fun startLogout(): Long = synchronized(lock) {
      val opId = operationCounter.incrementAndGet()
      for ((id, record) in operations) {
          if (id != opId && record.coroutineState == CoroutineState.RUNNING) {
              record.coroutineState = CoroutineState.COMPLETED // Lỗi: tự coi coroutine cũ đã xong
          }
      }
      operations.values.removeAll { it.isFinished() } // Lỗi: xóa luôn operation cũ nếu chưa có task
      ...
  ```
- **Sau:**
  ```kotlin
  fun startLogout(): Long = synchronized(lock) {
      val opId = operationCounter.incrementAndGet()
      // Clean up any historical operations that are already fully finished
      operations.values.removeAll { it.isFinished() }

      activeOperationId = opId
      val record = OperationRecord(operationId = opId)
      operations[opId] = record
      notifyStateChangeLocked()
      Log.d(TAG, "Started logout operation #$opId (total active operations: ${operations.size})")
      opId
  }
  ```
  - Loại bỏ hoàn toàn vòng lặp tự ý gán `CoroutineState.COMPLETED` cho các operation cũ. Thao tác cũ giữ nguyên trạng thái `RUNNING` cho đến khi Job của nó hoàn tất thật sự.

### 2.2. `app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt`
- **Trước:**
  - `logoutAToBWhileWaiterAIsWaitingDoesNotUnlockPrematurely`: Chỉ gọi `onCleanupCompleted(opB)` mà không kết thúc `opA`, nhưng lại khẳng định `awaitProviderCleanup(10) == true`.
- **Sau:**
  - Cập nhật `logoutAToBWhileWaiterAIsWaitingDoesNotUnlockPrematurely`: Kiểm tra sau khi `opB` xong, cổng đăng nhập vẫn bị chặn (`assertFalse`) do `opA` còn đang chạy. Chỉ sau khi `opA` được hoàn tất tường minh (`onCleanupCompleted(opA)`), cổng đăng nhập mới mở (`assertTrue`).
  - Bổ sung 5 bài test hồi quy mới:
    1. `reverseCompletionOrderStillGatesUntilAllFinished`: Đảo thứ tự hoàn tất (A xong trước, B chưa xong → vẫn chặn; cả 2 xong → mở).
    2. `operationAWithPendingTaskAndBFinishedStillGatesUntilTaskCompletes`: A có task pending, B xong, A coroutine xong nhưng task chưa xong → vẫn chặn cho đến khi task xong.
    3. `operationBCancelledBeforeStartWhileAIsRunningStillGatesUntilACompletes`: B bị hủy trước khi bắt đầu, A còn chạy → vẫn chặn cho đến khi A xong.
    4. `multipleConcurrentWaitersAllUnlockWhenOperationsComplete`: Nhiều waiters đồng thời chờ và cùng mở khóa khi mọi operation kết thúc.
    5. `timeoutThenSubsequentCompletionAllowsFutureLogin`: Waiter timeout không làm hỏng state, waiter sau thành công khi operation hoàn tất.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

### 3.1. Kiểm thử bộ hồi quy vòng 6 (`VipLoginRound6RegressionTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound6RegressionTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **3 / 3 tests PASSED (GREEN toàn bộ, lỗi F01 probe từ đỏ đã chuyển sang xanh)**:
  1. `finishingNewLogoutMustNotFinishOlderRunningCoroutine`: PASSED (Trước đó thất bại ở K00).
  2. `allActualCoroutinesFinishedAllowsLogin`: PASSED.
  3. `pendingTaskStillBlocksAfterCoroutinesFinish`: PASSED.

### 3.2. Kiểm thử bộ điều phối (`LogoutCoordinatorTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.LogoutCoordinatorTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **12 / 12 tests PASSED (GREEN toàn bộ)**.

### 3.3. Kiểm thử tổng hợp các suite login liên quan
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipLoginRound*" --tests "com.tscanner.app.LogoutCoordinatorTest" --tests "com.tscanner.app.AppAuth*" --tests "com.tscanner.app.GoogleLogin*" --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Chi tiết:** **115 / 115 tests PASSED (0 Failures, 0 Errors, 0 Skipped)**.

---

## 4. Báo cáo ranh giới và chuẩn bị tích hợp Gói K02

- **Trạng thái đạt được tại K01:** `LogoutCoordinator` hiện đã quản lý độc lập toàn bộ các operation đồng thời. Không operation nào bị ngắt sớm giả tạo khi bị thay thế.
- **Vấn đề cần giải quyết tại K02 (AppAuthManager Integration):**
  - Khi `AppAuthManager.signOut()` khởi tạo một `logoutOpId = LogoutCoordinator.startLogout()`, job coroutine dọn dẹp được launch trong `coroutineScope`.
  - Cần bảo đảm ở tầng tích hợp: nếu scope bị hủy trước khi chạy (cancel-before-start), hoặc coroutine bị hủy/thất bại giữa chừng (đặc biệt khi đang treo tại `clearCredentialState`), `cleanupJob.invokeOnCompletion` hoặc khối `try-finally` phải báo đúng trạng thái terminal cho chính `logoutOpId` đó (không dùng active operation ID mới nhất), tránh tình trạng operation bị treo `RUNNING` vĩnh viễn trong coordinator.
- **Giới hạn máy chủ:** Kiểm tra thuần túy trên JVM Host; thiết bị thật / Google Play Console là **NOT RUN**.

---

## 5. Kết luận gói K01

- Gói **K01 đã hoàn tất 100%**: Mã production trong `LogoutCoordinator.kt` đã được sửa; cả suite hồi quy K00 và suite `LogoutCoordinatorTest` đều xanh tuyệt đối.
- Dừng lại theo đúng quy tắc, chuẩn bị chuyển sang **K02**.
