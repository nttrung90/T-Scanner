# Báo cáo Nghiệm thu Gói K02 — Vòng 6: Chứng minh lifecycle của AppAuthManager khi clearCredentialState còn chờ

**Ngày thực hiện:** 26/09/2026  
**Mã gói:** K02  
**Mục tiêu:** Chứng minh và bảo vệ vòng đời của `AppAuthManager` khi coroutine dọn dẹp logout A đang bị giữ tại `clearCredentialState` trước khi kịp tạo GoogleSignIn Task, đảm bảo login C mới không bị cấp quyền sớm khi logout B hoàn tất, đồng thời Task của A tạo sau đó tiếp tục chặn login C cho tới khi hoàn tất thực sự.

---

## 1. Bất biến (Invariants) được bảo đảm

1. **Bảo vệ coroutine chưa tạo Task:** Khi logout A đang treo ở `clearCredentialState` (chưa đăng ký Task vào `LogoutCoordinator`), `LogoutCoordinator` vẫn duy trì operation A ở trạng thái `RUNNING`. Nếu logout B bắt đầu và kết thúc toàn bộ, login C vẫn bị chặn (gated) cho đến khi coroutine của A kết thúc và mọi Task của A hoàn thành.
2. **Theo dõi chính xác terminal state theo `logoutOpId`:** Mỗi cleanup Job của `signOut` khi hoàn tất bình thường, bị hủy (`CancellationException` hay cancel-before-start), hoặc ném ngoại lệ đều báo đúng trạng thái tương ứng (`onCleanupCompleted`, `onCleanupCancelled`, `onCleanupFailed`) theo đúng `logoutOpId`, không dựa vào `activeOperationId` mới nhất và không gán `COMPLETED` cho coroutine bị cancel.
3. **Cơ chế Seam kiểm thử không xâm lấn:** Giới thiệu seam `CredentialClearProvider` trong `AppAuthManager` với cài đặt mặc định gọi trực tiếp `CredentialManager.create(context).clearCredentialState(...)`. Production sử dụng 100% mã thật; kiểm thử trên JVM có thể điều khiển điểm dừng và lỗi bằng `CompletableDeferred` mà không dùng `Thread.sleep` hay reflection.
4. **Không tự hủy Task khi coroutine timeout:** Nếu coroutine dọn dẹp logout A bị timeout sau 3000ms trong khi chờ Google Task hoàn tất, Task đó vẫn duy trì pending trên `LogoutCoordinator` cho đến khi callback của SDK hoàn tất thực sự.
5. **Giữ nguyên Token Epoch & Owner Guards:** Không thay đổi bất kỳ logic kiểm tra phiên nào đã được nghiệm thu từ các vòng 1–5.

---

## 2. Danh sách file sửa đổi

1. **Production:**
   - [`app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt)
     - Khai báo `fun interface CredentialClearProvider`, `class DefaultCredentialClearProvider`, trường `@VisibleForTesting var credentialClearProvider`.
     - Thay thế lệnh gọi trực tiếp `CredentialManager.create(...)` trong `signOut()` bằng `credentialClearProvider.clearCredentialState(appContext)`.
     - Cập nhật `cleanupJob.invokeOnCompletion { cause -> ... }` để phân loại chính xác `onCleanupCancelled`, `onCleanupFailed`, `onCleanupCompleted` cho `logoutOpId`.
     - Trong khối `finally` của `cleanupJob`, chỉ gọi `onCleanupCompleted(logoutOpId)` khi coroutine kết thúc bình thường (`completedNormally == true`).
     - Đặt lại `credentialClearProvider = DefaultCredentialClearProvider()` trong `resetForTesting()`.

2. **Tests:**
   - [`app/src/test/java/com/tscanner/app/AppAuthGoogleLogoutIntegrationTest.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/AppAuthGoogleLogoutIntegrationTest.kt)
     - Khai báo `ControllableCredentialClearProvider` cho phép tạm dừng coroutine bằng `CompletableDeferred` hoặc giả lập lỗi ném ra.
     - Thêm test `logoutAHeldAtClearCredentialState_blocksLoginC_untilABothJobAndTasksComplete()`:
       1. Logout A bắt đầu, treo tại `clearCredentialState` trước khi tạo Task.
       2. Logout B bắt đầu và kết thúc toàn bộ.
       3. Login C yêu cầu: bị chặn (gated), `signInWithGoogle` trả lỗi đang dọn dẹp tài khoản cũ, không cấp quyền hay commit user.
       4. Thả A tiếp tục: A tạo Google Task và duy trì Task pending; login C retry tiếp tục bị chặn.
       5. Task A hoàn tất: login C retry thành công.
     - Thêm test `clearCredentialStateThrowsException_doesNotDeadlockFutureLogins()`: kiểm tra lỗi ngoại lệ trước khi tạo Task không gây deadlock phiên sau.
     - Thêm test `coroutineCancelledWhileInsideClearCredentialState_cancelsOperationAndDoesNotDeadlock()`: kiểm tra hủy coroutine scope khi đang treo tại `clearCredentialState` giải phóng gate an toàn và không gây deadlock.

---

## 3. So sánh trước và sau (Before & After)

| Tiêu chí | Trước K02 | Sau K02 |
| :--- | :--- | :--- |
| **Kiểm soát `clearCredentialState`** | Gọi tĩnh vào `androidx.credentials.CredentialManager`, không thể kiểm thử race condition trên host JVM | Có `CredentialClearProvider` seam; mặc định gọi CredentialManager thật; test điều khiển bằng `CompletableDeferred` |
| **Báo cáo kết thúc `cleanupJob`** | `invokeOnCompletion` gọi vô điều kiện `onCleanupCompleted(logoutOpId)`, báo sai COMPLETED khi coroutine bị cancel/fail | `invokeOnCompletion` phân nhánh `CancellationException` -> `onCleanupCancelled`, lỗi -> `onCleanupFailed`, thành công -> `onCleanupCompleted` |
| **Báo cáo trong `finally`** | `finally` gọi `onCleanupCompleted(logoutOpId)` ngay cả khi coroutine bị cancel, ghi đè state | Chỉ gọi `onCleanupCompleted` khi `completedNormally == true` |
| **A treo tại credential clear, B xong** | A bị xóa khỏi coordinator; B xong cho phép login C ngay dù A còn chạy ngầm và sẽ xóa phiên của C | Coordinator vẫn giữ A `RUNNING`; login C bị chặn cho tới khi cả A và Task của A hoàn tất |

---

## 4. Lệnh thực thi và kết quả kiểm thử

### Lệnh 1: Suite tích hợp AppAuth Google Logout
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.AppAuthGoogleLogoutIntegrationTest --offline --console=plain
```
- **Exit code:** 0 (`BUILD SUCCESSFUL`)
- **Kết quả:** 8/8 tests đạt (bao gồm 3 test mới của K02, 0 failure, 0 error, 0 skipped).

### Lệnh 2: Toàn bộ suite Unit Test của `:app`
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```
- **Exit code:** 0 (`BUILD SUCCESSFUL`)
- **Tổng số tests:** **729 tests** (baseline 718 + K00: 3 + K01: 5 + K02: 3 = 729)
- **Thất bại:** 0
- **Lỗi:** 0
- **Bỏ qua:** 0

---

## 5. Phân loại kỹ thuật và giới hạn kiểm tra

1. **`PROBE PASS`:**
   - 3/3 probe tests trong `VipLoginRound6RegressionTest.kt` đạt (1 lỗi gốc P1 đã được giải quyết ở K01, 2 controls tiếp tục xanh).
2. **`CODE VERIFIED`:**
   - Mã nguồn `AppAuthManager` và `LogoutCoordinator` phối hợp hoàn chỉnh qua luồng production thực, không sao chép thuật toán.
   - Gating và serialization khi có nhiều logout chồng nhau và hoán đổi thứ tự hoàn tất được xác nhận an toàn qua `AppAuthGoogleLogoutIntegrationTest` (8 tests).
3. **`DEVICE PASS`:**
   - **N/A**: Không kết nối thiết bị phần cứng thật qua ADB trong phiên làm việc.
4. **`NOT RUN`:**
   - Google Play Console OAuth thực tế với chữ ký release keystore.
   - Hệ điều hành Android thật thu hồi bộ nhớ (low-memory process death) chính xác tại thời điểm coroutine đang suspend trong `CredentialManager`.
   - Xoay màn hình / thay đổi cấu hình phần cứng trong quá trình mạng Google Play Services phản hồi chậm.

---

## 6. Trạng thái chuyển giao

Gói **K02** đã hoàn tất và được kiểm tra toàn diện. Sẵn sàng chuyển giao sang gói **K03** (Tổng hợp, chạy toàn bộ kiểm tra nghiệm thu tổng thể `--rerun :app:lintDebug :app:assembleDebug`, đối chiếu lint XML và hoàn thiện báo cáo cuối cùng vòng 6).
