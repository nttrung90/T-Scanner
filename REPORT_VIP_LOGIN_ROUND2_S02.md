# Báo cáo Nghiệm thu S02 — Cleanup Khóa Kể Cả Coroutine Chưa Chạy (VIP/Login Vòng 2)

**Ngày thực hiện:** 2026-09-25  
**Gói thực hiện:** S02 — Quản lý dọn dẹp khóa đăng nhập qua completion của Job kể cả khi coroutine chưa chạy (giải quyết triệt để R02 / P2)  
**Trạng thái kết thúc:** Hoàn tất S02, dừng trước S03.  

---

## 1. Mục tiêu và phạm vi gói S02

Theo kế hoạch `PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md`:
- Khắc phục lỗi **R02 / P2**: Scope đã bị hủy trước khi launch (hoặc hủy trong quá trình await) khiến khối thân coroutine không thực thi hoặc không tới finally, làm cờ đăng nhập bị khóa vĩnh viễn và chặn mọi lần đăng nhập tiếp theo.
- Quản lý cleanup bằng completion handler (`job.invokeOnCompletion`) gắn với quyền sở hữu token `attemptToken`.
- Phân biệt coroutine kết thúc do chuyển sang classic fallback (`attemptToken.isFallbackActive()`) với việc attempt đăng nhập kết thúc: không mở khóa khi Intent fallback còn đang chờ xử lý (chặn double tap).
- Không dùng reset boolean vô điều kiện; chỉ request sở hữu khóa mới được giải phóng khóa (`activeLoginAttempt.compareAndSet(attemptToken, null)`).
- Đảm bảo khi attempt A bị hủy và attempt B bắt đầu, completion của A không mở khóa B.

---

## 2. Bảng đối chiếu kế hoạch vs File thực tế

| Yêu cầu kế hoạch S02 | File thực tế | Hành động | Trạng thái |
|---|---|---|---|
| Cờ `isFallbackActive` và method `markFallbackActive` | `app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt` | Sửa đổi | Hoàn thành |
| `job.invokeOnCompletion` có quyền sở hữu token, giữ khóa khi fallback Intent | `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` | Sửa đổi | Hoàn thành |
| Nghiệm thu probe R02 xanh và bổ sung 4 regression tests S02 | `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt` | Sửa đổi | Hoàn thành |
| Lập báo cáo nghiệm thu S02 | `REPORT_VIP_LOGIN_ROUND2_S02.md` | Tạo mới | Hoàn thành |

---

## 3. Kết quả chi tiết kiểm thử bộ `VipLoginRound2RegressionTest`

```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
```

- **Kết quả tổng thể:** 15 tests completed, 1 failed (R04), 14 passed.
- **Probe R02 (`probeCancelledScopeMustNotLockFutureSignIn`) đã XANH (PASSED)!**
- **Ca đỏ duy nhất còn lại là probe R04 (`probeDriveResultWithoutPendingRequestMustNotSucceed`) thuộc phạm vi gói S04.**

| Tên test | Loại | Kết quả | Ghi chú / Trạng thái |
|---|---|---|---|
| `probeCancelledScopeMustNotLockFutureSignIn` | R02 Probe | **PASSED** (Xanh) | Scope đã hủy không làm khóa sign-in vĩnh viễn. |
| `probeSessionInvalidationMustRejectPendingCredential` | R01 Probe | **PASSED** (Xanh) | Vô hiệu hóa phiên loại bỏ credential cũ (S01). |
| `probeSuccessCallbackFailureMustNotRestartLogin` | R03 Probe | **PASSED** (Xanh) | Lỗi UI callback không mở lại login Intent. |
| `controlValidSignIn_succeedsAndUpdatesUser` | Control | **PASSED** (Xanh) | Luồng đăng nhập hợp lệ cập nhật user thành công. |
| `controlValidDriveAuthorization_withPendingRequest_succeeds` | Control | **PASSED** (Xanh) | Cấp quyền Drive hợp lệ với pending request. |
| `regressionCanceledBeforeLaunch_releasesLockAndAllowsNextSignIn` | S02 Mới | **PASSED** (Xanh) | Scope hủy trước launch tự động giải phóng khóa, lần thử sau chạy bình thường. |
| `regressionCancelDuringAwait_releasesLockAndAllowsNextSignIn` | S02 Mới | **PASSED** (Xanh) | Hủy job trong lúc await giải phóng khóa, lần thử sau chạy bình thường. |
| `regressionFallbackWaitingIntent_blocksDoubleTapUntilResultHandled` | S02 Mới | **PASSED** (Xanh) | Fallback chờ Intent vẫn chặn double tap; giải phóng khóa khi result về. |
| `regressionAttemptACancelledThenAttemptBStarted_completionOfADoesNotUnlockB` | S02 Mới | **PASSED** (Xanh) | Attempt A bị hủy rồi B bắt đầu, completion của A không mở khóa B. |
| `regressionClassicSignInAfterCancel_isDiscardedAndUserNull` | S01 | **PASSED** (Xanh) | Classic result sau cancel bị loại bỏ. |
| `regressionStaleAttemptA_whileAttemptBInProgress_doesNotOverwriteOrUnlockB` | S01 | **PASSED** (Xanh) | Stale attempt không ghi đè phiên mới. |
| `regressionStaleException_doesNotTriggerFallback` | S01 | **PASSED** (Xanh) | Ngoại lệ từ stale attempt không mở fallback Intent. |
| `regressionValidSignIn_claimsGuestDocumentsOnce` | S01 | **PASSED** (Xanh) | Đăng nhập hợp lệ claim đúng guest documents. |
| `regressionInvalidatedCredential_doesNotMutatePrefsOrGuestDocuments` | S01 | **PASSED** (Xanh) | Invalidation không làm thay đổi documents và prefs. |
| `probeDriveResultWithoutPendingRequestMustNotSucceed` | R04 Probe | **FAILED** (Đỏ) | Dự kiến còn đỏ, thuộc phạm vi gói **S04**. |

---

## 4. Kết quả kiểm tra hồi quy các bộ test Identity/Auth hiện hữu

```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain
```

- **Exit code:** 0
- **Kết quả:** `BUILD SUCCESSFUL` (100% test passed).

---

## 5. Khối bàn giao bắt buộc

```text
Gói / baseline: S02 — Cleanup khóa kể cả coroutine chưa chạy
File có thay đổi sẵn và file vừa sửa:
  - File tạo mới: REPORT_VIP_LOGIN_ROUND2_S02.md
  - File sửa đổi: app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt, app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt, app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt
Production path thực sự được test: AppAuthManager.signInWithGoogle, job.invokeOnCompletion, attemptToken.markFallbackActive
Trước sửa: Probe probeCancelledScopeMustNotLockFutureSignIn thất bại do scope bị hủy trước khi launch khiến coroutine không chạy body và khóa isSignInInProgress bị giữ vĩnh viễn.
Sau sửa:
  - Command: $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"; .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
  - Exit code: 1 (kỳ vọng do còn giữ probe R04 chưa sửa cho đến gói S04)
  - Counts: 15 tests completed, 1 failed (R04), 14 passed (R01, R02, R03, 2 controls, 5 regression S01, 4 regression S02 đều xanh)
  - Report: REPORT_VIP_LOGIN_ROUND2_S02.md
Regression dự kiến còn đỏ thuộc gói sau:
  - R04: probeDriveResultWithoutPendingRequestMustNotSucceed (sẽ sửa ở S04)
Chưa chạy thiết bị/Play/Drive: Đã kiểm thử unit test JVM với mock parser, fake prefs và CoroutineScope Dispatchers.Unconfined; chưa chạy thiết bị Android thật / Google Play Services OAuth flow.
Phụ thuộc và điểm dừng: Đã hoàn tất S02. Dừng trước S03, không tự động làm S03.
```
