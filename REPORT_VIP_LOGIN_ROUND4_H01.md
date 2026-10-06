# BÁO CÁO GÓI H01 — SERIALIZE LOGIN VỚI PROVIDER CLEANUP (U01/P1)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Gói H00 đã thiết lập 7 tests ban đầu trong `VipLoginRound4RegressionTest` (4 ca probe thất bại đỏ, 3 controls xanh).
- **Vấn đề U01:** Trong `AppAuthManager.signOut`, khi tiến trình provider cleanup bất đồng bộ bị treo/chậm, một lượt đăng nhập mới B khởi chạy và commit tài khoản thành công trước khi provider cleanup của A kết thúc. Khi cleanup của A tiếp tục chạy, nó thực thi các thao tác SDK có tính chất hủy diệt (`clearCredentialState`, `GoogleSignIn.signOut`), gây nguy cơ thu hồi hoặc xóa bỏ phiên làm việc mà tài khoản B vừa tạo.

## 2. Bảng trạng thái (State Table) & Cơ chế đồng bộ
- **Trạng thái:**
  - `IDLE`: Không có tác vụ dọn dẹp provider nào đang chạy. Cho phép đăng nhập/đăng xuất bình thường.
  - `CLEANING_PROVIDER`: Đang thực thi dọn dẹp SDK provider của một phiên đăng xuất.
    - *Yêu cầu đăng nhập mới:* Tạm dừng (suspend) tại `awaitProviderCleanup()`, chờ tác vụ provider của phiên cũ hoàn tất trước khi gọi SDK lấy credential và commit tài khoản mới.
    - *Đăng xuất kép (double logout):* Chuyển giao operation identity mới, hủy hoặc hoàn thành deferred cũ, đảm bảo không rò rỉ waiter.
    - *Lỗi / Ngoại lệ (error in cleanup):* Bắt lỗi, ghi log, giải phóng coordinator về `IDLE`, không bao giờ giữ khóa đăng nhập vĩnh viễn.
    - *Hủy scope (cancellation):* Bắt và rethrow `CancellationException` đúng chuẩn structured concurrency; `invokeOnCompletion` đảm bảo giải phóng coordinator về `IDLE`.
    - *Hết thời gian chờ (timeout) khi Task chưa xong:* Không âm thầm mở khóa (`silently unlock`) khi Task provider vẫn còn pending; từ chối đăng nhập với thông báo UI rõ ràng để người dùng thử lại an toàn.

## 3. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/LogoutCoordinator.kt` (File mới):
  - Điều phối tuần tự hóa giữa tiến trình dọn dẹp SDK bất đồng bộ và các yêu cầu đăng nhập tiếp theo.
  - Quản lý `activeOperationId`, `activeDeferred`, `isCleaning`, và cờ `isProviderTaskPending`.
  - Cung cấp `awaitProviderCleanup(timeoutMs)` có giới hạn thời gian (configurable timeout cho testing).
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Trong `signInWithGoogle`: Bổ sung **Step 0** gọi `LogoutCoordinator.awaitProviderCleanup()` trước khi chạm vào Provider SDK hay commit tài khoản. Nếu provider task vẫn đang pending sau timeout, hủy bỏ đăng nhập với thông báo lỗi rõ ràng và giải phóng attempt lock.
  - Trong `signOut`:
    - Tạo `logoutOpId` từ `LogoutCoordinator.startLogout()`.
    - Xử lý chuẩn `CancellationException` (rethrow để không nuốt cancellation).
    - Theo dõi Task `GoogleSignIn.getClient.signOut()` qua listener và cập nhật `LogoutCoordinator.markProviderTaskCompleted()`.
    - Gọi `LogoutCoordinator.onCleanupCompleted(logoutOpId)` trong khối `finally` và `cleanupJob.invokeOnCompletion`.
  - Trong `resetForTesting()`: Gọi `LogoutCoordinator.resetForTesting()` để bảo đảm môi trường kiểm thử độc lập.
- `app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt`:
  - Cập nhật assertion probe U01: Kiểm tra user B commit thành công sau khi cleanup của A kết thúc.
  - Bổ sung 4 regression tests cho H01:
    - `regressionProviderCleanupCancelledScope_doesNotDeadlockFutureLogin`: Scope bị hủy trước khi launch không làm treo đăng nhập sau.
    - `regressionProviderCleanupException_doesNotDeadlockFutureLogin`: Ngoại lệ trong provider cleanup không gây kẹt đăng nhập.
    - `regressionDoubleSignOut_sequencesCleanly`: Đăng xuất 2 lần liên tiếp hoàn thành tuần tự sạch sẽ.
    - `regressionProviderTaskStillPendingAfterTimeout_rejectsLoginWithUiFeedback`: Provider Task bị treo quá timeout từ chối đăng nhập có thông báo UI, không mở khóa ngầm nguy hiểm.

## 4. Invariant & Production paths được test
- Hàng rào tuần tự hóa giữa provider cleanup và commit đăng nhập mới (U01).
- Trật tự thực thi: Provider cleanup hoàn tất trước khi tài khoản mới commit.
- An toàn trước hủy coroutine scope và ngoại lệ provider.

## 5. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Trước H01:** `probeProviderCleanupMustNotOverlapNewLogin FAILED` (`AssertionError: Destructive provider cleanup must finish before new login commits`).
- **Sau H01:**
  - `probeProviderCleanupMustNotOverlapNewLogin`: **PASSED** (U01 giải quyết triệt để).
  - `regressionProviderCleanupCancelledScope_doesNotDeadlockFutureLogin`: **PASSED**.
  - `regressionProviderCleanupException_doesNotDeadlockFutureLogin`: **PASSED**.
  - `regressionDoubleSignOut_sequencesCleanly`: **PASSED**.
  - `regressionProviderTaskStillPendingAfterTimeout_rejectsLoginWithUiFeedback`: **PASSED**.
  - Toàn bộ controls và các ca test khác: **PASSED**.
- **Kiểm tra không hồi quy (Vòng 2 & 3):**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain` → **BUILD SUCCESSFUL** (0 failures).

## 6. Regression còn đỏ cho các gói tiếp theo
- **H02:** `probeDriveNullHostTokenDoesNotBorrowOtherRequest` (U02/P2) — Drive callback thiếu token mượn pending request.
- **H03:** `probeOldDriveTokenDoesNotMatchNewProcessCounters` (U03/P2) — Counter reset làm token cũ trùng request mới.
- **H04a & H04b:** `probeSyncActionMustRecheckSessionAtClick` (U04/P2) — Action UI cũ chạy sau khi đổi phiên.

## 7. Runtime chưa chạy
- Thử nghiệm tài khoản Google thật trên thiết bị với mạng chậm trong quá trình sign-out IPC: **NOT RUN** (thiếu thiết bị ADB).

## 8. Điểm dừng gói H01
Hoàn tất H01. U01 đã được xử lý triệt để với `LogoutCoordinator`.
