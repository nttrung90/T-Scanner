# Báo cáo Gói V06: Nhận đầy đủ kết quả cấp quyền Drive và loại bỏ unmonitored startActivity

Ngày: 25/09/2026
Mã gói: **V06**
Trạng thái: **HOÀN THÀNH**
Kho mã nguồn: `E:\DU AN AI\T-Scanner` (module `:app`)

---

## 1. Mục tiêu và phạm vi gói V06

- **Vấn đề trước khi sửa**:
  1. Hộp thoại `VipUpgradeDialog` chứa lệnh dự phòng `context.startActivity(intent)` gọi Intent cấp quyền Google Drive mà không qua `ActivityResultLauncher`. Khi người dùng đồng ý, từ chối, hủy hoặc gặp lỗi, ứng dụng hoàn toàn không nhận được callback kết quả, không kiểm tra được tài khoản và không kích hoạt đồng bộ đám mây đúng cách.
  2. Nhiều điểm gọi `VipUpgradeDialog` (tại `HomeFragment`, `MoreFragment` khi gia hạn hết hạn, `PdfViewerActivity`, `IdCardComposeActivity`, `CreatePdfDialog`) không truyền callback `onRequestDrivePermission`, khiến luồng cấp quyền Drive luôn rơi vào lệnh `startActivity` không giám sát nói trên.
  3. `MoreFragment` trước đây chỉ xử lý `resultCode == Activity.RESULT_OK` hoặc `resultCode != Activity.RESULT_CANCELED`; khi người dùng hủy hoặc khi Google Play Services trả về lỗi qua Intent kèm `RESULT_CANCELED`, kết quả bị bỏ qua hoặc nuốt mất mà không phân biệt được.
  4. Thiếu cơ chế ràng buộc phiên và xác thực tài khoản chặt chẽ: nếu người dùng mở màn hình cấp quyền Drive rồi đăng xuất / đổi sang tài khoản khác, hoặc chọn nhầm tài khoản Google khác trong hộp thoại consent, ứng dụng có nguy cơ nhận nhầm quyền hoặc đồng bộ sai tài khoản.

---

## 2. Chi tiết các thay đổi kỹ thuật

### 2.1. Router phân loại kết quả cấp quyền Drive (`DriveAuthorizationResultRouter.kt`)
- Xây dựng sealed class `DriveAuthorizationResult`:
  - `Success(accountData)`: Cấp quyền thành công, tài khoản khớp và có đủ scope `drive.file`.
  - `Cancelled`: Người dùng chủ động đóng/hủy consent (qua mã lỗi `12501` hoặc `RESULT_CANCELED` với null data). Luồng kết thúc êm dịu, không hiển thị thông báo lỗi giả mạo.
  - `AccountMismatch(expectedEmail, grantedEmail)`: Người dùng chọn tài khoản Google khác với tài khoản đang đăng nhập trong app. Chặn quyền và từ chối đồng bộ để tránh session hijacking.
  - `SessionExpiredOrChanged`: Phiên đăng nhập đã thay đổi (người dùng đăng xuất hoặc chuyển tài khoản khi màn hình consent đang mở). Kết quả cũ bị loại bỏ an toàn.
  - `PermissionDenied`: Tài khoản trả về thiếu scope `https://www.googleapis.com/auth/drive.file`.
  - `Failure(statusCode, errorMessage)`: Trích xuất mã lỗi thực tế từ `ApiException` (như lỗi mạng `7`, lỗi cấu hình `10`, lỗi Google Play `12500`) kể cả khi mang cờ `RESULT_CANCELED`.
- `dispatchResult`: Đảm bảo callback chỉ được gọi tối đa đúng một lần duy nhất (`AtomicBoolean`), ngăn chặn đồng bộ trùng lặp khi người dùng bấm nhanh 2 lần.

### 2.2. Ràng buộc phiên và xử lý kết quả tại `AppAuthManager.kt`
- Thêm `DriveAuthSessionSnapshot(userId, userEmail, sessionGeneration)` và `pendingDriveAuthSession: AtomicReference<DriveAuthSessionSnapshot?>`.
- Khi `getGoogleDriveSignInIntent(context)` được gọi: Lưu lại snapshot phiên hiện tại (`sessionGeneration`, `userId`, `userEmail`).
- Nâng cấp `handleDrivePermissionResult`:
  - Tiếp nhận `resultCode: Int` cùng với `data: Intent?`.
  - Đối chiếu phiên: So sánh `sessionGeneration` của snapshot với phiên hiện tại. Nếu phiên đã thay đổi (do đăng xuất hoặc đăng nhập tài khoản khác), hủy bỏ kết quả cũ và không kích hoạt đồng bộ.
  - Giữ lại các overload tương thích ngược cho các điểm gọi cũ và unit tests.

### 2.3. Loại bỏ hoàn toàn fallback `context.startActivity(intent)` (`VipUpgradeDialog.kt`)
- Xóa bỏ khối lệnh `context.startActivity(intent)` trong nhánh `!hasDrive`.
- Nếu `onRequestDrivePermission` được host cung cấp: Gọi callback để host kích hoạt `ActivityResultLauncher`.
- Nếu `onRequestDrivePermission == null`: Ghi log cảnh báo `Log.w`, không tự tiện phóng intent không có launcher.

### 2.4. Đăng ký launcher theo vòng đời tại tất cả các điểm gọi
- `HomeFragment.kt`:
  - Khai báo và đăng ký `driveAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.
  - Truyền `onRequestDrivePermission` trong sự kiện bấm `btnVipHome` khi người dùng đã đăng nhập.
- `MoreFragment.kt`:
  - Cập nhật `driveAuthorizationLauncher` truyền đầy đủ `resultCode` vào `AppAuthManager.handleDrivePermissionResult`.
  - Sửa `showVipExpiredNoticeDialog()`: Thay thế `VipUpgradeDialog(requireContext()).show()` bằng `showVipUpgradeDialog()` (đã có đầy đủ launcher và callback).
- `PdfViewerActivity.kt`:
  - Đăng ký `driveAuthorizationLauncher`.
  - Truyền `onRequestDrivePermission` vào `VipUpgradeDialog` và cả hai điểm gọi `CreatePdfDialog`.
- `IdCardComposeActivity.kt`:
  - Đăng ký `driveAuthorizationLauncher`.
  - Truyền `onRequestDrivePermission` vào `VipUpgradeDialog`.
- `CreatePdfDialog.kt`:
  - Bổ sung tham số `onRequestDrivePermission: (() -> Unit)? = null` và chuyển tiếp an toàn vào `VipUpgradeDialog`.

---

## 3. Kết quả kiểm thử tự động (Unit Tests)

Bộ kiểm thử mới [DriveAuthorizationFlowTest.kt](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/DriveAuthorizationFlowTest.kt) gồm 13 ca kiểm thử, kết hợp cùng toàn bộ test suite từ V00 đến V05:

| Nhóm kiểm thử | Số ca test | Kết quả |
| :--- | :---: | :---: |
| `DriveAuthorizationFlowTest` (V06) | 13 | **PASSED (100%)** |
| `VipLoginContinuationTest` (V05) | 13 | **PASSED (100%)** |
| `GoogleSignInResultRouterTest` (V01) | 11 | **PASSED (100%)** |
| `GoogleIdentityOptionsTest` (V02) | 9 | **PASSED (100%)** |
| `AppAuthCanonicalIdentityTest` (V04) | 7 | **PASSED (100%)** |
| `GoogleLoginFlowTest` (V03) | 7 | **PASSED (100%)** |
| `DemoAccountIsolationTest` (V04) | 5 | **PASSED (100%)** |
| `AppAuthDriveAuthorizationTest` (V03) | 4 | **PASSED (100%)** |
| `GoogleCredentialRequestFactoryTest` (V02) | 4 | **PASSED (100%)** |
| **Tổng cộng toàn bộ suite xác thực** | **73** | **PASSED (100%)** |

### Các ca kiểm thử then chốt:
1. `testRouteResult_validAccountAndDriveScope_returnsSuccess`: Cấp quyền đúng tài khoản và scope thành công.
2. `testRouteResult_differentAccountEmail_returnsAccountMismatch`: Phát hiện và từ chối khi tài khoản consent khác tài khoản đang đăng nhập.
3. `testRouteResult_missingDriveScope_returnsPermissionDenied`: Từ chối khi thiếu scope `drive.file`.
4. `testRouteResult_nullDataWithResultCanceled_returnsCancelled`: Hủy bỏ êm dịu khi người dùng bấm back.
5. `testRouteResult_apiException12501_returnsCancelled`: Mã lỗi 12501 phân loại chính xác là Cancelled.
6. `testRouteResult_apiExceptionNetworkError7_returnsFailure`: Mã lỗi 7 (mạng) trích xuất thành Failure kể cả khi mang `RESULT_CANCELED`.
7. `testRouteResult_sessionChangedWhileConsentOpen_returnsSessionExpiredOrChanged`: Hủy bỏ an toàn kết quả cũ khi phiên thay đổi trong lúc chờ consent.
8. `testDispatchResult_success_invokesOnSuccessExactlyOnce`: Đảm bảo callback chỉ chạy duy nhất 1 lần, chống sync trùng lặp.
9. `testAppAuthManager_handleDrivePermissionResult_validAccount_triggersPostAuthSync`: Kích hoạt đồng bộ sau khi cấp quyền hợp lệ.
10. `testAppAuthManager_handleDrivePermissionResult_mismatchedAccount_doesNotTriggerSync`: Không kích hoạt đồng bộ khi tài khoản không khớp.
11. `testAppAuthManager_handleDrivePermissionResult_staleSession_doesNotTriggerSync`: Không kích hoạt đồng bộ cho phiên cũ sau sign-out.

---

## 4. Ranh giới an toàn và ghi chú chuyển giao sang V07

- Không chỉnh sửa Web Client ID và không sửa SHA-1.
- Bảo toàn toàn bộ các thay đổi cục bộ có sẵn trên workspace.
- **Điểm bàn giao sang V07**:
  - Gói V07 sẽ tập trung vào việc đưa trạng thái đồng bộ ra UI (`SyncCatalogResult`), phân định rõ ràng giữa đăng nhập thành công với đồng bộ thất bại / yêu cầu quyền, và bảo đảm callback đồng bộ liên kết an toàn với vòng đời View.
