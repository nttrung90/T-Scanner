# Báo cáo V03: Tách đăng nhập và quyền Drive

- Ngày: 25/09/2026
- Repository: `E:\DU AN AI\T-Scanner`
- Module: `:app`
- Gói thực hiện: V03

---

## 1. Phạm vi thực hiện và ranh giới

### 1.1. Mục tiêu gói V03
Khắc phục sự phụ thuộc chéo giữa xác thực danh tính và quyền lưu trữ Drive:
1. `AppAuthManager.kt:213` trước đây xin `drive.file` scope ngay tại `getGoogleSignInIntent`. Điều này khiến luồng đăng nhập danh tính thông thường (hoặc fallback intent) bị áp đặt quyền truy cập Google Drive không cần thiết, làm tăng nguy cơ từ chối từ người dùng và vi phạm nguyên tắc cấp quyền tối thiểu (Least Privilege).
2. Tách bạch hoàn toàn:
   - `buildGoogleSignInOptions()`: chỉ xin thông tin danh tính (ID token, email, profile), tuyệt đối không chứa `DRIVE_FILE_SCOPE`.
   - `buildGoogleDriveSignInOptions(user)`: chỉ dùng khi cần cấp quyền lưu trữ đám mây, yêu cầu `DRIVE_FILE_SCOPE` và gắn tài khoản của người dùng hiện tại (`accountName`).
3. Khắc phục luồng hậu đăng nhập (`runPostAuthorizationSync`):
   - Người dùng Free (`!user.isVipActive`): tuyệt đối không kích hoạt sao lưu hay đồng bộ catalog Drive.
   - Người dùng VIP nhưng chưa cấp quyền Drive (`!hasDrivePermission(context)`): bỏ qua việc enqueue sao lưu, tránh lỗi xác thực chắc chắn sẽ xảy ra trên Worker nền.
   - Khi bị từ chối quyền Drive hoặc tài khoản cấp quyền không khớp trong `handleDrivePermissionResult`: gọi `onError`, tuyệt đối không đăng xuất tài khoản danh tính của người dùng.

### 1.2. Ranh giới tuân thủ
- **Đã sửa / tạo mới**:
  + `app/src/main/java/com/tscanner/app/utils/GoogleSignInResultRouter.kt` (Bổ sung `grantedScopes: Set<String>` vào `GoogleSignInAccountData` và trích xuất trong `DefaultGoogleSignInAccountParser`)
  + `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (`buildGoogleSignInOptions` không chứa Drive scope; `runPostAuthorizationSync` kiểm tra quyền Drive; `handleDrivePermissionResult` hỗ trợ parser injection, từ chối Drive không logout; `drivePermissionOverrideForTesting`)
  + `app/src/test/java/com/tscanner/app/GoogleIdentityOptionsTest.kt` (9 unit tests mới)
  + `REPORT_VIP_LOGIN_V03.md`
- **Không làm**:
  + Không đổi Web Client ID.
  + Không sửa dialog VIP hay điều hướng liên kết VIP của khách (dành cho V05).
  + Không sửa launcher cấp quyền Drive tại các callsite UI (dành cho V06).
  + Không xóa hay sửa cơ chế Demo account (dành cho V04).
  + Giữ nguyên các thay đổi có sẵn trong checkout.

---

## 2. Bằng chứng trước và sau khi sửa

### 2.1. Trước khi sửa
1. `getGoogleSignInIntent` tại `AppAuthManager.kt:218–226`:
```kotlin
fun getGoogleSignInIntent(context: Context): Intent {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(webClientId)
        .requestScopes(com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE)) // Xin thừa scope Drive
        .requestEmail()
        .requestProfile()
        .build()
    return GoogleSignIn.getClient(context, gso).signInIntent
}
```
2. `runPostAuthorizationSync` tại `AppAuthManager.kt:183–193`:
```kotlin
fun runPostAuthorizationSync(context: Context) {
    val user = _currentUser.value ?: return
    if (user.isVipActive) {
        val repo = DocumentRepo.getInstance(context)
        val unsynced = repo.getUnsyncedDocuments(user.id)
        if (unsynced.isNotEmpty()) {
            CloudBackupManager.enqueueBatchBackup(context, unsynced) // Đẩy job backup dù chưa có quyền Drive!
        }
        CloudBackupManager.syncCatalogFromDrive(context) {}
    }
}
```
- Khi người dùng đăng nhập bằng tài khoản VIP mới trên máy, nếu chưa cấp quyền Drive mà hệ thống tự động enqueue batch backup, Worker sẽ chạy ngầm và thất bại liên tục do thiếu `DRIVE_FILE_SCOPE`.
- Không thể test độc lập kết quả cấp quyền Drive trên môi trường JVM do phụ thuộc trực tiếp vào `GoogleSignIn.getSignedInAccountFromIntent`.

### 2.2. Sau khi sửa
1. **Tách biệt Options**:
   - `buildGoogleSignInOptions()`:
     ```kotlin
     fun buildGoogleSignInOptions(): GoogleSignInOptions {
         return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
             .requestIdToken(webClientId)
             .requestEmail()
             .requestProfile()
             .build()
     }
     ```
     Hoàn toàn không có `DRIVE_FILE_SCOPE`.
   - `getGoogleSignInIntent(context)` sử dụng `buildGoogleSignInOptions()`.
   - `buildGoogleDriveSignInOptions(user)` tiếp tục quản lý phạm vi `DRIVE_FILE_SCOPE` và ràng buộc `setAccountName(user.email)`.
2. **Bảo vệ Hậu đăng nhập (`runPostAuthorizationSync`)**:
   - Nhận thêm provider `hasDrivePermissionProvider: (Context) -> Boolean = { hasDrivePermission(it) }`.
   - Nếu `!user.isVipActive`: dừng ngay lập tức, không enqueue backup, không đồng bộ catalog.
   - Nếu `!hasDrivePermissionProvider(context)`: ghi nhận cảnh báo VIP chưa cấp quyền Drive và dừng ngay lập tức, không enqueue job hỏng.
3. **Độc lập phiên tài khoản khi xử lý kết quả Drive (`handleDrivePermissionResult`)**:
   - Bổ sung overload với `parser: GoogleSignInAccountParser`.
   - Nếu quyền Drive bị từ chối hoặc người dùng hủy: gọi `onError("Quyền truy cập Google Drive bị từ chối")`, tài khoản danh tính `_currentUser` được giữ nguyên vẹn (không bị đăng xuất).
   - Nếu tài khoản cấp quyền khác với tài khoản đang đăng nhập: báo lỗi không khớp tài khoản, giữ nguyên phiên người dùng hiện tại.

---

## 3. Lệnh thực thi và kết quả kiểm thử (Regression)

### 3.1. Các ca kiểm thử trong `GoogleIdentityOptionsTest` (9 tests)
1. `testBuildGoogleSignInOptions_doesNotContainDriveScope`: Xác nhận options đăng nhập danh tính không chứa bất kỳ scope Drive nào, yêu cầu đúng ID token và Web Client ID.
2. `testBuildGoogleDriveSignInOptions_withActiveUser_requestsDriveScope`: Xác nhận options Drive chứa `DRIVE_FILE_SCOPE`.
3. `testBuildGoogleDriveSignInOptions_withNullUser_requestsDriveScopeWithoutBoundAccount`: Xác nhận options Drive khi user null vẫn yêu cầu `DRIVE_FILE_SCOPE` mà không crash.
4. `testRunPostAuthorizationSync_freeUser_doesNotTriggerCloudBackup`: Tài khoản Free khi hoàn tất đăng nhập không truy vấn quyền Drive và không enqueue sao lưu.
5. `testRunPostAuthorizationSync_vipUserWithoutDrivePermission_doesNotEnqueueBackup`: Tài khoản VIP nhưng chưa cấp quyền Drive không enqueue sao lưu, bảo toàn tài liệu unsynced.
6. `testRunPostAuthorizationSync_vipUserWithDrivePermission_queriesUnsyncedDocs`: Tài khoản VIP đã cấp quyền Drive thực hiện kiểm tra và đẩy tài liệu chưa đồng bộ.
7. `testHandleDrivePermissionResult_denied_doesNotSignOutUser`: Khi bị từ chối quyền Drive, báo lỗi và giữ nguyên phiên đăng nhập người dùng.
8. `testHandleDrivePermissionResult_accountMismatch_doesNotSignOutUser`: Khi tài khoản cấp quyền khác với tài khoản đang đăng nhập, báo lỗi mismatch và giữ nguyên phiên người dùng.
9. `testHandleDrivePermissionResult_success_maintainsCurrentUser`: Khi cấp quyền thành công, giữ nguyên phiên người dùng và gọi `onSuccess`.

### 3.2. Lệnh chạy và kết quả kiểm thử toàn bộ Auth Suite
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests com.tscanner.app.GoogleIdentityOptionsTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleCredentialRequestFactoryTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain
  ```
- **Kết quả**:
  + `BUILD SUCCESSFUL in 38s`
  + `GoogleIdentityOptionsTest`: 9 tests, 0 failures, 0 errors
  + `AppAuthDriveAuthorizationTest`: 4 tests, 0 failures, 0 errors
  + `AppAuthCanonicalIdentityTest`: 7 tests, 0 failures, 0 errors
  + `GoogleLoginFlowTest`: 7 tests, 0 failures, 0 errors
  + `GoogleCredentialRequestFactoryTest`: 4 tests, 0 failures, 0 errors
  + `GoogleSignInResultRouterTest`: 11 tests, 0 failures, 0 errors
  + **Tổng cộng**: 42/42 unit tests PASS (0 failures, 0 errors, 0 skipped).

---

## 4. Giới hạn và phần chưa kiểm tra

- **Môi trường thiết bị thật**: Màn hình Google OAuth consent cấp quyền Drive và Intent trả về từ Google Play Services trên thiết bị thật chưa được kích hoạt trực tiếp qua ADB (do chưa có thiết bị kết nối).
- **Launcher cấp quyền Drive tại UI**: Việc nhận kết quả cấp quyền Drive ở các màn hình khác (HomeFragment, PdfViewerActivity, CreatePdfDialog) đang dùng startActivity không nhận kết quả (thuộc phạm vi gói V06).
- **Demo Account**: Lối vào tài khoản Demo khi đăng nhập thất bại vẫn còn xuất hiện tại UI (thuộc phạm vi gói V04).

---

## 5. Bàn giao cho gói V04

- **Phụ thuộc**: V01, V02, V03 đã hoàn thành và đạt đầy đủ tiêu chí nghiệm thu.
- **Nội dung gói tiếp theo (V04)**: Chặn Demo lấy tài liệu khách (`MoreFragment.showSignInErrorDialog` đang đề nghị đăng nhập Demo; `AppAuthManager.signInWithDemoAccount` gọi `claimGuestDocuments` bằng ID demo. Cần gỡ nút Demo khỏi luồng khắc phục lỗi đăng nhập và bảo vệ tài liệu khách/tài liệu thật).
