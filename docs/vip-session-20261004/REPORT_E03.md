# Báo cáo Bàn giao Gói E03 — Nối Chi tiết tài khoản và phân biệt lời nhắc

**Thời điểm:** 04/10/2026  
**Workspace:** `E:\DU AN AI/T-Scanner`  
**Gói phụ trách:** E03 (Nối Chi tiết tài khoản và phân biệt lời nhắc)  
**Trạng thái:** HOÀN THÀNH (PASS)

---

## 1. Mục tiêu và Phạm vi gói E03

- **Khắc phục khiếm khuyết F02:** Hộp thoại Chi tiết tài khoản (`AccountDetailDialog`) trước đây chỉ truyền `onRequestDrivePermission`, hoàn toàn bỏ qua `onRequestSignIn`, `onRequestSignInForAction`, `onUpgradeSuccess` và `onSyncResult`. Do đó, khi người dùng có tài khoản nhưng credential hết hạn bấm mua hoặc khôi phục VIP từ cả 2 điểm (nút `btnDialogUpgradeAction` và container `containerMembershipStatus`), hệ thống rơi vào lời nhắc rỗng (dead-end prompt) yêu cầu đăng nhập dù người dùng vẫn đang thấy tài khoản của mình.
- **Khắc phục khiếm khuyết F04:** Trong `VipUpgradeDialog.kt:154`, `hasSignInCallback` trước đây chỉ xét `(onRequestSignIn != null)`, bỏ qua `onRequestSignInForAction`. Do đó, bất kỳ điểm gọi nào chỉ truyền callback theo action đều bị coi là không có khả năng đăng nhập/xác thực lại.
- **Phân biệt chuỗi hiển thị theo 3 trạng thái:**
  1. *Guest (chưa có tài khoản):* hiển thị lời nhắc đăng nhập để liên kết và kích hoạt gói VIP (`sign_in_to_activate_vip_prompt`).
  2. *Tài khoản hợp lệ (credential còn hạn):* mở giao diện thanh toán Google Play hoặc khôi phục.
  3. *Phiên hết hạn / cần xác thực lại:* hiển thị thông báo rõ ràng "Phiên xác thực đã hết hạn. Vui lòng xác thực lại tài khoản Google để tiếp tục." (`vip_session_expired_reauth_prompt`). Không dùng chung câu đăng nhập gây hiểu lầm cho người dùng đang có tài khoản.
- Không tự động đăng xuất tài khoản khi đóng các hộp thoại này.

---

## 2. Chi tiết thay đổi code production

### 2.1 `app/src/main/java/com/tscanner/app/ui/dialogs/AccountDetailDialog.kt`
- Mở rộng constructor nhận đầy đủ các callback liên quan đến VIP:
  ```kotlin
  class AccountDetailDialog(
      context: Context,
      private val user: UserProfile,
      private val onRequestDrivePermission: (() -> Unit)? = null,
      private val onRequestSignIn: (() -> Unit)? = null,
      private val onRequestSignInForAction: ((com.tscanner.app.utils.VipContinuationAction) -> Unit)? = null,
      private val onUpgradeSuccess: (() -> Unit)? = null,
      private val onSyncResult: ((com.tscanner.app.utils.SyncCatalogResult) -> Unit)? = null,
      private val onSignOut: () -> Unit
  ) : Dialog(context)
  ```
- Tạo hàm tập trung `openVipUpgradeDialog()` chuyển tiếp toàn bộ các callback trên vào `VipUpgradeDialog`.
- Cả hai điểm mở VIP trong `setupListeners()`:
  - `binding.btnDialogUpgradeAction.setOnClickListener`: gọi `openVipUpgradeDialog()`.
  - `binding.containerMembershipStatus.setOnClickListener` (khi Free): gọi `openVipUpgradeDialog()`.

### 2.2 `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
- Sửa điều kiện đánh giá `hasSignInCallback` tại line 154:
  ```kotlin
  hasSignInCallback = (onRequestSignIn != null || onRequestSignInForAction != null)
  ```
- Cập nhật các điểm hiển thị thông báo:
  - Trong `onRequestSignIn()` của listener: nếu `currentUser != null`, hiển thị Toast `vip_session_expired_reauth_prompt`.
  - Trong `onShowSignInPrompt()` của listener: phân biệt giữa `currentUser != null` (`vip_session_expired_reauth_prompt`) và guest (`sign_in_to_activate_vip_prompt`).
  - Trong nút khôi phục `btnRestorePurchases`: nếu không có callback, hiển thị `vip_session_expired_reauth_prompt` cho người dùng hiện tại, thay vì câu nhắc đăng nhập chung chung.

### 2.3 `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
- Trong `handleAccountClick()`, khi khởi tạo `AccountDetailDialog`, truyền đầy đủ:
  - `onRequestSignIn = { startSignInForVipContinuation(VipContinuationAction.UPGRADE) }`
  - `onRequestSignInForAction = { action -> startSignInForVipContinuation(action) }`
  - `onUpgradeSuccess = { updateAccountUi(AppAuthManager.getCurrentUser()) }`
  - `onSyncResult = { result -> handlePostAuthSyncResult(result, ...) }`

### 2.4 Resource Strings (`values/strings.xml` & `values-vi/strings.xml`)
- Bổ sung chuỗi mới:
  - `vip_session_expired_reauth_prompt`:
    - Tiếng Việt: `"Phiên xác thực đã hết hạn. Vui lòng xác thực lại tài khoản Google để tiếp tục."`
    - Tiếng Anh: `"Session expired. Please re-authenticate your Google account to continue."`
  - Gắn thuộc tính `tools:ignore="MissingTranslation"` bảo đảm kiểm tra lint đa ngôn ngữ sạch sẽ.

---

## 3. Kết quả kiểm thử và nghiệm thu

### 3.1 Suite hồi quy phiên xác thực: `VipSessionExpiryRegressionTest.kt`
Tất cả 7 bài kiểm thử đều đạt kết quả **PASS** (100%):
- `testF02_accountDetailDialog_expiredUser_mustHaveReauthPathNotDeadEndPrompt`: **PASS** (Trước đây FAILED, nay xác nhận cả 2 điểm từ Chi tiết tài khoản đều mở đường reauth, 0 dead-end prompt).
- `testF04_actionOnlyCallback_mustBeRecognizedAsHavingSignInCallback`: **PASS** (Trước đây FAILED, nay xác nhận host chỉ có `onRequestSignInForAction` được nhận diện chính xác `hasSignInCallback = true`).
- `testF05_credentialManagerSuccess_preservesRestoreContinuation`: **PASS** (Đã chuyển xanh từ E02).
- 4 test control: `testControl_expiredTokenDetection_isTokenExpired`, `testControl_validUser_tokenNotExpired_resolvesToActivateVip`, `testControl_guestUser_withoutCallback_resolvesToShowSignInRequiredPrompt`, `testControl_guestUser_withCallback_resolvesToRequestSignIn`: **TẤT CẢ PASS**.

### 3.2 Toàn bộ các suite liên quan
- `VipSessionExpiryRegressionTest` (7 tests): **PASS**
- `AppAuthReauthenticationTest` (11 tests): **PASS**
- `VipLoginContinuationTest` (19 tests): **PASS**
- `VipPurchaseActionCoordinatorTest` (11 tests): **PASS**
- `VipRound8RegressionTest` (17 tests): **PASS**
- **Tổng cộng 65/65 tests: BUILD SUCCESSFUL.**

---

## 4. Kết luận và Bàn giao sang Gói E04

- Gói E03 đã giải quyết triệt để F02 và F04, nối thông hoàn toàn các điểm chạm UI trong Chi tiết tài khoản và hoàn thiện phân biệt ngữ cảnh thông báo.
- Sẵn sàng chuyển giao sang **Gói E04** (Khép các khoảng hở khi đang tải/mua/khôi phục — `VipPurchaseActionCoordinator`, `BillingManager`).
