# BÁO CÁO NGHIỆM THU GÓI S05a — VIP LOGIN TẠI VIEWER VÀ CREATE PDF

**Dự án**: T-Scanner (:app)  
**Thời gian thực hiện**: 2026-09-25  
**Tiến độ tổng thể**: S00 (Xong) → S01 (Xong) → S02 (Xong) → S03 (Xong) → S04 (Xong) → **S05a (Hoàn thành)** → S05b → S06a → S06b → S07 → S08 → S09  

---

## 1. Mục tiêu và phạm vi gói S05a

- Khắc phục lỗi **R05** (phần Viewer và Create PDF) theo audit `RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md`:
  * Trước S05a: `PdfViewerActivity.kt:240` và `CreatePdfDialog.kt:58` khi khởi tạo `VipUpgradeDialog` không truyền `onRequestSignIn`. Người dùng khách (guest) khi bấm gỡ watermark hoặc dùng tính năng VIP chỉ nhận được Toast thông báo `"Vui lòng đăng nhập"`, không thể đăng nhập để kích hoạt/phục hồi VIP. Nếu rời khỏi màn hình để đăng nhập từ bên ngoài thì mất phiên Viewer (danh sách trang đã quét, tài liệu đang tạo).
- Thực hiện thiết kế bảo toàn phiên (Session & State Preservation):
  * Tích hợp máy trạng thái tiếp tục đăng nhập VIP (`VipLoginContinuationHandler`) vào `PdfViewerActivity`.
  * Truyền callback đăng nhập ở cả nút Watermark của Viewer và hàng Watermark trong hộp thoại `CreatePdfDialog`.
  * Khi người dùng khách bắt đầu đăng nhập: lưu trữ tên file PDF đang gõ dở (`pendingDraftPdfName`), giữ nguyên danh sách các trang PDF (`renderedPagePaths`) và các thiết lập tài liệu.
  * Sau khi đăng nhập Google thành công: kích hoạt mở lại hộp thoại `VipUpgradeDialog` đúng **một lần** để người dùng tự tay xác nhận kích hoạt dùng thử VIP (tuyệt đối không tự động cấp VIP ngầm).
  * Khi người dùng xác nhận VIP: tự động bỏ watermark, cập nhật giao diện Viewer và mở lại `CreatePdfDialog` với đúng tên file đã gõ trước đó.
  * Khi hủy đăng nhập (cancel) hoặc lỗi (error): dọn dẹp cờ chờ, giữ nguyên các trang và file name, không lặp lại dialog hay văng lỗi.
  * Toàn bộ trạng thái (token requestId, session generation, cờ pending continuation, tên file dở dang) đều được lưu qua `onSaveInstanceState` và phục hồi qua `onCreate`, không giữ tham chiếu `Activity` tránh rò rỉ bộ nhớ.

---

## 2. Các thay đổi chi tiết

### 2.1. Cập nhật `CreatePdfDialog.kt`
- Thêm tham số `onRequestSignIn: ((currentName: String) -> Unit)? = null` vào constructor chính và phụ.
- Khi người dùng khách bấm vào hàng Watermark VIP, hộp thoại `VipUpgradeDialog` được truyền `onRequestSignIn`.
- Khi người dùng chọn đăng nhập: `CreatePdfDialog` lấy tên file người dùng đang nhập (`binding.etPdfName.text.toString().trim()`), đóng hộp thoại an toàn và chuyển tên file này qua callback `onRequestSignIn(currentName)`.
- Ghi đè phương thức `dismiss()` an toàn với kiểm tra `isShowing` và bọc `try-catch`.

### 2.2. Cập nhật `VipUpgradeDialog.kt`
- Ghi đè phương thức `dismiss()` an toàn với kiểm tra `isShowing` và bọc `try-catch` để tránh crash khi Activity host bị xoay màn hình hoặc hủy đột ngột.

### 2.3. Cập nhật `PdfViewerActivity.kt`
- Khai báo các thành phần quản trị luồng đăng nhập:
  * `vipContinuationHandler = VipLoginContinuationHandler()`
  * `pendingSignInAttempt: GoogleLoginAttempt?`
  * `pendingDraftPdfName: String?`
- Đăng ký `googleSignInFallbackLauncher` bằng `ActivityResultContracts.StartActivityForResult()` để xử lý fallback intent của Google Sign-In.
- Triển khai các phương thức an toàn vòng đời:
  * `performGoogleSignIn()`: Gọi `AppAuthManager.signInWithGoogle` với lifecycle của Activity, quản lý lock và token attempt.
  * `handleSignInSuccess(profile)`: Thông báo đăng nhập thành công, chạy sync nền, kích hoạt `vipContinuationHandler.onSignInSuccess { showVipUpgradeDialog() }`.
  * `startSignInForVipContinuation(draftName: String?)`: Lưu tên file dở dang, đánh dấu `requestContinuation()` và bắt đầu đăng nhập.
  * `showVipUpgradeDialog()`: Mở `VipUpgradeDialog` với đầy đủ `onRequestDrivePermission`, `onUpgradeSuccess` và `onRequestSignIn`. Sau khi user nâng cấp thành công, nếu có `pendingDraftPdfName` thì mở lại `createNewPdf` hoặc `saveFinalDocument` tương ứng.
  * `showSignInErrorDialog(errorMsg)`: Hiển thị lỗi đăng nhập có nút Thử lại.
- Cập nhật cả 3 callsite trong `PdfViewerActivity`:
  * `binding.btnVipWatermarkViewer.setOnClickListener`: Khách bấm mở `showVipUpgradeDialog()`.
  * `createNewPdf(draftName: String? = null)`: Truyền `onRequestSignIn = { currentTypedName -> startSignInForVipContinuation(currentTypedName) }`.
  * `saveFinalDocument(draftName: String? = null)`: Truyền `onRequestSignIn = { currentTypedName -> startSignInForVipContinuation(currentTypedName) }`.
- Lưu và phục hồi qua `onSaveInstanceState` / `onCreate`:
  * `KEY_PENDING_SIGN_IN_REQ_ID`, `KEY_PENDING_SIGN_IN_SESSION_GEN`, `KEY_PENDING_DRAFT_PDF_NAME`, `vipContinuationHandler.saveInstanceState(outState)`.

### 2.4. Tạo bộ unit test mới `VipViewerLoginContinuationTest.kt`
- Đường dẫn: `app/src/test/java/com/tscanner/app/VipViewerLoginContinuationTest.kt`
- Gồm 11 ca kiểm thử toàn diện:
  1. `testViewerWatermarkAction_whenGuestWithCallback_resolvesToRequestSignIn`: Khách có callback giải quyết sang `RequestSignIn`.
  2. `testViewerWatermarkAction_whenGuestWithoutCallback_resolvesToPrompt`: Khách không có callback giải quyết sang `ShowSignInRequiredPrompt`.
  3. `testViewerWatermarkAction_whenLoggedInUser_resolvesToActivateVip`: User đã đăng nhập giải quyết sang `ActivateVip`.
  4. `testCreatePdfDialog_onRequestSignIn_preservesTypedDraftName`: Đảm bảo tên file đang nhập được chuyển nguyên vẹn đến host khi bắt đầu đăng nhập.
  5. `testContinuationHandler_loginSuccess_triggersConfirmationExactlyOnce`: Đăng nhập thành công chỉ mở xác nhận VIP đúng 1 lần, không gọi lại lần hai.
  6. `testContinuationHandler_loginCancelled_resetsPending`: Hủy đăng nhập dọn sạch cờ chờ.
  7. `testContinuationHandler_loginError_resetsPending`: Lỗi đăng nhập dọn sạch cờ chờ.
  8. `testViewerStatePreservation_draftNameAndContinuationPreservedAcrossSavedState`: Tên file dở dang và cờ continuation được bảo toàn qua lưu trạng thái.
  9. `testGuestViewerPagesAndWatermarkPreference_preservedOnLoginCancellation`: Danh sách các trang đã quét và trạng thái watermark được bảo toàn nguyên vẹn khi hủy đăng nhập.
  10. `testViewerLoginSuccess_doesNotAutoGrantVip_requiresUserConfirmation`: Đăng nhập thành công chưa cấp VIP ngay; phải có xác nhận tường minh của người dùng trong dialog mới trở thành VIP.
  11. `testAccountSwitchWhileWaitingSignIn_doesNotAutoGrantVip`: Đổi tài khoản trong khi chờ không tự cấp VIP ngầm.

---

## 3. Bằng chứng thực thi và kết quả kiểm thử

### Lệnh 1: Chạy bộ kiểm thử mới `VipViewerLoginContinuationTest`
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipViewerLoginContinuationTest --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 40s
27 actionable tasks: 5 executed, 22 up-to-date
```
- Exit code: `0`
- Toàn bộ 11/11 tests trong `VipViewerLoginContinuationTest` đều **PASS**.

### Lệnh 2: Chạy tổ hợp kiểm thử VIP Continuation & Regression
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.VipViewerLoginContinuationTest `
  --tests com.tscanner.app.VipLoginRound2RegressionTest `
  --tests com.tscanner.app.VipLoginContinuationTest `
  --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 22s
27 actionable tasks: 1 executed, 26 up-to-date
```
- Exit code: `0`
- Tất cả các test suites liên quan đều **PASS**.

### Lệnh 3: Chạy toàn bộ test suite dự án `:app`
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 32s
27 actionable tasks: 1 executed, 26 up-to-date
```
- Báo cáo HTML `app/build/reports/tests/testDebugUnitTest/index.html`:
  * **Tổng số test**: `604` (tăng từ 593 lên 604 do thêm 11 test mới)
  * **Thất bại**: `0`
  * **Bỏ qua**: `0`
  * **Tỷ lệ thành công**: `100%`

---

## 4. Bảng đối chiếu hiện trạng các phát hiện audit

| Mã | Tên vấn đề | Trạng thái kỹ thuật | Trạng thái thiết bị/Console | Ghi chú |
|---|---|---|---|---|
| R01 | Vô hiệu hóa phiên đăng nhập | **PROBE PASS** | NOT RUN | Đã fix tại S01 (token requestId & generation) |
| R02 | Scope huỷ làm khoá sign-in | **PROBE PASS** | NOT RUN | Đã fix tại S02 (isFallbackActive & invokeOnCompletion) |
| R03 | Lỗi UI sau login fallback | **PROBE PASS** | NOT RUN | Đã fix tại S03 (cô lập 3 bước try-catch) |
| R04 | Drive result không gắn request | **PROBE PASS** | NOT RUN | Đã fix tại S04 (DriveAuthorizationAttempt single-consume) |
| R05 | VIP login thiếu tại Viewer & Create PDF | **CODE VERIFIED** | NOT RUN | Đã fix tại S05a (nối onRequestSignIn, giữ file/trang, mở dialog xác nhận) |
| R05 | VIP login thiếu tại ID card | PENDING | NOT RUN | Sẽ thực hiện tại gói S05b |
| R06 | Free login báo sync failure giả | PENDING | NOT RUN | Sẽ thực hiện tại gói S06a |
| R07 | Block UI vì snapshot WorkManager | PENDING | NOT RUN | Sẽ thực hiện tại gói S07 |
| R08 | Snapshot lưu tại cacheDir dễ mất | PENDING | NOT RUN | Sẽ thực hiện tại gói S08 |

---

## 5. Khối bàn giao bắt buộc (Mandatory Handover Block)

- **Gói vừa hoàn thành**: S05a — VIP login tại Viewer và Create PDF
- **Trạng thái**: Hoàn thành, sạch lỗi, 604/604 unit test đạt (100%), R05 (Viewer/Create PDF) đã được kiểm chứng bằng mã nguồn và test suite chuyên biệt
- **Tập tin đã sửa/tạo**:
  * `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`
  * `app/src/main/java/com/tscanner/app/ui/dialogs/CreatePdfDialog.kt`
  * `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
  * `app/src/test/java/com/tscanner/app/VipViewerLoginContinuationTest.kt` (mới)
  * `REPORT_VIP_LOGIN_ROUND2_S05a.md` (mới)
- **Lệnh kiểm chứng đã chạy**:
  * `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipViewerLoginContinuationTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.VipLoginContinuationTest --offline --console=plain` (Exit code 0)
  * `.\gradlew.bat :app:testDebugUnitTest --offline --console=plain` (Exit code 0, 604 tests passed)
- **Gói kế tiếp**: S05b — VIP login tại ID card (`IdCardComposeActivity.kt`, tái dùng hợp đồng tiếp tục VIP từ S05a)
- **Điểm dừng**: Dừng lại theo đúng quy tắc một gói mỗi lượt. Chờ lệnh **`tiếp`** từ người dùng trước khi tiến hành gói S05b.
