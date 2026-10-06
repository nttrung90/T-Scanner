# BÁO CÁO GÓI G00 — ĐƯA 4 PROBE VÒNG 3 THÀNH REGRESSION TEST LÂU DÀI

## 1. Baseline & Status trước khi thực hiện
- **Branch/Status:** Trên nhánh `master`, working tree giữ nguyên các thay đổi chưa commit/staged hiện có, không chạy git reset hay clean.
- **Thư mục bằng chứng:** Bảo toàn nguyên vẹn `build/vip-login-reaudit3` (`VipLoginRound3ProbeTest.kt`, `probe-results.xml`, `baseline-results`).
- **Production:** Tuyệt đối không thay đổi mã production trong gói này.

## 2. File thay đổi
- **File mới:** `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`
- **File khác:** Không chỉnh sửa bất kỳ file nào khác trong `app/src/main` hoặc `app/src/test`.

## 3. Production paths được test
- `AppAuthManager.handleGoogleSignInResult` với `attempt` phục hồi (reconstructed `GoogleLoginAttempt`) khi `resultCode == Activity.RESULT_CANCELED` (T01).
- `AppAuthManager.handleGoogleSignInResult` với `attempt == null` khi có attempt khác đang active trong hệ thống (T02).
- `AppAuthManager.handleDrivePermissionResult` với `DriveAuthorizationAttempt` trước và sau khi serialize/deserialize qua Java Object Streams (T03).
- `AppAuthManager.signOut` với coroutine cleanup trễ chạy trên dispatcher hàng đợi sau khi user B đã đăng nhập thành công (T04).
- Các control paths:
  - `controlValidGoogleSignInRequestToken_succeeds`: Kết quả đăng nhập Google hợp lệ với request token active giải phóng khóa và cập nhật user.
  - `controlValidDriveRequestToken_succeeds`: Kết quả phân quyền Google Drive hợp lệ với request token active thành công.
  - `controlNormalSignOut_clearsCurrentUserAndPreferences`: Đăng xuất bình thường xóa sạch user hiện tại và preferences.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Kết quả:** `7 tests completed, 4 failed` (BUILD FAILED as expected for G00 baseline).
- **Chi tiết 4 ca regression đỏ (T01 - T04):**
  1. `probeRecreatedLoginCancelReleasesAttempt` (T01):
     - *Assertion:* `java.lang.AssertionError: Recreated host cancellation must release active attempt`
     - *Nguyên nhân:* CAS trong `AppAuthManager.cancelSignInProgress` dùng so sánh danh tính đối tượng (`===`), trong khi Activity khôi phục tạo instance mới.
  2. `probeMissingHostTokenMustNotBorrowActiveAttempt` (T02):
     - *Assertion:* `java.lang.AssertionError: Uncorrelated host result must not borrow another attempt expected null, but was:<UserProfile(id=old, email=old@example.com...)>`
     - *Nguyên nhân:* Fallback `attempt ?: activeLoginAttempt.get()` tự ý mượn attempt của host khác đang chờ.
  3. `probeCopiedDriveAttemptCannotBeConsumedTwice` (T03):
     - *Assertion:* `java.lang.AssertionError: Single-consume must bind request identity across host reconstruction expected:<1> but was:<2>`
     - *Nguyên nhân:* Biến `consumed` đánh dấu `@Transient` trong `DriveAuthorizationAttempt`, khi deserialize không duy trì trạng thái đã consume trên bộ nhớ tập trung.
  4. `probeLateSignOutMustPreserveNewLogin` (T04):
     - *Assertion:* `java.lang.AssertionError: Old logout completion must not erase the new session expected:<b> but was:<null>`
     - *Nguyên nhân:* Coroutine cleanup trong `finally` của `signOut` gọi `clearSavedUser` và `_currentUser.value = null` vô điều kiện mà không kiểm tra session generation / user id.
- **Chi tiết 3 ca controls xanh:**
  - `controlValidGoogleSignInRequestToken_succeeds`: PASSED
  - `controlValidDriveRequestToken_succeeds`: PASSED
  - `controlNormalSignOut_clearsCurrentUserAndPreferences`: PASSED

## 5. Regression còn đỏ cho các gói tiếp theo
- G01 giải quyết T01 (`probeRecreatedLoginCancelReleasesAttempt`).
- G02 giải quyết T02 (`probeMissingHostTokenMustNotBorrowActiveAttempt`).
- G03 giải quyết T04 (`probeLateSignOutMustPreserveNewLogin`).
- G04 giải quyết T03 (`probeCopiedDriveAttemptCannotBeConsumedTwice`).

## 6. Runtime chưa chạy
- Runtime Google Play Track / OAuth client production.
- Process recreation / activity destruction trên thiết bị thật.

## 7. Điểm dừng gói G00
Hoàn tất G00. Đã thiết lập harness regression test chính thức, ghi nhận 4 ca đỏ đúng assertion và 3 controls xanh.
