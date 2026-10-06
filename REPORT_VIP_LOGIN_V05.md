# Báo cáo Gói V05: Luồng nâng cấp VIP cho khách và tiếp tục sau đăng nhập

Ngày: 25/09/2026
Mã gói: **V05**
Trạng thái: **HOÀN THÀNH**
Kho mã nguồn: `E:\DU AN AI\T-Scanner` (module `:app`)

---

## 1. Mục tiêu và phạm vi gói V05

- **Vấn đề trước khi sửa**:
  1. Khi người dùng ở trạng thái Khách (chưa đăng nhập Google) bấm nút hoặc banner nâng cấp VIP (trên Home hoặc More), hộp thoại VIP trước đây chỉ hiển thị nút "Kích hoạt dùng thử VIP" nhưng nếu bấm vào chỉ bật Toast hoặc xử lý dở dang, không dẫn luồng đăng nhập mượt mà.
  2. Không có cơ chế tiếp tục (continuation) sau khi đăng nhập thành công: người dùng đăng nhập xong bị rớt về màn hình chính mà không được tự động mở lại đúng hộp thoại kích hoạt VIP họ vừa yêu cầu.
  3. Nguy cơ vi phạm nguyên tắc phân tách quyền: Đăng nhập thành công **tuyệt đối không được tự động kích hoạt VIP ngầm** khi người dùng chưa bấm xác nhận gói; nếu người dùng hủy đăng nhập hoặc gặp lỗi mạng, tài khoản phải giữ nguyên trạng thái Free/Khách, không được ép sang tài khoản demo và không làm mất tài liệu đang lưu trong bộ nhớ tạm/cơ sở dữ liệu.
  4. Quản lý trạng thái vòng đời (Activity/Fragment recreation, xoay màn hình): Trạng thái chờ tiếp tục nâng cấp VIP cần được bảo lưu an toàn qua Bundle SavedState mà không gây memory leak (không giữ tham chiếu Activity/Dialog).

---

## 2. Chi tiết các thay đổi kỹ thuật

### 2.1. Tách logic quyết định nâng cấp và hỗ trợ callback đăng nhập (`VipUpgradeDialog.kt`)
- Thêm tham số `onRequestSignIn: (() -> Unit)? = null` vào hàm khởi tạo của `VipUpgradeDialog`.
- Trích xuất bộ phân giải hành động `VipUpgradeActionResolver` (object thuần Kotlin, deterministic 100% không phụ thuộc Android Windowing framework):
  - `VipUpgradeAction.ActivateVip(accountEmail)`: Dành cho người dùng đã đăng nhập (Free) bấm kích hoạt dùng thử.
  - `VipUpgradeAction.RequestSignIn`: Dành cho khách chưa đăng nhập khi có handler điều hướng đăng nhập.
  - `VipUpgradeAction.ShowSignInRequiredPrompt`: Dự phòng an toàn cho các điểm gọi dialog khác (như xem PDF, scan ID) khi chưa truyền handler đăng nhập trực tiếp.
  - `VipUpgradeAction.AlreadyVip`: Hiển thị trạng thái đã có VIP.
- Cập nhật giao diện nút bấm trong `VipUpgradeDialog`:
  - Khách chưa đăng nhập: Nút chính hiển thị `"Đăng nhập Google để dùng VIP"`. Khi bấm, đóng dialog và gọi `onRequestSignIn()`.
  - Đã đăng nhập: Nút chính hiển thị `"Kích hoạt dùng thử VIP ($accountEmail)"`.

### 2.2. Trình điều phối trạng thái tiếp tục (`VipLoginContinuationHandler.kt`)
- Xây dựng state machine `VipLoginContinuationHandler`:
  - `isPending`: Cờ báo trạng thái đang chờ hoàn tất đăng nhập để tiếp tục luồng VIP.
  - `requestContinuation()`: Đặt `isPending = true`.
  - `onSignInSuccess(onOpenConfirmation)`: Kiểm tra `isPending`. Nếu đúng, đặt lại `isPending = false` và thực thi callback mở lại hộp thoại xác nhận dùng thử VIP đúng một lần duy nhất.
  - `onSignInCancelled()`: Đặt lại `isPending = false`, đảm bảo người dùng tiếp tục là Free/Khách.
  - `onSignInError()`: Đặt lại `isPending = false`, không kích hoạt VIP ngầm.
  - `saveInstanceState(Bundle)` / `restoreInstanceState(Bundle?)`: Lưu và khôi phục cờ qua vòng đời Fragment/Activity.
  - `saveToMap(MutableMap)` / `restoreFromMap(Map?)`: Hỗ trợ kiểm thử đơn vị thuần JVM không phụ thuộc Android stub.

### 2.3. Điều hướng từ MainActivity và HomeFragment
- `MainActivity.kt`:
  - Thêm phương thức `navigateToMoreForVipSignIn()`: Gửi FragmentResult với key `MoreFragment.REQUEST_KEY_VIP_SIGN_IN` tới `MoreFragment`, đồng thời chuyển tab điều hướng dưới đáy sang `R.id.nav_more`.
- `HomeFragment.kt`:
  - Cập nhật sự kiện click của `btnVipHome` (nút VIP trên thẻ trạng thái sao lưu Home): Nếu người dùng chưa đăng nhập, mở `VipUpgradeDialog` với callback `onRequestSignIn` trỏ tới `(activity as? MainActivity)?.navigateToMoreForVipSignIn()`.
- Các điểm gọi khác (`PdfViewerActivity`, `IdCardComposeActivity`, `CreatePdfDialog`):
  - Tiếp tục sử dụng `VipUpgradeDialog` nguyên bản; nếu người dùng đóng hộp thoại mà không đăng nhập, toàn bộ bản nháp đang xử lý (tài liệu đang quét, ảnh căn cước, văn bản OCR) được bảo toàn tuyệt đối không bị reset.

### 2.4. Tích hợp continuation trong `MoreFragment.kt`
- Khởi tạo `vipContinuationHandler = VipLoginContinuationHandler()`.
- Lắng nghe FragmentResult `REQUEST_KEY_VIP_SIGN_IN`: Khi nhận được tín hiệu từ HomeFragment hoặc Deep Link, tự động gọi `startSignInForVipContinuation()`.
- Khi luồng đăng nhập Google (`performGoogleSignIn` / `googleSignInLauncher`):
  - Thành công: Gọi `vipContinuationHandler.onSignInSuccess { showVipUpgradeDialog() }`, tự động hiển thị lại hộp thoại với thông tin tài khoản người dùng vừa đăng nhập để họ xác nhận kích hoạt dùng thử.
  - Bị hủy / Đóng Credential Manager: Gọi `vipContinuationHandler.onSignInCancelled()`.
  - Thất bại / Lỗi mạng: Gọi `vipContinuationHandler.onSignInError()`.
- Đảm bảo `onSaveInstanceState` và `onViewCreated` liên kết chặt chẽ với `vipContinuationHandler`.

---

## 3. Kết quả kiểm thử tự động (Unit Tests)

Bộ kiểm thử mới [VipLoginContinuationTest.kt](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipLoginContinuationTest.kt) gồm 13 ca kiểm thử, kết hợp cùng toàn bộ các bài test xác thực từ V00 đến V04:

| Nhóm kiểm thử | Số ca test | Kết quả |
| :--- | :---: | :---: |
| `VipLoginContinuationTest` (V05) | 13 | **PASSED (100%)** |
| `GoogleSignInResultRouterTest` (V01) | 11 | **PASSED (100%)** |
| `GoogleIdentityOptionsTest` (V02) | 9 | **PASSED (100%)** |
| `GoogleCredentialRequestFactoryTest` (V02) | 4 | **PASSED (100%)** |
| `AppAuthDriveAuthorizationTest` (V03) | 4 | **PASSED (100%)** |
| `GoogleLoginFlowTest` (V03) | 7 | **PASSED (100%)** |
| `DemoAccountIsolationTest` (V04) | 5 | **PASSED (100%)** |
| `AppAuthCanonicalIdentityTest` (V04) | 7 | **PASSED (100%)** |
| **Tổng cộng toàn bộ suite xác thực** | **60** | **PASSED (100%)** |

### Các đặc tả then chốt đã được chứng minh qua test:
1. `testVipUpgradeActionResolver_whenFreeGuest_requestsSignIn`: Khách chưa đăng nhập nhận đúng hành động `RequestSignIn`.
2. `testVipUpgradeActionResolver_whenLoggedInFree_activatesVip`: Người dùng Free đã đăng nhập nhận đúng hành động `ActivateVip(email)`.
3. `testContinuationHandler_onSignInSuccess_executesConfirmationExactlyOnce`: Khi đăng nhập thành công, callback mở lại dialog chỉ chạy duy nhất 1 lần rồi xóa cờ chờ.
4. `testContinuationHandler_onSignInSuccess_whenNotPending_doesNotOpenConfirmation`: Đăng nhập thông thường không mở dialog VIP nếu trước đó người dùng không yêu cầu VIP.
5. `testContinuationHandler_onSignInCancelled_resetsPending` & `testContinuationHandler_onSignInError_resetsPending`: Hủy đăng nhập hoặc lỗi kết nối hủy bỏ hoàn toàn trạng thái chờ.
6. `testContinuationHandler_savedStateRestoration_preservesPending`: Trạng thái chờ không bị thất lạc khi xoay màn hình (recreation).
7. `testGuestDocumentsAndFreeStatePreserved_whenContinuationCancelled`: Hủy luồng VIP không làm mất tài liệu của Khách.
8. `testGuestDocumentsTransferredToUser_whenContinuationSucceeds`: Tài liệu của Khách được chuyển giao an toàn cho tài khoản Google thật khi đăng nhập thành công.

---

## 4. Ranh giới an toàn và ghi chú chuyển giao sang V06

- Không chỉnh sửa Web Client ID và không đổi cấu hình OAuth.
- Không tự động cấp quyền VIP ngầm khi mới chỉ đăng nhập tài khoản.
- Không sửa đè các file ngoài phạm vi (bảo toàn nguyên vẹn thay đổi tại `DocumentRepo.kt`, `CloudBackupManager.kt`, và các tệp tài nguyên đa ngôn ngữ).
- **Điểm bàn giao sang V06**:
  - Gói V06 sẽ tập trung vào việc nhận diện kết quả phân quyền Google Drive (`driveAuthorizationLauncher`), loại bỏ các lệnh `startActivity` rơi vào khoảng trống không có callback giám sát trong `AppAuthManager`, và kiểm tra tính nhất quán giữa tài khoản đăng nhập chính và tài khoản phân quyền Drive.
