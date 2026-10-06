# Báo cáo Nghiệm thu S01 — Token Yêu Cầu Đăng Nhập và Vô Hiệu Hóa Phiên (VIP/Login Vòng 2)

**Ngày thực hiện:** 2026-09-25  
**Gói thực hiện:** S01 — Token yêu cầu đăng nhập và vô hiệu hóa phiên (giải quyết triệt để R01 / P1)  
**Trạng thái kết thúc:** Hoàn tất S01, dừng trước S02.  

---

## 1. Mục tiêu và phạm vi gói S01

Theo kế hoạch `PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md`:
- Khắc phục lỗi **R01 / P1**: `cancelSignInProgress` chỉ reset boolean và Credential Manager / classic Google Sign-In chỉ so sánh request generation mà không kiểm tra session generation, khiến callback credential hoặc intent cũ đến sau khi đăng xuất/hủy phiên vẫn ghi đè và lưu tài khoản.
- Tạo token bất biến `GoogleLoginAttempt(requestId, initialSessionGeneration)`.
- Serialize kiểm tra tính hợp lệ của token và commit tài khoản trên cùng cơ chế điều phối (`authStateLock`).
- Đảm bảo các hành động hủy/logout/đổi phiên vô hiệu hóa attempt đang chờ.
- Host (`MoreFragment`) lưu trữ `pendingSignInAttempt`, khôi phục an toàn qua `savedInstanceState`, truyền đúng token vào `handleGoogleSignInResult`.
- Chỉ attempt đang sở hữu trạng thái mới được giải phóng khóa (`compareAndSet(token, null)`), stale callback không mở khóa attempt mới.

---

## 2. Bảng đối chiếu kế hoạch vs File thực tế

| Yêu cầu kế hoạch S01 | File thực tế | Hành động | Trạng thái |
|---|---|---|---|
| Token yêu cầu bất biến `GoogleLoginAttempt` | `app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt` | Tạo mới | Hoàn thành |
| Quản lý vòng đời attempt, serialize check + mutation | `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` | Sửa đổi | Hoàn thành |
| Host lưu token, truyền token vào result, lưu saved state | `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` | Sửa đổi (chỉ phần auth launcher/result/state) | Hoàn thành |
| Cập nhật test caller cho `handleGoogleSignInResult` | `app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt` | Sửa đổi | Hoàn thành |
| Regression probe R01 xanh và bổ sung 5 ca kiểm thử S01 | `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt` | Sửa đổi | Hoàn thành |
| Lập báo cáo nghiệm thu S01 | `REPORT_VIP_LOGIN_ROUND2_S01.md` | Tạo mới | Hoàn thành |

---

## 3. Kết quả chi tiết kiểm thử bộ `VipLoginRound2RegressionTest`

```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
```

- **Kết quả tổng thể:** 11 tests completed, 2 failed (R02, R04), 9 passed.
- **R01 đã chuyển từ ĐỎ (FAILED) sang XANH (PASSED)!**

| Tên test | Loại | Kết quả | Chi tiết / Trạng thái |
|---|---|---|---|
| `probeSessionInvalidationMustRejectPendingCredential` | R01 Probe | **PASSED** (Xanh) | Credential cũ đến sau `nextSessionGeneration` và `cancelSignInProgress` bị loại bỏ hoàn toàn, `currentUser` null. |
| `probeSuccessCallbackFailureMustNotRestartLogin` | R03 Probe | **PASSED** (Xanh) | Không kích hoạt fallback login khi callback UI ném ngoại lệ hậu đăng nhập. |
| `controlValidSignIn_succeedsAndUpdatesUser` | Control | **PASSED** (Xanh) | Luồng đăng nhập hợp lệ vẫn cập nhật user bình thường. |
| `controlValidDriveAuthorization_withPendingRequest_succeeds` | Control | **PASSED** (Xanh) | Cấp quyền Drive hợp lệ với pending request. |
| `regressionClassicSignInAfterCancel_isDiscardedAndUserNull` | S01 Mới | **PASSED** (Xanh) | Intent classic result đến sau khi hủy sign-in bị hủy bỏ, không gọi `onSuccess`, không lưu prefs. |
| `regressionStaleAttemptA_whileAttemptBInProgress_doesNotOverwriteOrUnlockB` | S01 Mới | **PASSED** (Xanh) | Attempt A chậm trễ không ghi đè user B và không giải phóng khóa của Attempt B. |
| `regressionStaleException_doesNotTriggerFallback` | S01 Mới | **PASSED** (Xanh) | Ngoại lệ kỹ thuật từ attempt đã bị hủy/vô hiệu hóa không kích hoạt fallback Intent. |
| `regressionValidSignIn_claimsGuestDocumentsOnce` | S01 Mới | **PASSED** (Xanh) | Đăng nhập hợp lệ claim đúng các tài liệu guest unowned cho canonicalId. |
| `regressionInvalidatedCredential_doesNotMutatePrefsOrGuestDocuments` | S01 Mới | **PASSED** (Xanh) | Credential bị vô hiệu hóa không làm thay đổi owner tài liệu và không ghi SharedPreferences. |
| `probeCancelledScopeMustNotLockFutureSignIn` | R02 Probe | **FAILED** (Đỏ) | Dự kiến còn đỏ, thuộc phạm vi xử lý của gói **S02**. |
| `probeDriveResultWithoutPendingRequestMustNotSucceed` | R04 Probe | **FAILED** (Đỏ) | Dự kiến còn đỏ, thuộc phạm vi xử lý của gói **S04**. |

---

## 4. Kết quả kiểm tra hồi quy các bộ test Identity/Auth hiện hữu

```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.VipLoginContinuationTest --tests com.tscanner.app.DemoAccountIsolationTest --offline --console=plain
```

- **Exit code:** 0
- **Kết quả:** BUILD SUCCESSFUL, tất cả các bài test trong cả 6 test suites đều PASS 100%.

---

## 5. Khối bàn giao bắt buộc

```text
Gói / baseline: S01 — Token yêu cầu đăng nhập và vô hiệu hóa phiên
File có thay đổi sẵn và file vừa sửa:
  - File tạo mới: app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt, REPORT_VIP_LOGIN_ROUND2_S01.md
  - File sửa đổi: app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt, app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt, app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt, app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt
Production path thực sự được test: AppAuthManager.signInWithGoogle, AppAuthManager.commitSignedInAccount, AppAuthManager.handleGoogleSignInResult, AppAuthManager.cancelSignInProgress, AppAuthManager.signOut
Trước sửa: Probe probeSessionInvalidationMustRejectPendingCredential thất bại do credential cũ đến sau invalidation vẫn ghi đè UserProfile; classic sign-in không có token gắn kết.
Sau sửa:
  - Command: $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"; .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
  - Exit code: 1 (kỳ vọng do còn giữ R02 và R04 chưa sửa cho đến S02 và S04)
  - Counts: 11 tests completed, 2 failed (R02, R04), 9 passed (R01 đã xanh, R03 đã xanh, 2 controls xanh, 5 regression mới xanh)
  - Report: REPORT_VIP_LOGIN_ROUND2_S01.md
Regression dự kiến còn đỏ thuộc gói sau:
  - R02: probeCancelledScopeMustNotLockFutureSignIn (sẽ sửa ở S02)
  - R04: probeDriveResultWithoutPendingRequestMustNotSucceed (sẽ sửa ở S04)
Chưa chạy thiết bị/Play/Drive: Đã kiểm thử unit test JVM với mock parser, fake prefs và CoroutineScope Dispatchers.Unconfined; chưa chạy thiết bị Android thật / Google Play Services OAuth flow.
Phụ thuộc và điểm dừng: Đã hoàn tất S01. Dừng trước S02, không tự động làm S02.
```
