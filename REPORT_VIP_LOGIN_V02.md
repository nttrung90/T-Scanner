# Báo cáo V02: Sửa request Credential Manager và vòng đời đăng nhập

- Ngày: 25/09/2026
- Repository: `E:\DU AN AI\T-Scanner`
- Module: `:app`
- Gói thực hiện: V02

---

## 1. Phạm vi thực hiện và ranh giới

### 1.1. Mục tiêu gói V02
Khắc phục lỗi mã xác nhận L02 và các vấn đề vòng đời đăng nhập:
1. `AppAuthManager.kt:306–307` trước đây ghép cả `GetSignInWithGoogleOption` và `GetGoogleIdOption` trong cùng một `GetCredentialRequest`. Theo tài liệu Google Codelabs ("Sign in with Google on Android"), luồng đăng nhập nút bấm tường minh (explicit button) chỉ được chứa duy nhất 1 option `GetSignInWithGoogleOption`.
2. Chuẩn hóa luồng và thứ tự gọi đăng nhập: Thử Credential Manager trước, nếu gặp lỗi kỹ thuật thì fallback sang Google Sign-In intent tối đa 1 lần; nếu người dùng chủ động hủy (hoặc coroutine scope bị hủy theo vòng đời), không fallback sang intent.
3. Chống nhấn đúp liên tục (atomic debounce) tạo nhiều phiên đồng thời; loại bỏ stale completion từ phiên cũ; kiểm tra vòng đời Activity (`!isFinishing && !isDestroyed`) trước khi gọi UI callbacks.

### 1.2. Ranh giới tuân thủ
- **Đã sửa / tạo mới**:
  + `app/src/main/java/com/tscanner/app/utils/GoogleCredentialRequestFactory.kt` (Seam mới tạo `GetCredentialRequest` chuẩn hóa và adapter `GoogleCredentialClient`)
  + `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (Debounce, tracking generation, single-fallback, kiểm tra Activity lifecycle, inject `GoogleCredentialClient` và `CoroutineDispatcher`)
  + `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` (`performGoogleSignIn` gọi Credential Manager với fallback sang Intent launcher tối đa 1 lần)
  + `app/src/test/java/com/tscanner/app/GoogleCredentialRequestFactoryTest.kt` (4 unit tests)
  + `app/src/test/java/com/tscanner/app/GoogleLoginFlowTest.kt` (7 unit tests)
- **Không làm**:
  + Không đổi Web Client ID, không đổi server client ID.
  + Không tách scope Drive ở gói này (dành cho V03).
  + Không sửa VIP hay đường Demo (dành cho V04/V05).
  + Không sửa Drive authorization launcher (dành cho V06).
  + Không cập nhật dependency bên ngoài phạm vi.

---

## 2. Bằng chứng trước và sau khi sửa

### 2.1. Trước khi sửa
1. `AppAuthManager.kt`:
```kotlin
val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
val googleIdOption = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(serverClientId).build()
val request = GetCredentialRequest.Builder()
    .addCredentialOption(signInWithGoogleOption)
    .addCredentialOption(googleIdOption)
    .build()
```
- Ghép sai 2 option trong một request cho explicit button sign-in.
- Không có cơ chế debounce: người dùng nhấn nhanh nhiều lần có thể tạo ra nhiều coroutine gọi đồng thời, kết quả của phiên trước có thể ghi đè phiên sau.
- Khi người dùng bấm Cancel trong Credential Manager (`GetCredentialCancellationException`), ứng dụng vẫn có thể rơi vào catch chung và fallback mở Google Sign-In intent, gây phiền hà và bất thường trải nghiệm.
- `MoreFragment.kt` gọi thẳng `googleSignInLauncher` (legacy Intent) mà không đi qua Credential Manager.

### 2.2. Sau khi sửa
1. **`GoogleCredentialRequestFactory`**:
   - `createExplicitButtonRequest(serverClientId, nonce)`: Chỉ tạo và thêm duy nhất `GetSignInWithGoogleOption`. Tuyệt đối không thêm `GetGoogleIdOption`.
   - Seam `GoogleCredentialClient` cung cấp abstraction `getCredential(activity, request)` cho phép unit test deterministic trên JVM.
2. **`AppAuthManager.signInWithGoogle`**:
   - Sử dụng `AtomicBoolean(isSignInInProgress)`: nếu đang có tiến trình đăng nhập thì từ chối gọi đúp.
   - Sử dụng `AtomicInteger(signInRequestGeneration)`: đánh dấu thế hệ request để loại bỏ kết quả cũ (stale callback).
   - Fallback có điều kiện và tối đa 1 lần qua `AtomicBoolean(fallbackTriggered)`:
     + `GetCredentialCancellationException`: Người dùng hủy -> gọi `onCancelled()`, không fallback.
     + `CancellationException`: Coroutine bị hủy do lifecycle -> rethrow, không fallback.
     + Lỗi kỹ thuật (`NoCredentialException`, `GetCredentialException`, generic Exception): gọi `onFallbackToIntent()`, fallback tối đa 1 lần.
   - Kiểm tra `!activity.isFinishing && !activity.isDestroyed` trước khi gọi UI callbacks.
   - Overload hỗ trợ injection `credentialClient` và `mainDispatcher: CoroutineDispatcher = Dispatchers.Main`.
   - `handleGoogleSignInResult` reset `isSignInInProgress = false` trong khối `finally`.
3. **`MoreFragment.kt`**:
   - `performGoogleSignIn()` gọi `AppAuthManager.signInWithGoogle(...)`.
   - Nhánh `onFallbackToIntent` khởi động `googleSignInLauncher`.
   - Nhánh `onCancelled` kết thúc yên lặng, không báo lỗi.
   - Nhánh `onError` hiển thị dialog lỗi hoặc toast trung tính (đã chuẩn hóa từ V01).

---

## 3. Lệnh thực thi và kết quả kiểm thử (Regression)

### 3.1. Các ca kiểm thử bắt buộc đã phủ

**Trong `GoogleCredentialRequestFactoryTest` (4 tests):**
1. `testCreateExplicitButtonRequest_containsSingleSignInWithGoogleOption`: Kiểm tra request chứa đúng 1 option `GetSignInWithGoogleOption`.
2. `testCreateExplicitButtonRequest_doesNotContainGoogleIdOption`: Khẳng định không chứa `GetGoogleIdOption`.
3. `testCreateExplicitButtonRequest_credentialOptionsSizeIsExactlyOne`: Tổng số credential options trong request chính xác bằng 1.
4. `testCreateExplicitButtonRequest_preservesServerClientIdAndNonce`: Đúng clientId và nonce.

**Trong `GoogleLoginFlowTest` (7 tests):**
1. `testSignInWithGoogle_successfulCredentialManager_updatesSessionAndCallsSuccess`: Đăng nhập thành công qua Credential Manager cập nhật session, reset flag tiến trình và gọi `onSuccess`.
2. `testSignInWithGoogle_userCancellation_callsOnCancelled_neverFallbacks`: Khi người dùng hủy, chỉ gọi `onCancelled`, không fallback sang intent.
3. `testSignInWithGoogle_technicalError_triggersFallbackAtMostOnce`: Khi gặp lỗi kỹ thuật, kích hoạt fallback sang intent tối đa 1 lần.
4. `testSignInWithGoogle_doubleTap_rejectedWhileInProgress`: Nhấn đúp khi đang đăng nhập bị từ chối và không khởi chạy phiên thứ hai.
5. `testSignInWithGoogle_scopeCancellation_rethrowsAndDoesNotInvokeUiCallbacks`: Khi coroutine scope bị cancel theo lifecycle, rethrow và không gọi UI callbacks.
6. `testSignInWithGoogle_activityFinishingOrDestroyed_doesNotInvokeUiCallbacks`: Khi Activity đã hủy hoặc đang kết thúc, không gọi UI callbacks để bảo vệ lifecycle.
7. `testSignInWithGoogle_staleGenerationCompletion_doesNotOverwriteNewerSession`: Stale completion từ request cũ không ghi đè session mới hơn.

### 3.2. Lệnh chạy và kết quả kiểm thử
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleCredentialRequestFactoryTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --offline --console=plain
  ```
- **Kết quả**:
  + `BUILD SUCCESSFUL in 18s`
  + `GoogleCredentialRequestFactoryTest`: 4 tests, 0 failures, 0 errors
  + `GoogleLoginFlowTest`: 7 tests, 0 failures, 0 errors
  + `GoogleSignInResultRouterTest`: 11 tests, 0 failures, 0 errors
  + `AppAuthCanonicalIdentityTest`: 5 tests, 0 failures, 0 errors
  + `AppAuthDriveAuthorizationTest`: 6 tests, 0 failures, 0 errors
  + **Tổng cộng**: 33/33 unit tests PASS (0 failures, 0 errors, 0 skipped).

---

## 4. Giới hạn và phần chưa kiểm tra

- **Môi trường thiết bị thật**: Credential Manager UI tương tác và GMS UI của Play Services chưa chạy trên thiết bị thật (do chưa có thiết bị kết nối qua ADB).
- **Drive Scope**: Luồng đăng nhập hiện tại chưa tách biệt hoàn toàn phạm vi danh tính và quyền Drive (thuộc phạm vi gói V03).
- **Vòng lặp tái kích hoạt**: Chưa kiểm tra tương tác khi xoay màn hình giữa lúc popup Credential Manager đang mở trên thiết bị thật.

---

## 5. Bàn giao cho gói V03

- **Phụ thuộc**: V01 và V02 đã hoàn thành và đạt đầy đủ tiêu chí nghiệm thu.
- **Nội dung gói tiếp theo (V03)**: Tách đăng nhập danh tính và quyền Drive (`AppAuthManager.kt:213` đang yêu cầu `drive.file` ngay tại `getGoogleSignInIntent`; cần loại bỏ `drive.file` khỏi options đăng nhập danh tính, giữ lại ở `getGoogleDriveSignInIntent`, bảo đảm người dùng Free không tự ý kích hoạt sao lưu khi chỉ đăng nhập danh tính).
