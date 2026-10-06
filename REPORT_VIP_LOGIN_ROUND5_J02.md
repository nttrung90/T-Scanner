# BÁO CÁO GÓI J02 — NỐI TASK GOOGLE THẬT VÀO COORDINATOR (F01 TÍCH HỢP)

Ngày thực hiện: 25/09/2026.  
Phạm vi: Gói J02 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)
1. **Ràng buộc Task SDK thật sự:** Không dùng timeout hay việc catch `CancellationException` để tự đánh dấu Task SDK hoàn tất giả tạo. Việc đánh dấu hoàn tất (`markProviderTaskCompleted`) do listener thực sự của `Task` (`addOnCompleteListener`) kích hoạt.
2. **Ngăn chặn destructive overlap:** Khi coroutine dọn dẹp kết thúc (hoặc bị timeout/cancel), khối `finally` chỉ thông báo trạng thái coroutine hoàn tất (`onCleanupCompleted`). Task SDK còn sống tiếp tục giữ cờ pending trong `LogoutCoordinator`, ngăn chặn việc login mới ghi đè credentials hay session.
3. **Không rò rỉ Activity:** Quá trình dọn dẹp background sử dụng `applicationContext` cho toàn bộ các dịch vụ hệ thống (`WorkManager`, `CredentialManager`, `GoogleSignOutProvider`, `SharedPreferences`).
4. **Kiểm tra trạng thái Authoritative:** Trong `signInWithGoogle`, sau khi tạm dừng chờ `awaitProviderCleanup()`, hệ thống đối chiếu lại tính hợp lệ của request attempt (`isAttemptValid`) trước khi khởi chạy Google UI SDK; ngăn chặn request bị logout khác vô hiệu hóa mở UI Google muộn.
5. **Đảm bảo UI callback không bị nuốt:** Trong `signOut`, callback `onComplete()` được bảo vệ bằng `withContext(NonCancellable)` đảm bảo thông báo UI luôn thực thi ngay cả khi coroutine scope bị hủy.

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. Cập nhật production: [`app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt)
- **Trước:**
  - `signOut` gọi `GoogleSignIn.getClient(context, gso).signOut()` trực tiếp mà không tách lớp adapter kiểm thử.
  - Gọi `LogoutCoordinator.markProviderTaskStarted()` và `markProviderTaskCompleted()` không gắn với `logoutOpId` và `taskId`.
  - Trong catch `CancellationException` và catch `Exception`, tự ý gọi `markProviderTaskCompleted()` dù Task SDK vẫn đang chạy ngầm trong Play Services.
  - Khối `finally` gọi `withContext(mainDispatcher) { onComplete() }` bị hủy khi scope cancel, làm nuốt UI callback.
  - `signInWithGoogle` sau khi `awaitProviderCleanup()` không kiểm tra lại `isAttemptValid(attemptToken)`, có thể mở UI Google muộn nếu request đã bị vô hiệu hóa trong lúc chờ.
- **Sau:**
  - Khai báo interface `GoogleSignOutProvider` với implementation mặc định `DefaultGoogleSignOutProvider` gọi `GoogleSignIn.getClient(context, gso).signOut()`, cho phép adapter kiểm thử injection mà không đổi chữ ký API production.
  - Trong `signOut`: Sử dụng `appContext`, sinh `taskId = "google_sign_out_$logoutOpId"`, đăng ký với `LogoutCoordinator.markProviderTaskStarted(logoutOpId, taskId)`.
  - Gắn `task.addOnCompleteListener(directExecutor)` gọi `LogoutCoordinator.markProviderTaskCompleted(logoutOpId, taskId)`. Khi timeout 3 giây hoặc scope cancel xảy ra, coroutine hoàn tất nhưng Task SDK **vẫn tiếp tục được theo dõi và giữ cờ pending**.
  - `onComplete()` trong `finally` được bọc bởi `withContext(NonCancellable)` đảm bảo luôn được gọi.
  - Trong `signInWithGoogle`: Bổ sung kiểm tra hậu chờ `isAttemptValid(attemptToken)` và `activity` lifecycle trước khi gọi SDK provider.
  - Trong `resetForTesting()`: Reset `googleSignOutProvider = DefaultGoogleSignOutProvider()`.

### 2.2. Thêm mới test tích hợp: [`app/src/test/java/com/tscanner/app/AppAuthGoogleLogoutIntegrationTest.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/AppAuthGoogleLogoutIntegrationTest.kt)
- Xây dựng 5 kịch bản tích hợp production với controllable provider adapter:
  1. `taskPendingThroughTimeoutAndScopeCancel_gatesLoginUntilTaskCompletes`: Task pending qua timeout và scope cancel -> login bị gate; khi Task hoàn tất sau đó -> login hợp lệ thành công.
  2. `twoLogoutOperationsWithReversedCompletion_gatesUntilBothComplete`: Hai lệnh logout hoàn tất Task đảo thứ tự -> login vẫn bị gate cho tới khi cả hai task hoàn tất.
  3. `coroutineCanceledBeforeProviderStart_doesNotDeadlock`: Coroutine bị cancel trước khi provider start -> không gây deadlock, login kế tiếp chạy bình thường.
  4. `failureSynchronousBeforeTaskCreation_doesNotDeadlock`: Lỗi đồng bộ trước khi tạo Task -> giải phóng sạch sẽ, không treo coordinator.
  5. `validGoogleCredentialControlFlow_completesNormally`: Luồng đăng xuất chuẩn có Task hoàn tất ngay -> login hợp lệ thành công bình thường.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

### 3.1. Bộ test tích hợp mới [`AppAuthGoogleLogoutIntegrationTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/AppAuthGoogleLogoutIntegrationTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.AppAuthGoogleLogoutIntegrationTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **5/5 tests PASSED (100% Green)**.

### 3.2. Bộ test hồi quy Coordinator [`LogoutCoordinatorTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.LogoutCoordinatorTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL, 7/7 tests passed).

### 3.3. Bộ test luồng đăng nhập [`GoogleLoginFlowTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/GoogleLoginFlowTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL, 13/13 tests passed).

### 3.4. Bộ test hồi quy Vòng 4 [`VipLoginRound4RegressionTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL, 7/7 tests passed).

### 3.5. Bộ test hồi quy Vòng 5 [`VipLoginRound5RegressionTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipLoginRound5RegressionTest.kt)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound5RegressionTest --offline --console=plain
  ```
- **Exit Code:** `1` (Đúng nghiệm thu: F01a, F01b và Control XANH; 2 bài test F02 vẫn đỏ theo kế hoạch chờ J03).
  - 🟢 `pendingProviderTaskSurvivesCoroutineCompletion` (F01a): **PASSED**
  - 🟢 `replacedLogoutMustNotReleaseOldWaiter` (F01b): **PASSED**
  - 🟢 `completedProviderAllowsLoginControl` (Control): **PASSED**
  - 🔴 `legacyRestoredLoginTokenMustNotMatchNewProcess` (F02): **FAILED** (chờ gói J03)
  - 🔴 `oldEpochCancellationMustNotClearNewAttempt` (F02): **FAILED** (chờ gói J03)

---

## 4. Các khía cạnh Runtime chưa chạy (NOT RUN)
- **Google Play Services SDK Task trên thiết bị thật:** Test sử dụng adapter controllable Task production để mô phỏng chính xác Task listener và hành vi bất đồng bộ của Google Play Services. Việc chạy trên thiết bị thật vẫn là gate thiết bị (**NOT RUN**).
- **Phạm vi F02 (Saved State & Epoch):** Chưa sửa các host Activity/Fragment và helper của F02 (sẽ thực hiện tại **J03**).
- **Google Play Console / Kênh phân phối**: **NOT RUN**.

---

## 5. Kết luận nghiệm thu gói J02
- Nghiệm thu: `LogoutCoordinatorTest` (7 tests) và `AppAuthGoogleLogoutIntegrationTest` (5 tests) XANH 100%. Không task nào bị mark complete giả.
- `GoogleLoginFlowTest` và `VipLoginRound4RegressionTest` bảo toàn xanh.
- Hoàn tất gói J02.
