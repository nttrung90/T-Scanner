# BÁO CÁO GÓI J01 — TRẠNG THÁI CLEANUP THEO OPERATION VÀ TASK (F01)

Ngày thực hiện: 25/09/2026.  
Phạm vi: Gói J01 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md).

---

## 1. Bảng chuyển trạng thái (State Transition Table) đã thiết kế

Hệ thống quản lý trạng thái dọn dẹp đăng xuất tại [`LogoutCoordinator.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt) được tổ chức theo từng phiên độc lập (`OperationRecord`):

| Trạng thái hiện tại | Sự kiện kích hoạt | Điều kiện kiểm tra | Trạng thái kế tiếp | Tác động tới Waiter (`awaitProviderCleanup`) |
|---|---|---|---|---|
| `IDLE` (chưa có op) | `startLogout()` | Tạo mới operation ID | `op.coroutine = RUNNING`, `pendingTasks = []` | Waiter bị chặn (`isCleaningProvider = true`) |
| `op.coroutine = RUNNING` | `markProviderTaskStarted(op, taskId)` | Task SDK Google bắt đầu | `pendingTasks += taskId` | Waiter tiếp tục chờ, notify tín hiệu |
| `op.coroutine = RUNNING` | `onCleanupCompleted(op)` | Coroutine local hoàn tất | `op.coroutine = COMPLETED` | Nếu `pendingTasks` còn sống -> **VẪN CHẶN** (F01a) |
| `op.coroutine = COMPLETED`, `pendingTasks != []` | `markProviderTaskCompleted(op, taskId)` | Task SDK thật hoàn tất | `pendingTasks -= taskId` | Nếu `pendingTasks` rỗng -> Gỡ bỏ operation, **MỞ KHÓA** cho login |
| `opA.coroutine = RUNNING` (waiter đang chờ) | `startLogout()` (opB) | Người dùng logout lần 2 | `opA.coroutine = COMPLETED`, `opB = RUNNING` | Giữ nguyên `pendingTasks` của A. Waiter thức dậy kiểm tra thấy B còn chạy -> **TIẾP TỤC CHỜ**, không nhả sớm (F01b) |
| `opA.pendingTasks = [T]`, `opB.pendingTasks = [T]` | `markProviderTaskCompleted(opA, T)` | Callback trễ của opA | `opA.pendingTasks -= T` | Chỉ xóa task của A; task của B **vẫn pending**, waiter tiếp tục chặn |
| `op.coroutine = RUNNING`, `pendingTasks = []` | `onCleanupCancelled(op)` | Scope hủy trước khi Task SDK chạy | `op.coroutine = CANCELLED`, `isFinished = true` | Gỡ bỏ operation ngay lập tức, **KHÔNG DEADLOCK** |
| Bất kỳ (`pendingTasks != []`) | `awaitProviderCleanup` timeout | Hết thời gian chờ (ms) | Không đổi trạng thái op/task | Waiter trả về `false`; tasks **không bị xóa giả tạo** |

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. Cập nhật production: [`app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt)
- **Trước:**
  - Dùng cờ Boolean toàn cục đơn giản `isCleaning` và `isProviderTaskPending`.
  - `onCleanupCompleted` tự tiện gán `isProviderTaskPending = false` và hoàn tất deferred, làm mở khóa login khi Task SDK thực tế còn pending (F01a).
  - `startLogout` gọi `activeDeferred?.complete(Unit)` giải phóng waiter đang chờ của logout cũ dù logout mới đang bắt đầu (F01b).
- **Sau:**
  - Quản lý theo `OperationRecord(operationId, coroutineState, pendingTasks, completedTasks)`.
  - `markProviderTaskStarted` và `markProviderTaskCompleted` nhận `operationId` và `taskId` rõ ràng; callback cũ chỉ hoàn tất task của chính nó.
  - Khi operation B bắt đầu, chỉ đánh dấu coroutine của operation A là hoàn tất/thay thế, nhưng **toàn bộ pending Tasks của A được bảo toàn**.
  - Waiter trong `awaitProviderCleanup` khi được đánh thức bởi tín hiệu luôn đọc lại trạng thái tổng thể `isCleaningProviderLocked()`, không coi việc deferred hoàn tất là quyền cho login.
  - `CancellationException` được rethrow trực tiếp bảo đảm nguyên tắc structured concurrency.
  - Các hàm có kiểu trả về `: Unit` tường minh.

### 2.2. Thêm mới test: [`app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt)
- Tạo 7 unit tests kiểm tra toàn diện 7 kịch bản:
  1. `taskPendingAndCoroutineCompletedStillGates`: Task pending + coroutine completed vẫn gate.
  2. `taskCompleteUnlocksWaiter`: Task complete mới unlock.
  3. `logoutAToBWhileWaiterAIsWaitingDoesNotUnlockPrematurely`: Logout A→B khi waiter A đang chờ không unlock sớm.
  4. `taskALateCallbackDoesNotCompleteTaskB`: Task A callback trễ không hoàn tất task B.
  5. `canceledBeforeProviderStartDoesNotDeadlock`: Canceled-before-provider-start không deadlock.
  6. `timeoutWaiterDoesNotChangeTaskState`: Timeout waiter không thay đổi task state.
  7. `duplicateCompletionIsIdempotent`: Duplicate completion idempotent.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

### 3.1. Bộ test hồi quy [`LogoutCoordinatorTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.LogoutCoordinatorTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **7/7 tests PASSED (100% Green)**.

### 3.2. Bộ test hồi quy [`VipLoginRound5RegressionTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipLoginRound5RegressionTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound5RegressionTest --offline --console=plain
  ```
- **Exit Code:** `1` (Đúng theo nghiệm thu J01: 2 probe F01 và control đã xanh, 2 probe F02 còn lại vẫn đỏ theo kế hoạch).
- **Chi tiết 5 tests:**
  - 🟢 `pendingProviderTaskSurvivesCoroutineCompletion` (F01a): **PASSED**
  - 🟢 `replacedLogoutMustNotReleaseOldWaiter` (F01b): **PASSED**
  - 🟢 `completedProviderAllowsLoginControl` (Control): **PASSED**
  - 🔴 `legacyRestoredLoginTokenMustNotMatchNewProcess` (F02): **FAILED** (chờ gói J03)
  - 🔴 `oldEpochCancellationMustNotClearNewAttempt` (F02): **FAILED** (chờ gói J03)

### 3.3. Bộ test hồi quy Vòng 4 [`VipLoginRound4RegressionTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL, không phát sinh hồi quy).

---

## 4. API mới của LogoutCoordinator

```kotlin
object LogoutCoordinator {
    const val DEFAULT_TASK_ID = "default_provider_task"

    // Khởi tạo operation mới, trả về ID định danh
    fun startLogout(): Long

    // Đăng ký Task SDK của provider với operation cụ thể (hoặc active operation)
    fun markProviderTaskStarted(operationId: Long? = null, taskId: String = DEFAULT_TASK_ID): String

    // Đánh dấu Task SDK của provider đã hoàn tất (idempotent, chỉ hoàn tất task đúng op)
    fun markProviderTaskCompleted(operationId: Long? = null, taskId: String = DEFAULT_TASK_ID): Unit

    // Báo coroutine dọn dẹp local đã xong (không tự tiện xóa pending Task)
    fun onCleanupCompleted(operationId: Long): Unit

    // Báo coroutine dọn dẹp local bị cancel
    fun onCleanupCancelled(operationId: Long): Unit

    // Báo coroutine dọn dẹp local bị lỗi
    fun onCleanupFailed(operationId: Long, throwable: Throwable? = null): Unit

    // Kiểm tra còn operation hoặc task nào đang dọn dẹp không
    fun isCleaningProvider(): Boolean

    // Kiểm tra có provider task nào đang pending không
    fun hasPendingProviderTasks(): Boolean

    // Lấy operation ID active hiện tại
    fun getActiveOperationId(): Long

    // Tạm dừng coroutine chờ dọn dẹp xong toàn bộ
    suspend fun awaitProviderCleanup(timeoutMs: Long = cleanupTimeoutMs): Boolean
}
```

---

## 5. Các khía cạnh Runtime chưa chạy (NOT RUN)
- **Chưa nối Task Google SDK thực tế:** Gói J01 mới chỉ hoàn thiện và nghiệm thu state machine độc lập của `LogoutCoordinator`. Việc nối Google SignOut Task listener thật trong `AppAuthManager` thuộc phạm vi gói **J02**.
- **Chưa sửa F02 (Epoch/Identity):** 2 bài test probe F02 vẫn đang đỏ theo đúng lộ trình, sẽ được xử lý trong gói **J03**.
- **Chưa chạy trên thiết bị / Google Play Console**.

---

## 6. Kết luận nghiệm thu gói J01
- Nghiệm thu: Hai probe F01 (F01a, F01b) và control đã XANH. Bộ 7 test mở rộng trong `LogoutCoordinatorTest` XANH 100%. Không làm hỏng các test Vòng 4.
- Chưa claim tích hợp SDK thật (sẽ thực hiện tại J02).
- Hoàn tất gói J01.
