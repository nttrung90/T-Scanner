# Báo cáo V01: Không bỏ qua lỗi đăng nhập

- Ngày: 25/09/2026
- Repository: `E:\DU AN AI\T-Scanner`
- Module: `:app`
- Gói thực hiện: V01

---

## 1. Phạm vi thực hiện và ranh giới

### 1.1. Mục tiêu gói V01
Khắc phục lỗi mã xác nhận L01: `MoreFragment.kt` chỉ kiểm tra `resultCode == Activity.RESULT_OK`, bỏ qua toàn bộ kết quả thất bại từ Google Play Services (vốn thường trả về `RESULT_CANCELED` kèm Intent mang mã lỗi `ApiException` như mã 10 DEVELOPER_ERROR, 12500, v.v.). Đưa toàn bộ kết quả có dữ liệu vào bộ phân tích, phân biệt rõ ràng giữa thao tác hủy của người dùng và lỗi xác thực/cấu hình.

### 1.2. Ranh giới tuân thủ
- **Đã sửa**:
  + `app/src/main/java/com/tscanner/app/utils/GoogleSignInResultRouter.kt` (Seam mới trong production điều phối kết quả đăng nhập)
  + `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (Bổ sung overload nhận `resultCode`, `data`, `parser`, `onCancelled`)
  + `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` (Bỏ gate lọc cứng `RESULT_OK`, chuyển toàn bộ kết quả qua router)
  + `app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt` (11 unit test regression)
  + `app/src/main/res/values*/strings.xml` (8 locale: `values`, `values-vi`, `values-de`, `values-es`, `values-fr`, `values-in`, `values-ja`, `values-pt`)
- **Không làm**:
  + Không đổi Web Client ID, không đổi scope hay options (dành cho V02/V03).
  + Không sửa cơ chế VIP, không đổi đường Demo (dành cho V04/V05).
  + Không sửa Drive authorization launcher (dành cho V06).
  + Không sửa logic sao lưu đám mây hay Gradle dependencies.

---

## 2. Bằng chứng trước và sau khi sửa

### 2.1. Trước khi sửa
Tại `MoreFragment.kt:44–58`:
```kotlin
googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        AppAuthManager.handleGoogleSignInResult(...)
    }
}
```
- Khi Google Play Services trả về lỗi (ví dụ mã 10 do cấu hình SHA-1/OAuth hoặc mã 12500), Activity result mang `resultCode = Activity.RESULT_CANCELED` (0) cùng dữ liệu lỗi nằm trong `result.data`.
- Lệnh `if (result.resultCode == Activity.RESULT_OK)` khiến toàn bộ nhánh lỗi bị bỏ qua: không hiển thị thông báo, người dùng tưởng ứng dụng bị đơ/không phản hồi.
- Tại `AppAuthManager.kt:265`, thông báo mã 10 khẳng định phiến diện: `"Mã 10: SHA-1 debug chưa khớp hoặc chưa thêm email vào Test users"` (không phù hợp với bản cài Google Play phát hành).

### 2.2. Sau khi sửa
1. **Seam `GoogleSignInResultRouter`**:
   - `routeResult(context, resultCode, data, parser)`:
     + Khi `data == null`: Nếu `resultCode == Activity.RESULT_CANCELED` -> `GoogleSignInResult.Cancelled` (kết thúc yên lặng). Nếu `resultCode != Activity.RESULT_CANCELED` -> `GoogleSignInResult.Failure` (báo lỗi dữ liệu trống, không crash, không báo thành công).
     + Khi `data != null`: Đưa vào parser để trích xuất `ApiException`.
     + Nếu `statusCode == 12501` (SIGN_IN_CANCELLED) -> `GoogleSignInResult.Cancelled` (kết thúc yên lặng).
     + Nếu `statusCode == 10` (DEVELOPER_ERROR) -> `GoogleSignInResult.Failure(statusCode = 10, errorMessage = ...)` với thông điệp trung tính: *"Lỗi cấu hình dịch vụ Google (Mã lỗi: 10). Vui lòng kiểm tra lại cấu hình ứng dụng trên Google Play hoặc Google Cloud Console."*
     + Nếu `statusCode == 12500` -> `GoogleSignInResult.Failure(statusCode = 12500, ...)` với thông báo lỗi xác thực Google Play.
     + Các mã lỗi khác -> hiển thị mã lỗi cụ thể qua format string.
   - `dispatchResult(...)`: Sử dụng `AtomicBoolean` bảo đảm callback chỉ được gọi duy nhất 1 lần (ngăn chặn double invocation).
2. **`AppAuthManager.handleGoogleSignInResult`**:
   - Hỗ trợ `resultCode: Int`, `data: Intent?`, `parser: GoogleSignInAccountParser`.
   - Xử lý `processSignedInAccount` an toàn, duy trì bảo toàn phiên, cập nhật canonical ID (`sub`) và LiveData.
   - Giữ overload cũ tương thích ngược.
3. **`MoreFragment.kt`**:
   - `googleSignInLauncher` gọi `AppAuthManager.handleGoogleSignInResult` cho mọi kết quả.
   - Kiểm tra `isAdded` trước khi cập nhật giao diện hoặc mở dialog lỗi để bảo vệ Fragment lifecycle.
   - Bổ sung callback `onCancelled`: người dùng hủy hoặc bấm ngoài dialog thì kết thúc yên lặng, không báo lỗi giả.
4. **Bảo mật và an toàn dữ liệu**:
   - Không ghi log idToken hoặc nội dung credential; chỉ ghi mã status code và message của ngoại lệ.
   - Bảo toàn các thay đổi có sẵn trong checkout.

---

## 3. Lệnh thực thi và kết quả kiểm thử (Regression)

### 3.1. Các ca kiểm thử bắt buộc đã phủ trong `GoogleSignInResultRouterTest`
1. `testRouteResult_success_returnsSuccessWithAccountData`: Đăng nhập thành công trả về `Success` với đầy đủ thông tin tài khoản.
2. `testRouteResult_resultCanceledWithStatus10_returnsFailureWithNeutralConfigError`: `RESULT_CANCELED` kèm Intent chứa mã 10 trả về `Failure`, hiển thị mã 10, thông điệp trung tính không khẳng định SHA-1 debug.
3. `testRouteResult_resultCanceledWithStatus12501_returnsCancelled`: Mã 12501 chuyển thành `Cancelled`.
4. `testRouteResult_nullIntent_resultCanceled_returnsCancelledSilently`: `RESULT_CANCELED` và Intent null chuyển thành `Cancelled` yên lặng.
5. `testRouteResult_nullIntent_resultOk_returnsFailureWithoutCrash`: `RESULT_OK` nhưng Intent null không crash và báo `Failure`.
6. `testRouteResult_parserThrowsGenericException_returnsFailureWithoutCrash`: Lỗi parser ngoại lệ không xác định không làm crash ứng dụng, báo `Failure`.
7. `testRouteResult_parserReturnsNull_returnsFailure`: Parser trả về null báo `Failure`.
8. `testDispatchResult_guaranteesSingleInvocation_neverCallsTwice`: Bảo đảm atomic single-call, không bị gọi đúp callback.
9. `testAppAuthManager_handleGoogleSignInResult_withStatus10Intent_callsOnErrorAndDoesNotChangeUser`: Khi gặp lỗi 10, gọi `onError`, không đổi session và không đổi user.
10. `testAppAuthManager_handleGoogleSignInResult_with12501_callsOnCancelled`: Khi hủy 12501, gọi `onCancelled`, không gọi `onError` hay `onSuccess`.
11. `testAppAuthManager_handleGoogleSignInResult_success_updatesCurrentUserAndCallsOnSuccess`: Khi thành công, cập nhật canonical ID trong `AppAuthManager.currentUser` và gọi `onSuccess`.

### 3.2. Lệnh chạy và kết quả kiểm thử
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain
  ```
- **Kết quả**:
  + `BUILD SUCCESSFUL in 5s`
  + `AppAuthCanonicalIdentityTest`: 7 tests, 0 failures, 0 errors
  + `AppAuthDriveAuthorizationTest`: 4 tests, 0 failures, 0 errors
  + `GoogleSignInResultRouterTest`: 11 tests, 0 failures, 0 errors
  + **Tổng cộng**: 22/22 unit tests PASS (0 failures, 0 errors, 0 skipped).
- **File báo cáo XML**:
  + `app/build/test-results/testDebugUnitTest/TEST-com.tscanner.app.GoogleSignInResultRouterTest.xml`
  + `app/build/test-results/testDebugUnitTest/TEST-com.tscanner.app.AppAuthCanonicalIdentityTest.xml`
  + `app/build/test-results/testDebugUnitTest/TEST-com.tscanner.app.AppAuthDriveAuthorizationTest.xml`

---

## 4. Giới hạn và phần chưa kiểm tra

- **Môi trường thiết bị thật**: Chưa kiểm tra được Intent sinh ra từ Google Play Services thực tế trên thiết bị người dùng (do chưa có thiết bị kết nối qua ADB).
- **Phần SDK chưa chạy trong JVM**: `DefaultGoogleSignInAccountParser` gọi `GoogleSignIn.getSignedInAccountFromIntent` của GMS SDK; trên JVM, việc kiểm thử được thực hiện qua seam `GoogleSignInAccountParser` với các trường hợp dữ liệu thực tế tương ứng.
- **Credential Manager**: Gói V01 chỉ sửa luồng tiếp nhận kết quả Google Sign-In; chưa sửa request Credential Manager (L02) và luồng fallback kép (thuộc phạm vi gói V02).

---

## 5. Bàn giao cho gói V02

- **Phụ thuộc**: V01 đã hoàn thành và đạt đầy đủ tiêu chí nghiệm thu.
- **Nội dung gói tiếp theo (V02)**: Sửa request Credential Manager và vòng đời đăng nhập (`AppAuthManager.kt:306–307` đang trộn lẫn `GetSignInWithGoogleOption` và `GetGoogleIdOption`; chuẩn hóa thứ tự gọi, chống nhấn đúp tạo nhiều phiên và fallback tối đa 1 lần).
