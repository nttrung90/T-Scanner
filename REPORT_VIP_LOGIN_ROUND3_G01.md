# BÁO CÁO GÓI G01 — NHẤT QUÁN IDENTITY CỦA TOKEN SAU RECREATE (T01/P1)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Gói G00 đã thiết lập 7 tests ban đầu trong `VipLoginRound3RegressionTest` (4 ca probe thất bại đỏ, 3 controls xanh).
- **Vấn đề T01:** `isAttemptValid` dùng so sánh dữ liệu (`equals`), trong khi việc giải phóng token (`cancelSignInProgress`, `commitSignedInAccount`, `handleGoogleSignInResult`, coroutine completion) lại dùng `AtomicReference.compareAndSet(token, null)` dựa trên con trỏ đối tượng (`===`). Khi Activity bị recreate sau cấu hình thay đổi (rotation/process kill), host tái tạo instance `GoogleLoginAttempt` mới có cùng `requestId` và `initialSessionGeneration`. Do khác identity con trỏ trong bộ nhớ, CAS thất bại, khiến `activeLoginAttempt` không được dọn và `isSignInInProgress` bị kẹt `true`, chặn mọi lượt đăng nhập tiếp theo.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Thêm phương thức bảo đảm luồng `matchesActiveAttemptLocked(token)` và `clearActiveAttemptIfMatchingLocked(token)` dưới `synchronized(authStateLock)`.
  - Thay thế toàn bộ các điểm gọi `activeLoginAttempt.compareAndSet(token, null)` (bao gồm `cancelSignInProgress`, `commitSignedInAccount`, `handleGoogleSignInResult` tại onCancelled, onError, finally, các nhánh exception và lifecycle cancellation trong `signInWithGoogle`, và `job.invokeOnCompletion`).
  - Đảm bảo token cũ stale (ví dụ attempt A) không được phép xóa attempt B mới hơn (`active.requestId == token.requestId && active.initialSessionGeneration == token.initialSessionGeneration`).
  - Đọc và đồng bộ trạng thái `isFallbackActive` trên instance active dưới `authStateLock`.
- `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`:
  - Bổ sung 3 regression tests toàn diện cho G01:
    - `regressionRecreatedLoginSuccess_commitsProfileAndReleasesActiveReference`: Token tái tạo sau recreate khi nhận RESULT_OK commit thành công profile, lưu persistent JSON trong prefs, dọn sạch active reference và mở đường cho lượt login tiếp theo.
    - `regressionRecreatedLoginError_releasesActiveReferenceAndAllowsNextSignIn`: Token tái tạo khi gặp lỗi giải phóng active reference, reset flag và cho phép thử lại.
    - `regressionStaleRecreatedAttemptA_whileAttemptBInProgress_doesNotReleaseOrOverwriteB`: Token tái tạo của attempt A cũ không được phép giải phóng hay ghi đè attempt B đang active.

## 3. Production paths được test
- `AppAuthManager.handleGoogleSignInResult` với restored attempt instance khi `RESULT_CANCELED` (T01).
- `AppAuthManager.handleGoogleSignInResult` với restored attempt instance khi `RESULT_OK` (commit profile & release lock).
- `AppAuthManager.handleGoogleSignInResult` với restored attempt instance khi error (release lock & allow retry).
- `AppAuthManager.handleGoogleSignInResult` với stale restored attempt A khi attempt B đang active.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Trước G01:** `probeRecreatedLoginCancelReleasesAttempt FAILED` (`AssertionError: Recreated host cancellation must release active attempt`).
- **Sau G01:**
  - `probeRecreatedLoginCancelReleasesAttempt`: **PASSED** (T01 giải quyết triệt để).
  - `regressionRecreatedLoginSuccess_commitsProfileAndReleasesActiveReference`: **PASSED**.
  - `regressionRecreatedLoginError_releasesActiveReferenceAndAllowsNextSignIn`: **PASSED**.
  - `regressionStaleRecreatedAttemptA_whileAttemptBInProgress_doesNotReleaseOrOverwriteB`: **PASSED**.
  - 3 controls trước đó: **PASSED**.
- **Kiểm tra không hồi quy (Round 2):**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain` → **BUILD SUCCESSFUL** (toàn bộ tests vòng 2 xanh).

## 5. Regression còn đỏ cho các gói tiếp theo
- G02: `probeMissingHostTokenMustNotBorrowActiveAttempt` (T02/P1) — Kết quả thiếu token của host mượn attempt hiện hành.
- G03: `probeLateSignOutMustPreserveNewLogin` (T04/P1) — Logout cũ xóa login mới.
- G04: `probeCopiedDriveAttemptCannotBeConsumedTwice` (T03/P2) — Drive token qua serialization consume hai lần.

## 6. Runtime chưa chạy
- Device rotation thực tế trên thiết bị vật lý với Google Sign-In Sheet / Intent hiển thị: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói G01
Hoàn tất G01. T01 đã được khắc phục và nghiệm thu đầy đủ bằng regression test.
