# Báo cáo V04: Chặn demo lấy tài liệu khách

- Ngày: 25/09/2026
- Repository: `E:\DU AN AI\T-Scanner`
- Module: `:app`
- Gói thực hiện: V04

---

## 1. Phạm vi thực hiện và ranh giới

### 1.1. Mục tiêu gói V04
Khắc phục lỗi mã xác nhận và rủi ro thất thoát dữ liệu do tài khoản Demo:
1. `MoreFragment.showSignInErrorDialog` trước đây hiển thị thông báo đề nghị người dùng đăng nhập tài khoản Demo khi Google Sign-In thất bại, và cung cấp nút hành động "Đăng nhập Demo".
2. `AppAuthManager.signInWithDemoAccount` trước đây gọi `DocumentRepo.getInstance(context).claimGuestDocuments(demoUser.id)`, tự động chiếm quyền sở hữu toàn bộ tài liệu khách (`ownerId == null`) gán sang tài khoản Demo `google_user_demo_1001`.
3. Hệ quả tiêu cực: Sau khi người dùng chuyển sang tài khoản Demo, các tài liệu thật được tạo trước đó bị đổi `ownerId` thành demo. Khi người dùng khắc phục xong lỗi Google Play Services và đăng nhập tài khoản Google thật, hàm `claimGuestDocuments` không thể nhận diện và chuyển giao lại các tài liệu này vì chúng không còn mang `ownerId == null`.
4. Mục tiêu V04:
   - Gỡ bỏ hoàn toàn nút Demo khỏi luồng xử lý lỗi đăng nhập production trên UI.
   - Cô lập hoàn toàn API `signInWithDemoAccount`: tuyệt đối không chiếm quyền tài liệu khách hoặc tài liệu của bất kỳ người dùng nào.
   - Bảo toàn tài liệu thật: Tài khoản Google thật khi đăng nhập chỉ nhận tài liệu khách (`ownerId == null`) và không tự ý chiếm đoạt tài liệu đã mang `ownerId` demo.

### 1.2. Ranh giới tuân thủ
- **Đã sửa / tạo mới**:
  + `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` (`showSignInErrorDialog` hiển thị thông báo lỗi trực tiếp, cung cấp nút Thử lại / Đóng; loại bỏ hoàn toàn nút Demo)
  + `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (`signInWithDemoAccount` loại bỏ lệnh `claimGuestDocuments`, cô lập hoàn toàn tài khoản demo)
  + `app/src/test/java/com/tscanner/app/DemoAccountIsolationTest.kt` (5 unit tests regression)
  + `REPORT_VIP_LOGIN_V04.md`
- **Không làm**:
  + Không xóa hay sửa đổi các tài liệu demo cũ đã lưu trên máy nếu có (xem mục 4).
  + Không sửa dialog VIP hay điều hướng liên kết VIP của khách (dành cho V05).
  + Không sửa launcher cấp quyền Drive tại các callsite UI (dành cho V06).
  + Giữ nguyên các thay đổi có sẵn trong checkout.

---

## 2. Bằng chứng trước và sau khi sửa

### 2.1. Trước khi sửa
1. Tại `MoreFragment.kt:289–303`:
```kotlin
private fun showSignInErrorDialog(errorMsg: String) {
    if (!isAdded) return

    AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_TScanner_Dialog)
        .setTitle(R.string.account_sign_in_title)
        .setMessage(getString(R.string.demo_account_prompt_format, errorMsg))
        .setPositiveButton(R.string.sign_in_demo_action) { _, _ ->
            AppAuthManager.signInWithDemoAccount(requireContext()) {
                Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                AppAuthManager.runPostAuthorizationSync(requireContext())
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
```
2. Tại `AppAuthManager.kt:549–570`:
```kotlin
fun signInWithDemoAccount(context: Context, onComplete: (UserProfile) -> Unit) {
    val demoUser = UserProfile(
        id = "google_user_demo_1001",
        email = "demo.scanner@gmail.com",
        ...
    )
    DocumentRepo.getInstance(context).claimGuestDocuments(demoUser.id) // <-- LẤY HẾT TÀI LIỆU KHÁCH!
    ...
}
```

### 2.2. Sau khi sửa
1. **`MoreFragment.kt`**:
   - `showSignInErrorDialog`: Chỉ hiển thị `errorMsg` trung tính, cung cấp nút **Thử lại** (`R.string.retry`) kích hoạt lại `performGoogleSignIn()` và nút **Đóng** (`R.string.close`).
   - Tuyệt đối không còn gợi ý hoặc đường dẫn kích hoạt tài khoản Demo từ luồng lỗi đăng nhập.
2. **`AppAuthManager.kt`**:
   - `signInWithDemoAccount`: Đã loại bỏ hoàn toàn dòng `claimGuestDocuments(demoUser.id)`.
   - Tài khoản Demo khi đăng nhập chỉ hoạt động như một phiên cô lập phục vụ kiểm thử nội bộ; tài liệu khách (`ownerId == null`) và tài liệu của các tài khoản khác (`user_a`, `user_b`) được giữ nguyên vẹn 100%.
   - Đăng nhập tài khoản Google thật (`processSignedInAccount`) tiếp tục duy trì nguyên tắc: chỉ claim tài liệu khách chưa có chủ sở hữu (`ownerId == null`), không ghi đè tài liệu của demo hoặc tài khoản khác.

---

## 3. Lệnh thực thi và kết quả kiểm thử (Regression)

### 3.1. Các ca kiểm thử trong `DemoAccountIsolationTest` (5 tests)
1. `testSignInWithDemoAccount_doesNotClaimGuestDocuments`: Xác nhận khi đăng nhập tài khoản Demo, tài liệu khách (`ownerId = null`) vẫn giữ nguyên `ownerId = null`, nội dung file không bị biến đổi.
2. `testSignInWithDemoAccount_doesNotMutateUserADocuments`: Xác nhận tài liệu của User A (`ownerId = "user_alice_canonical"`) không bị thay đổi quyền sở hữu hay nội dung khi gọi demo.
3. `testSignInWithDemoAccount_doesNotMutateUserBDocuments`: Xác nhận tài liệu của User B không bị thay đổi quyền sở hữu hay nội dung khi gọi demo.
4. `testRealGoogleSignIn_claimsGuestDocumentsEvenAfterDemoLoginWasTriggered`: Xác nhận luồng thực tế: Người dùng có tài liệu khách -> Demo đăng nhập -> Người dùng đăng nhập tài khoản Google thật (`112233445566`) -> Tài liệu khách được chuyển quyền sở hữu thành công sang tài khoản Google thật.
5. `testRealGoogleSignIn_doesNotClaimOrStealExistingDemoDocuments`: Xác nhận nếu có tài liệu đã mang `ownerId = "google_user_demo_1001"`, tài khoản Google thật đăng nhập sẽ không tự ý chiếm đoạt hay ghi đè.

### 3.2. Lệnh chạy và kết quả kiểm thử toàn bộ Auth Suite
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests com.tscanner.app.DemoAccountIsolationTest --tests com.tscanner.app.GoogleIdentityOptionsTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.AppAuthCanonicalIdentityTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleCredentialRequestFactoryTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain
  ```
- **Kết quả**:
  + `BUILD SUCCESSFUL in 58s`
  + `DemoAccountIsolationTest`: 5 tests, 0 failures, 0 errors
  + `GoogleIdentityOptionsTest`: 9 tests, 0 failures, 0 errors
  + `AppAuthDriveAuthorizationTest`: 4 tests, 0 failures, 0 errors
  + `AppAuthCanonicalIdentityTest`: 7 tests, 0 failures, 0 errors
  + `GoogleLoginFlowTest`: 7 tests, 0 failures, 0 errors
  + `GoogleCredentialRequestFactoryTest`: 4 tests, 0 failures, 0 errors
  + `GoogleSignInResultRouterTest`: 11 tests, 0 failures, 0 errors
  + **Tổng cộng**: 47/47 unit tests PASS (0 failures, 0 errors, 0 skipped).

---

## 4. Phương án phục hồi tài liệu Demo cũ (nếu có trên thiết bị người dùng)

Trong trường hợp người dùng trên các bản cài trước đó đã bấm "Đăng nhập Demo" và bị gắn `ownerId = "google_user_demo_1001"` vào tài liệu cá nhân:
1. **Nguyên tắc**: Không tự động chuyển toàn bộ tài liệu có `ownerId == "google_user_demo_1001"` sang tài khoản Google bất kỳ vừa đăng nhập để tránh gán nhầm dữ liệu giữa nhiều người cùng dùng thiết bị.
2. **Quy trình phục hồi an toàn (khi cần triển khai riêng)**:
   - Có thể cung cấp tính năng "Khôi phục tài liệu Demo" trong mục Cài đặt nâng cao / Quản lý tài liệu.
   - Người dùng được hiển thị danh sách tài liệu mang owner demo và chủ động chọn "Chuyển vào tài khoản hiện tại" hoặc "Chuyển thành tài liệu cục bộ (Guest)".
   - Không thực hiện tự động và không xóa bỏ các tài liệu này.

---

## 5. Bàn giao cho gói V05

- **Phụ thuộc**: V01, V02, V03, V04 đã hoàn thành và đạt đầy đủ tiêu chí nghiệm thu.
- **Nội dung gói tiếp theo (V05)**: Nối nút VIP của khách tới đăng nhập (`VipUpgradeDialog` khi người dùng là khách hiện tại chỉ hiển thị Toast và đóng dialog, Home mở dialog trực tiếp; cần thêm callback yêu cầu đăng nhập rõ ràng, điều hướng đăng nhập bảo vệ lifecycle, và quay lại bước xác nhận kích hoạt sau khi đăng nhập thành công).
