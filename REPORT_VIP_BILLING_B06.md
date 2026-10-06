# Báo cáo kết quả gói B06 — Verify và session ownership ở client (F04/F05)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B06

- **Khắc phục lỗi F04 (Client tự parse receipt và tự grant VIP mà không qua xác thực backend):**
  - Trước đây: `processPurchase` trong `BillingManager` tự kiểm tra `PURCHASED` / `isAcknowledged` rồi gọi trực tiếp `setUserVipTier(durationDays)` mà không qua xác thực có thẩm quyền. Nếu không có backend, client tự cấp miễn phí cho mọi giao dịch.
  - Giải pháp: Tạo adapter `PlayPurchaseVerifier` hiện thực `PurchaseVerifier` (đã định nghĩa ở B03). Tất cả giao dịch phải được kiểm tra qua `verifier.verifyPurchase(request)`. Trong môi trường release khi chưa cấu hình backend thật, bắt buộc trả về `MissingBackendGate` và từ chối kích hoạt VIP, không bao giờ giả lập thành công.
- **Khắc phục lỗi F05 (Giao dịch không gắn với tài khoản / token trôi nổi / late callback cross-contamination):**
  - Bắt giữ thông tin chủ sở hữu (`ownerAppUserId`) tại thời điểm khởi chạy thanh toán (`launchBillingFlow`).
  - Mã hóa một chiều SHA-256 hex string của canonical user ID thành `obfuscatedAccountId` truyền vào `BillingFlowParams` (khớp chính xác với đặc tả backend B04a).
  - Kiểm tra danh mục cho phép (`ALLOWED_PRODUCT_IDS`) trước khi xử lý, từ chối mọi mã sản phẩm lạ.
  - Cơ chế bảo vệ callback muộn (Late callback guard): Khi giao dịch được thanh toán bởi Người dùng A, nhưng Người dùng A đăng xuất và Người dùng B đăng nhập trước khi Google Play hoàn tất xác nhận (`acknowledge`), quyền VIP được lưu trữ bảo toàn cho Người dùng A trong `BillingEntitlementStore`, tuyệt đối **không** cấp nhầm hoặc làm đột biến tài khoản của Người dùng B.
  - Ràng buộc tài khoản khách (`bindPurchasesToCurrentUser`): Sử dụng `BillingEntitlementStore.bindGuestEntitlementsToUser` để chuyển giao quyền từ khách sang tài khoản đăng nhập mà không cộng dồn thời hạn lặp lại (idempotent).
  - Che giấu dữ liệu nhạy cảm (`maskToken`): Tất cả token giao dịch và ID nhạy cảm đều được che giấu trong log (`tok_...1234`).
- **Tập tin đã sửa/tạo (Strict Whitelist):**
  - `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt` (Tạo mới)
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (Cập nhật launch, process, late-callback guard, bind)
  - `app/src/test/java/com/tscanner/app/BillingPurchaseVerificationTest.kt` (Tạo mới 7 bài kiểm thử)
  - `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt` (Cập nhật default timestamp)
  - `app/src/test/java/com/tscanner/app/BillingManagerTest.kt` (Cập nhật kiểm tra null expiry cho VIP trọn đời)

---

## 2. Chi tiết thực hiện

### 2.1. Adapter xác thực `PlayPurchaseVerifier.kt`
- Hiện thực `PurchaseVerifier`.
- Hàm `computeObfuscatedAccountId(canonicalUserId)`: Băm SHA-256 ra chuỗi 64 ký tự hex khớp với backend Node.js (`backend/billing-verifier/`).
- Enforce allowlist: Kiểm tra `BillingManager.ALLOWED_PRODUCT_IDS`. Nếu sản phẩm nằm ngoài danh mục, trả về `VerificationResult.Rejected(PRODUCT_NOT_ALLOWED)`.
- Enforce backend gate: Khi không cấu hình `backendUrl` và `allowLocalFallback == false` (môi trường sản xuất), trả về `VerificationResult.MissingBackendGate`.
- Hỗ trợ sandbox development/unit test (`isLocalFallbackAllowed()`): Tự động phát hiện môi trường kiểm thử JVM để hỗ trợ chạy test nội bộ mà không cần backend remote thực tế.

### 2.2. Kiểm soát luồng thanh toán và Session trong `BillingManager.kt`
- `launchBillingFlow`: Ghi nhận `activePurchaseOwnerUserId = AppAuthManager.getCurrentUser()?.id` và đính kèm `obfuscatedAccountId` vào `BillingFlowParams`.
- `processPurchase`:
  1. Kiểm tra allowlist trước: Nếu sản phẩm không hợp lệ, lập tức trả về lỗi và dừng xử lý.
  2. Gửi yêu cầu xác thực sang `verifier.verifyPurchase(request)`.
  3. Xử lý theo kết quả có kiểu (`VerificationResult`):
     - `Success`: Thực hiện `acknowledgePurchase` nếu cần, sau đó gọi `applyVerifiedEntitlement(result.entitlement, targetOwnerId)`.
     - `MissingBackendGate`: Chặn giao dịch, ghi log cảnh báo với token đã che giấu, thông báo cho callback với lý do cụ thể.
     - `Rejected`: Báo lỗi từ chối, không cấp quyền.
     - `Pending` / `TransientError`: Báo trạng thái tương ứng, giữ nguyên cache.
- `applyVerifiedEntitlement`:
  - Lưu entitlement vào `BillingEntitlementStore`.
  - Kiểm tra chủ sở hữu: Chỉ cập nhật in-memory cho `AppAuthManager.getCurrentUser()` nếu ID của người dùng hiện tại khớp với `targetOwnerId`. Nếu người dùng đã đổi sang tài khoản khác, chỉ lưu quyền cho tài khoản sở hữu ban đầu, bảo vệ tài khoản mới khỏi bị cấp nhầm.
- `bindPurchasesToCurrentUser`: Tích hợp với `BillingEntitlementStore.bindGuestEntitlementsToUser`, chuyển quyền và áp dụng snapshot an toàn, loại bỏ hoàn toàn cơ chế cộng dồn ngày tương đối (`durationDays`).

---

## 3. Kết quả kiểm thử & Bằng chứng

### 3.1. Unit Tests (`BillingPurchaseVerificationTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingPurchaseVerificationTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **7/7 PASS** (0 failed, 0 skipped).

Chi tiết 7 bài kiểm thử trong `BillingPurchaseVerificationTest`:
1. `testCatalogAllowlist_unknownProduct_isRejectedImmediately`: PASS (Sản phẩm lạ bị từ chối ngay lập tức, không grant VIP).
2. `testMissingBackendGate_whenFallbackDisabled_blocksPaidPurchase`: PASS (Khi tắt fallback và không có backend URL, purchase bị chặn bởi `MissingBackendGate`).
3. `testLateAck_accountSwitchBeforeCallback_doesNotMutateNewUser`: PASS (A mua, đổi sang B trước khi ack hoàn tất, B không bị cấp VIP nhầm, quyền lưu đúng về A).
4. `testObfuscatedAccountId_computesSha256HexMatchingBackend`: PASS (Tính toán SHA-256 chuẩn xác 64 ký tự hex).
5. `testTokenMasking_masksSensitiveDataInLogs`: PASS (Che giấu token nhạy cảm an toàn trong logs).
6. `testDuplicatePurchaseProcessing_isIdempotent`: PASS (Xử lý cùng một token nhiều lần không làm tăng hạn sử dụng).
7. `testCustomVerifierInjection_rejectedReasonHandledGracefully`: PASS (Giao dịch bị hoàn tiền/hủy từ backend được xử lý chuẩn mực).

### 3.2. Chạy hồi quy các Probe lỗi từ B00 (`BillingReauditRegressionTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingReauditRegressionTest.probeUnknownProductMustNotGrantVip' --tests 'com.tscanner.app.BillingReauditRegressionTest.probeLateAckMustNotGrantToDifferentAccount' --tests 'com.tscanner.app.BillingReauditRegressionTest.probeRepeatedBindingMustNotExtendExpiry' --tests 'com.tscanner.app.BillingReauditRegressionTest.probeDuplicateTokenMustNotExtendExpiry'
```
Kết quả: **BUILD SUCCESSFUL**, cả 4 probe lỗi F04, F05, F02 đã chính thức chuyển xanh (**4/4 PASS**).

### 3.3. Bộ test tổng hợp toàn bộ hệ thống Billing & Auth
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingPurchaseVerificationTest' --tests 'com.tscanner.app.BillingEntitlementStoreTest' --tests 'com.tscanner.app.BillingEntitlementContractTest' --tests 'com.tscanner.app.BillingManagerTest' --tests 'com.tscanner.app.VipPurchaseActionCoordinatorTest' --tests 'com.tscanner.app.AppAuthCanonicalIdentityTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **66/66 PASS** (0 failed, 0 skipped).

### 3.4. Biên dịch và Đóng gói APK Debug (`:app:assembleDebug`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:assembleDebug
```
Kết quả: **BUILD SUCCESSFUL in 10s** (39 actionable tasks: 3 executed, 36 up-to-date).

### 3.5. Vị trí bằng chứng
- `build/vip-billing-b06/TEST-com.tscanner.app.BillingPurchaseVerificationTest.xml`
- `build/vip-billing-b06/TEST-com.tscanner.app.BillingManagerTest.xml`
- `build/vip-billing-b06/TEST-com.tscanner.app.BillingEntitlementStoreTest.xml`

---

## 4. Trạng thái và bước tiếp theo

Gói **B06** đã hoàn thành trọn vẹn, vượt qua mọi kiểm thử đơn vị và đóng gói thành công.
Sẵn sàng thực hiện gói tiếp theo theo kế hoạch:
👉 **`B07 — Reconcile và restore chờ đủ kết quả (F03/F06)`**:
- Tái cấu trúc `restorePurchases` và `syncActivePurchasesInternal`.
- Coalesce kết quả query cả SUBS và INAPP, chỉ hoàn tất restore khi toàn bộ quá trình query và verification/ack đã có kết quả xác định.
- Phân biệt rõ lỗi mạng tạm thời (không tự ý xóa cache VIP) với kết quả rỗng có thẩm quyền (revoke VIP).
