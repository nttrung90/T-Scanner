# BÁO CÁO GÓI S03 — KHÔNG BIẾN LỖI HẬU ĐĂNG NHẬP THÀNH LỖI PROVIDER

**Thời điểm:** 2026-09-25  
**Phạm vi:** `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`, `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt`  
**Gói kế thừa:** S00 (Baseline probes red), S01 (Token & Session Invalidation), S02 (Lifecycle Cancellation & Fallback Locking)  

---

## 1. Mục tiêu và Thiết kế Gói S03

Theo `PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md`, mục tiêu của S03 là giải quyết **R03/P2**:
- Trước sửa: khối `try { ... }` bao trọn từ lời gọi SDK Credential Manager, gọi `commitSignedInAccount`, cho tới callback UI `onSuccess`. Nếu `onSuccess` ném lỗi (ví dụ Fragment bị destroy hoặc lỗi sync Drive/UI), khối `catch (e: Exception)` tổng bắt lỗi và nhầm tưởng đó là lỗi của Credential Manager provider, từ đó gọi `onFallbackToIntent()`, kích hoạt Intent đăng nhập lần 2 trong khi tài khoản người dùng đã được commit thành công vào hệ thống.
- Ở nhánh Classic (`handleGoogleSignInResult`), exception phát sinh sau `onSuccess` cũng có thể dẫn đến việc gọi thêm `onError`.

### Thiết kế ranh giới lỗi độc lập (Separated Error Boundaries):
1. **Provider SDK Boundary**:
   - Chỉ bao quanh lời gọi `credentialClient.getCredential(activity, request)`.
   - `CancellationException` được re-throw nguyên bản để giữ đúng hành vi coroutine lifecycle.
   - `GetCredentialCancellationException` (người dùng chủ động hủy sheet đăng nhập) kích hoạt `onCancelled()`, giải phóng lock, và **tuyệt đối không fallback**.
   - Các lỗi kỹ thuật từ provider (`NoCredentialException`, `GetCredentialException`, lỗi SDK khác) trả về `accountData = null` để chuyển sang bước Fallback.
2. **Commit Boundary**:
   - `commitSignedInAccount` được đặt trong try/catch riêng. Nếu bước commit thất bại (ví dụ lỗi SharedPreferences/I/O), hệ thống gọi `onError(errorMsg)` và giải phóng lock; **tuyệt đối không kích hoạt fallback Intent**.
3. **Post-Auth Callback Boundary**:
   - `onSuccess(profile)` được gọi trên `mainDispatcher` trong try/catch riêng. Nếu `onSuccess` ném ngoại lệ (lỗi UI/sync sau đăng nhập), hệ thống ghi log lỗi, **không gọi onError** và **không kích hoạt fallback Intent**. Trạng thái đăng nhập của người dùng đã được cam kết bền vững.
4. **Terminal State**:
   - Mỗi attempt chỉ có tối đa một trạng thái kết thúc (Success, Cancelled, hoặc Error).
   - Ở nhánh fallback Classic, `GoogleSignInResultRouter.dispatchResult` đảm bảo chỉ gọi duy nhất một callback qua CAS. Nếu `onSuccess` ném ngoại lệ, khối catch độc lập log lỗi mà không kích hoạt `onError` lần hai.

---

## 2. Các thay đổi mã nguồn

1. **`AppAuthManager.kt`**:
   - Tách biệt rõ 3 bước trong `signInWithGoogle`:
     - Step 1: Lời gọi `credentialClient.getCredential` chỉ bắt lỗi SDK provider.
     - Step 2a: `commitSignedInAccount` bắt lỗi commit riêng biệt, báo `onError`, không fallback.
     - Step 2b: `onSuccess(profile)` bắt lỗi UI/sync riêng biệt, bảo toàn phiên đã commit, không fallback, không `onError`.
     - Step 3: Fallback sang classic GoogleSignIn Intent chỉ diễn ra khi provider trả về null/lỗi SDK và attempt còn hợp lệ.
   - Hoàn thiện `handleGoogleSignInResult` với commit và callback error boundary độc lập, ngăn chặn `onError` sau `onSuccess`.

2. **`VipLoginRound2RegressionTest.kt`**:
   - Cập nhật probe `probeSuccessCallbackFailureMustNotRestartLogin` (R03): Credential Manager trả về tài khoản hợp lệ, `onSuccess` ném `IllegalStateException` -> người dùng đã đăng nhập, `fallbacks == 0` (XANH).
   - Bổ sung 5 ca kiểm thử hồi quy mới cho S03:
     - `regressionCredentialOnSuccessThrow_doesNotCallOnErrorOrFallback`: `onSuccess` ném lỗi không gọi fallback, không gọi `onError`, người dùng giữ trạng thái đã đăng nhập, lock được giải phóng cho lần đăng nhập sau.
     - `regressionClassicOnSuccessThrow_doesNotCallOnError`: `onSuccess` ở classic flow ném lỗi không gọi `onError`, tài khoản đã commit, lock được giải phóng.
     - `regressionProviderError_triggersFallbackExactlyOnce`: Lỗi provider kích hoạt fallback Intent đúng 1 lần, lock giữ nguyên để đón Intent result.
     - `regressionUserCancellation_doesNotTriggerFallbackOrError`: Người dùng hủy sheet Credential Manager gọi `onCancelled`, không fallback, không `onError`, lock giải phóng.
     - `regressionCommitError_callsOnErrorAndDoesNotFallback`: Lỗi lưu trữ cục bộ khi commit gọi `onError`, không fallback Intent, lock giải phóng.

---

## 3. Kết quả thực thi kiểm thử

### Lệnh 1: Bộ kiểm thử hồi quy VIP Login vòng 2
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
```
- **Exit code**: `1` (Dự kiến chính xác: 20 tests completed, 19 passed, 1 failed).
- **Ca duy nhất đỏ**: `probeDriveResultWithoutPendingRequestMustNotSucceed` (Probe R04 dành riêng cho gói S04 kế tiếp).
- **19 ca xanh bao gồm**:
  - `controlValidSignIn_positiveControl_succeeds` [PASS]
  - `controlValidDriveAuthorization_withPendingRequest_succeeds` [PASS]
  - `probeCancelledScopeMustNotLockFutureSignIn` (R02) [PASS]
  - `probeSessionInvalidationMustRejectPendingCredential` (R01) [PASS]
  - `probeSuccessCallbackFailureMustNotRestartLogin` (R03) [PASS]
  - 5 tests S01 (Token & Invalidation) [ALL PASS]
  - 4 tests S02 (Scope Cancel & Fallback Lock) [ALL PASS]
  - 5 tests S03 (Separated Error Boundaries) [ALL PASS]

### Lệnh 2: Các bộ kiểm thử luồng đăng nhập hiện có
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain
```
- **Exit code**: `0` [BUILD SUCCESSFUL].

### Lệnh 3: Các bộ kiểm thử danh tính & Options
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.GoogleIdentityOptionsTest --tests com.tscanner.app.GoogleCredentialRequestFactoryTest --offline --console=plain
```
- **Exit code**: `0` [BUILD SUCCESSFUL].

---

## 4. Khối bàn giao bắt buộc (Handover Block)

```text
Gói / baseline: S03 — Không biến lỗi hậu đăng nhập thành lỗi provider (kế thừa S00, S01, S02)
File có thay đổi sẵn và file vừa sửa:
  - File sửa trong gói S03:
    * app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt
    * app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt
  - File giữ nguyên từ các gói trước:
    * app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt
    * app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt
    * app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt
    * Các uncommitted changes khác trên working tree được bảo toàn 100%.
Production path thực sự được test:
  - AppAuthManager.signInWithGoogle (Credential Manager provider seam, commit boundary, post-auth onSuccess callback boundary)
  - AppAuthManager.handleGoogleSignInResult (Classic GoogleSignIn result dispatching, commit boundary, isolated post-auth callback)
  - AppAuthManager.commitSignedInAccount
Trước sửa: assertions hoặc hiện tượng lỗi:
  - R03: Ngoại lệ ném ra từ callback onSuccess sau khi người dùng đã đăng nhập và commit tài khoản thành công bị khối catch chung xử lý như lỗi SDK, kích hoạt onFallbackToIntent() mở lại màn hình đăng nhập Google lần 2.
Sau sửa: command, exit code, counts, report:
  - .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
  - Exit code: 1 (20 tests completed, 19 passed, 1 failed). Probe R03 chuyển sang XANH. 5 ca hồi quy mới của S03 đều XANH.
  - .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain -> Exit code: 0 [PASS]
  - Báo cáo chi tiết: REPORT_VIP_LOGIN_ROUND2_S03.md
Regression dự kiến còn đỏ thuộc gói sau:
  - probeDriveResultWithoutPendingRequestMustNotSucceed (Probe R04 thuộc gói S04 — Drive authorization có request ID và consume một lần)
Chưa chạy thiết bị/Play/Drive:
  - Runtime Android thực tế, Google Play Services trên thiết bị thật, Drive API v3 live network call, process recreation trên thiết bị.
Phụ thuộc và điểm dừng:
  - Gói S03 đã hoàn thành và nghiệm thu đầy đủ trên host JVM tests.
  - Đã dừng lại đúng cam kết. Chờ lệnh "tiếp" của người dùng để bắt đầu gói S04.
```
