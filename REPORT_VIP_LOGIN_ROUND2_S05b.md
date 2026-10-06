# BÁO CÁO NGHIỆM THU GÓI S05b — VIP LOGIN TẠI ID CARD

**Dự án**: T-Scanner (:app)  
**Thời gian thực hiện**: 2026-09-25  
**Tiến độ tổng thể**: S00 (Xong) → S01 (Xong) → S02 (Xong) → S03 (Xong) → S04 (Xong) → S05a (Xong) → **S05b (Hoàn thành)** → S06a → S06b → S07 → S08 → S09  

---

## 1. Mục tiêu và phạm vi gói S05b

- Khắc phục triệt để lỗi **R05** tại màn hình ghép thẻ CCCD / ID Card (`IdCardComposeActivity.kt`) theo tài liệu `RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md`:
  * Trước S05b: Tại `IdCardComposeActivity.kt`, khi người dùng bấm vào nút VIP Watermark hoặc gạt switch tắt watermark, `VipUpgradeDialog` được tạo mà không truyền callback `onRequestSignIn`.
  * Khách (guest) khi muốn bỏ watermark chỉ nhận được Toast thông báo yêu cầu đăng nhập. Không có đường dẫn trực tiếp để đăng nhập tại chỗ; nếu thoát màn hình để đăng nhập từ bên ngoài thì mất toàn bộ phiên ghép thẻ (ảnh mặt trước, ảnh mặt sau đã cắt/chụp và các cấu hình bố cục).
- Thực hiện thiết kế bảo toàn phiên ghép thẻ và tiếp tục đăng nhập (Session Preservation & Login Continuation):
  * Tích hợp máy trạng thái `VipLoginContinuationHandler` và `GoogleLoginAttempt` vào `IdCardComposeActivity`.
  * Đăng ký `googleSignInFallbackLauncher` để tiếp nhận kết quả Google Sign-In fallback intent an toàn vòng đời.
  * Giữ nguyên ảnh 2 mặt CCCD (`frontImagePath`, `backImagePath`), chế độ bố cục (`layoutMode`), tỷ lệ (`scaleMode`), đường viền cắt (`showCutBorder`) và trạng thái watermark thông qua `IdCardSessionDraft`.
  * Sau khi đăng nhập Google thành công: mở lại `VipUpgradeDialog` đúng **một lần duy nhất** để người dùng chủ động xác nhận dùng thử VIP. Tuyệt đối không tự động gỡ watermark hoặc tự kích hoạt VIP ngầm.
  * Khi người dùng xác nhận kích hoạt VIP trong dialog: gỡ watermark (`addWatermark = false`), cập nhật UI badge và re-render ảnh ghép xem trước không còn watermark.
  * Khi người dùng hủy hoặc đăng nhập lỗi: dọn dẹp cờ tiếp tục, giữ nguyên ảnh hai mặt và các tùy chọn, không làm gián đoạn trải nghiệm người dùng.
  * Toàn bộ trạng thái phiên tiếp tục đăng nhập được bảo toàn qua `onSaveInstanceState` và khôi phục an toàn trong `onCreate`.

---

## 2. Các thay đổi chi tiết

### 2.1. Cập nhật `IdCardComposeActivity.kt`
- Thêm các thuộc tính quản trị đăng nhập và continuation:
  ```kotlin
  private val vipContinuationHandler = VipLoginContinuationHandler()
  private var pendingSignInAttempt: GoogleLoginAttempt? = null
  ```
- Đăng ký launcher `googleSignInFallbackLauncher` với `ActivityResultContracts.StartActivityForResult()`:
  * Gọi `AppAuthManager.handleGoogleSignInResult` để phân tích và hoàn tất đăng nhập.
  * Xử lý trường hợp người dùng bấm Back hủy đăng nhập (`RESULT_CANCELED`) bằng cách mở khóa và gọi `vipContinuationHandler.onSignInCancelled()`.
- Bổ sung các phương thức nghiệp vụ:
  * `performGoogleSignIn()`: Gọi `AppAuthManager.signInWithGoogle` với lifecycle scope của Activity. Nếu cần fallback intent thì kích hoạt launcher.
  * `handleSignInSuccess(profile)`: Cập nhật giao diện, đồng bộ nền và gọi `vipContinuationHandler.onSignInSuccess { showVipUpgradeDialog() }`.
  * `startSignInForVipContinuation()`: Đánh dấu `vipContinuationHandler.requestContinuation()` và bắt đầu đăng nhập.
  * `showVipUpgradeDialog()`: Khởi tạo `VipUpgradeDialog` với đầy đủ `onRequestDrivePermission`, `onRequestSignIn = { startSignInForVipContinuation() }`, và `onUpgradeSuccess` (gỡ watermark, cập nhật preview).
  * `showSignInErrorDialog(errorMsg)`: Hiển thị dialog thông báo lỗi kèm nút Thử lại.
- Cập nhật cả 2 vị trí kích hoạt VIP Watermark:
  * `binding.btnVipWatermark.setOnClickListener`: Gọi `showVipUpgradeDialog()`.
  * `binding.switchWatermark.setOnCheckedChangeListener`: Nếu user chưa phải VIP mà muốn tắt watermark, giữ switch bật và mở `showVipUpgradeDialog()`.
- Bảo toàn và khôi phục trạng thái:
  * Lưu `KEY_PENDING_SIGN_IN_REQ_ID`, `KEY_PENDING_SIGN_IN_SESSION_GEN`, `vipContinuationHandler` trong `onSaveInstanceState`.
  * Khôi phục trong `onCreate(savedInstanceState)`.

### 2.2. Tạo bộ kiểm thử mới `VipIdCardLoginContinuationTest.kt`
- Đường dẫn: `app/src/test/java/com/tscanner/app/VipIdCardLoginContinuationTest.kt`
- Gồm 10 ca kiểm thử chuyên biệt:
  1. `testIdCardWatermarkAction_whenGuestWithCallback_resolvesToRequestSignIn`: Khách có callback giải quyết sang hành động `RequestSignIn`.
  2. `testIdCardWatermarkAction_whenGuestWithoutCallback_resolvesToPrompt`: Khách không có callback giải quyết về `ShowSignInRequiredPrompt`.
  3. `testIdCardWatermarkAction_whenLoggedInUser_resolvesToActivateVip`: Người dùng đã đăng nhập giải quyết sang `ActivateVip`.
  4. `testContinuationHandler_loginSuccess_triggersConfirmationExactlyOnce`: Đăng nhập thành công mở lại dialog xác nhận đúng 1 lần.
  5. `testContinuationHandler_loginCancelled_resetsPending`: Hủy đăng nhập xóa cờ chờ an toàn.
  6. `testContinuationHandler_loginError_resetsPending`: Lỗi đăng nhập xóa cờ chờ an toàn.
  7. `testIdCardSessionDraftAndContinuation_survivesSavedStateAcrossRecreation`: Dữ liệu ảnh, cấu hình ghép và trạng thái continuation sống sót qua lưu/khôi phục trạng thái.
  8. `testGuestIdCard_preservesFrontAndBackImagesAndWatermark_onLoginCancellation`: File ảnh 2 mặt và watermark được giữ nguyên khi hủy đăng nhập.
  9. `testIdCardLoginSuccess_doesNotAutoRemoveWatermark_requiresUserConfirmation`: Đăng nhập thành công KHÔNG tự động gỡ watermark nếu chưa có người dùng bấm xác nhận.
  10. `testAccountSwitchWhileWaitingSignIn_doesNotAutoGrantVip`: Đổi tài khoản khác trong khi chờ không tự động cấp VIP ngầm.

---

## 3. Bằng chứng thực thi và kết quả kiểm thử

### Lệnh 1: Chạy kiểm thử bộ mới `VipIdCardLoginContinuationTest`
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipIdCardLoginContinuationTest --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 38s
27 actionable tasks: 2 executed, 25 up-to-date
```
- Exit code: `0`
- Toàn bộ 10/10 tests **PASS**.

### Lệnh 2: Chạy tổ hợp kiểm thử VIP Continuation & Regression (S00, S05a, S05b)
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.VipIdCardLoginContinuationTest `
  --tests com.tscanner.app.VipViewerLoginContinuationTest `
  --tests com.tscanner.app.VipLoginRound2RegressionTest `
  --tests com.tscanner.app.VipLoginContinuationTest `
  --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 27s
27 actionable tasks: 1 executed, 26 up-to-date
```
- Exit code: `0`
- Toàn bộ các test của các gói S00, S05a, S05b đều **PASS**.

### Lệnh 3: Chạy toàn bộ test suite dự án (`testDebugUnitTest`)
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 41s
27 actionable tasks: 1 executed, 26 up-to-date
```
- **Tổng số test**: `614` tests.
- **Failures**: `0`.
- **Skipped**: `0`.
- **Exit code**: `0`.

---

## 4. Bảng đối chiếu rà soát kiểm tra (Audit Checklist)

| Mục kiểm tra | Trạng thái trước S05b | Trạng thái sau S05b | Kết luận |
|---|---|---|---|
| Callsite `IdCardComposeActivity` truyền `onRequestSignIn` | Thiếu (không truyền) | Đã truyền callback liên kết máy trạng thái continuation | **ĐẠT** |
| Bảo toàn ảnh CCCD 2 mặt khi khách bấm đăng nhập | Nguy cơ mất ảnh nếu thoát ra ngoài đăng nhập | Ảnh 2 mặt và bố cục giữ nguyên trong suốt quá trình đăng nhập | **ĐẠT** |
| Đăng nhập thành công mở lại dialog xác nhận | Không hỗ trợ | Mở lại `VipUpgradeDialog` đúng 1 lần | **ĐẠT** |
| Bất biến không tự cấp VIP / không tự gỡ watermark ngầm | N/A | Bắt buộc người dùng nhấn xác nhận trong dialog | **ĐẠT** |
| Hủy/lỗi đăng nhập không văng crash, không mất phiên | N/A | Reset continuation sạch sẽ, giữ nguyên ảnh và cài đặt | **ĐẠT** |
| Rà soát toàn bộ callsites `VipUpgradeDialog` trong app | 2 callsites thiếu (`Viewer`, `IdCard`) | 100% callsites đã được gắn `onRequestSignIn` và `onRequestDrivePermission` | **ĐẠT** (R05 đóng hoàn toàn) |

---

## 5. Kết luận gói S05b
- Gói **S05b** đã hoàn thành trọn vẹn và đóng dứt điểm khiếm khuyết **R05** trên toàn bộ ứng dụng (cả màn hình Viewer và màn hình ID Card).
- Sẵn sàng chuyển tiếp sang gói **S06a** (Free login không hiển thị sync failure giả).
