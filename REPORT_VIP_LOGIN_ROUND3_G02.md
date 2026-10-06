# BÁO CÁO GÓI G02 — KHÔNG MƯỢN TOKEN TỪ SINGLETON (T02/P1)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói G01, 10 tests trong `VipLoginRound3RegressionTest` chạy với 3 ca đỏ còn lại (`probeMissingHostTokenMustNotBorrowActiveAttempt`, `probeLateSignOutMustPreserveNewLogin`, `probeCopiedDriveAttemptCannotBeConsumedTwice`).
- **Vấn đề T02:** Trong `AppAuthManager.handleGoogleSignInResult`, biểu thức `val targetAttempt = attempt ?: activeLoginAttempt.get()` tự ý mượn attempt đang active từ singleton khi caller truyền `attempt == null`. Tương tự, các overload không nhận attempt cũng gọi `attempt = activeLoginAttempt.get()`. Khi host A nhận callback chậm, trùng lặp, hoặc không có token (ví dụ sau khi đã xóa pending token hoặc saved state mất token) trong lúc host B vừa khởi tạo một attempt mới, callback của host A sẽ chiếm đoạt attempt của host B, commit tài khoản lạ hoặc hủy attempt đang chờ của host B.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Loại bỏ hoàn toàn fallback `attempt ?: activeLoginAttempt.get()`. Nếu `attempt == null || !isAttemptValid(attempt)` thì ghi cảnh báo log và discard ngay lập tức mà không gọi parser hay commit.
  - Sửa các overload backward-compatible của `handleGoogleSignInResult` để truyền `attempt = null` thay vì mượn `activeLoginAttempt.get()`, đánh dấu `@Deprecated` để yêu cầu caller truyền explicit attempt token, triệt tiêu hoàn toàn đường bypass mượn token ngầm.
- `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`:
  - Đồng bộ chuẩn lifecycle: capture `val attempt = pendingSignInAttempt; pendingSignInAttempt = null` trước khi gọi `AppAuthManager.handleGoogleSignInResult`, bảo đảm callback thứ hai hoặc trùng lặp sẽ có `attempt = null` và bị loại bỏ an toàn.
- `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`:
  - Đồng bộ chuẩn lifecycle: capture `val attempt = pendingSignInAttempt; pendingSignInAttempt = null` trước khi gọi `AppAuthManager.handleGoogleSignInResult`.
- `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`:
  - Cập nhật assertion probe T02: kiểm tra `assertNull(getCurrentUser())` đồng thời xác nhận `isSignInInProgress` và `activeLoginAttempt` của host B vẫn được giữ nguyên vẹn.
  - Bổ sung 3 regression tests toàn diện cho G02:
    - `regressionOldDuplicateCallbackWithoutToken_doesNotCancelOrCommitAttemptB`: Callback hủy hoặc thành công không token từ host cũ không được phép hủy hoặc ghi đè attempt B.
    - `regressionNullToken_doesNotInvokeParserOrProduceSideEffects`: Callback không token bị chặn ngay ở guard biên, không gọi `parser` gây side effect hoặc exception.
    - `regressionSavedStateLostToken_allowsSafeRetry`: Saved state mất token được discard an toàn và cho phép retry đăng nhập bình thường.

## 3. Production paths được test
- `AppAuthManager.handleGoogleSignInResult` với `attempt == null` khi có một active attempt khác trong hệ thống.
- `AppAuthManager.handleGoogleSignInResult` khi nhận `RESULT_CANCELED` với `attempt == null` không tác động attempt đang active.
- `AppAuthManager.handleGoogleSignInResult` với bomb parser chứng minh parser không bị gọi khi thiếu token.
- Đường retry an toàn sau khi token bị mất.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Trước G02:** `probeMissingHostTokenMustNotBorrowActiveAttempt FAILED` (`AssertionError: Uncorrelated host result must not borrow another attempt expected null, but was:<UserProfile(id=old...)>`).
- **Sau G02:**
  - `probeMissingHostTokenMustNotBorrowActiveAttempt`: **PASSED** (T02 giải quyết triệt để).
  - `regressionOldDuplicateCallbackWithoutToken_doesNotCancelOrCommitAttemptB`: **PASSED**.
  - `regressionNullToken_doesNotInvokeParserOrProduceSideEffects`: **PASSED**.
  - `regressionSavedStateLostToken_allowsSafeRetry`: **PASSED**.
  - Toàn bộ các test của G00 và G01: **PASSED**.
- **Kiểm tra không hồi quy (Round 2 & Router):**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleSignInResultRouterTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain` → **BUILD SUCCESSFUL** (toàn bộ tests router và vòng 2 xanh).

## 5. Regression còn đỏ cho các gói tiếp theo
- G03: `probeLateSignOutMustPreserveNewLogin` (T04/P1) — Logout cũ xóa login mới.
- G04: `probeCopiedDriveAttemptCannotBeConsumedTwice` (T03/P2) — Drive token qua serialization consume hai lần.

## 6. Runtime chưa chạy
- Xử lý intent callback thực tế sau khi process death từ Google Play Services: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói G02
Hoàn tất G02. T02 đã được khắc phục hoàn toàn. Không còn route ngầm mượn active token từ singleton.
