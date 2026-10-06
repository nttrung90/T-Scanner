# Báo cáo kết quả gói B05 — Writer entitlement tuyệt đối và migration local (F02/F03)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B05

- **Khắc phục lỗi F02 (Cộng dồn thời hạn tương đối sai bản chất):**
  - Trước đây: Replay / restore / verify lại token cũ thực hiện tính `now + 30 ngày`, dẫn đến việc restore nhiều lần làm sai lệch thời hạn sử dụng.
  - Giải pháp: Lưu và áp dụng giá trị `expiryTimeMillis` tuyệt đối (absolute timestamp) từ entitlement snapshot. Replay không bao giờ cộng thêm ngày.
- **Khắc phục lỗi F03 (Thiếu cơ chế phiên bản snapshot & xung đột ghi):**
  - Lưu trữ `BillingEntitlement` theo định dạng JSON có khóa theo `ownerAppUserId`.
  - Thực thi kiểm tra phiên bản đơn điệu (`snapshotVersion` monotonic): Snapshot cũ không thể ghi đè snapshot mới hơn.
- **Hỗ trợ trọn đời (Lifetime VIP):**
  - Entitlement trọn đời lưu với `expiryTimeMillis = null`, luôn kích hoạt vĩnh viễn không hết hạn.
  - Sửa lỗi trong `AppAuthManager.loadVipForUser`: Trước đây yêu cầu `expiresAt != null`, khiến VIP trọn đời (`expiresAt == null`) bị hạ cấp nhầm thành FREE.
- **Ràng buộc tài khoản khách (Guest binding):**
  - API `bindGuestEntitlementsToUser`: Chuyển giao an toàn các entitlement mua khi chưa đăng nhập (`ownerAppUserId = null`) sang tài khoản Google chính tắc (`canonicalUserId`) khi người dùng đăng nhập.
- **Tập tin đã sửa/tạo (Strict Whitelist):**
  - `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlementStore.kt` (Tạo mới)
  - `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (Bổ sung `applyEntitlementSnapshot`, `applyEntitlement`, `getEntitlementSnapshot`, sửa `loadVipForUser`)
  - `app/src/test/java/com/tscanner/app/BillingEntitlementStoreTest.kt` (Tạo mới 9 bài kiểm thử)

---

## 2. Chi tiết thực hiện

### 2.1. Lớp lưu trữ `BillingEntitlementStore.kt`
- Thread-safe singleton với khóa đồng bộ hóa `@Synchronized`.
- Lưu trữ trong `SharedPreferences` chuyên dụng (`tscanner_billing_entitlements`):
  - Khách: khóa `guest_entitlements`
  - Người dùng: khóa `user_entitlements_<canonicalUserId>`
- Xử lý lỗi hỏng dữ liệu: Bọc JSON parsing trong try-catch, tự động fallback trả về snapshot rỗng an toàn, không gây crash ứng dụng.
- Merge logic: Tận dụng `UserEntitlementSnapshot.mergeNewerSnapshot()` đã định nghĩa và kiểm thử tại B03 để đảm bảo tính bất biến, chặn snapshot cũ và ngăn chặn xung đột chủ sở hữu.

### 2.2. Tích hợp `AppAuthManager.kt`
- Bổ sung `applyEntitlementSnapshot(context, snapshot)`: Áp dụng snapshot vào `BillingEntitlementStore`, đồng thời cập nhật tức thời `_currentUser` LiveData và lưu vào profile nếu thuộc về user hiện tại.
- Bổ sung `getEntitlementSnapshot(context, userId)`.
- Cập nhật `loadVipForUser(context, profile)`:
  1. Ưu tiên đọc snapshot từ `BillingEntitlementStore`.
  2. Xác định trạng thái VIP qua `snapshot.isVipActive()`, tier cao nhất và cờ `isLifetimeActive()`.
  3. Nếu không có snapshot mới, đọc fallback từ legacy SharedPreferences với điều kiện `(expiresAt == null || System.currentTimeMillis() <= expiresAt)`.

---

## 3. Kết quả kiểm thử & Bằng chứng

### 3.1. Unit Tests (`BillingEntitlementStoreTest.kt` + `BillingEntitlementContractTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingEntitlementStoreTest' --tests 'com.tscanner.app.BillingEntitlementContractTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **22/22 PASS** (0 failed, 0 skipped).

Chi tiết 9 bài kiểm thử trong `BillingEntitlementStoreTest`:
1. `testIdempotentReplay_reapplyingSnapshot_doesNotExtendExpiration`: PASS (Áp dụng snapshot nhiều lần giữ nguyên timestamp tuyệt đối).
2. `testMonotonicVersioning_staleSnapshotCannotOverwriteNewer`: PASS (Snapshot phiên bản 3 không thể ghi đè phiên bản 5).
3. `testLifetimeEntitlement_storedWithNullExpiry_loadsAsLifetimeVip`: PASS (VIP trọn đời lưu null expiry, profile nhận `isVip = true`, `vipExpiresAt = null`).
4. `testMonthlySubscription_appliesAbsoluteExpiry_activeThenExpired`: PASS (Gói tháng lưu timestamp tuyệt đối, tính đúng trạng thái active).
5. `testGuestBinding_transfersEntitlementsToSignedInUser`: PASS (Chuyển giao quyền từ guest sang tài khoản Google đã đăng nhập, xóa sạch bản ghi guest cũ).
6. `testMultiEntitlement_monthlyAndLifetime_lifetimeDominates`: PASS (Có cả gói tháng và trọn đời, khi gói tháng hết hạn quyền trọn đời vẫn duy trì VIP).
7. `testLegacyMigration_preservesLocalVipGracefully`: PASS (Dữ liệu cũ SharedPreferences nạp trơn tru không bị xóa nhầm thành FREE).
8. `testStorageSafety_corruptJsonDoesNotCrashAndReturnsEmpty`: PASS (JSON hỏng trả về snapshot rỗng an toàn, không ném ngoại lệ).
9. `testAppAuthManager_applyEntitlementSnapshot_updatesCurrentUserLive`: PASS (Áp dụng snapshot cập nhật tức thì LiveData `_currentUser`).

### 3.2. Biên dịch và Đóng gói (`:app:assembleDebug`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:assembleDebug
```
Kết quả: **BUILD SUCCESSFUL in 8s** (39 actionable tasks: 3 executed, 36 up-to-date).

### 3.3. Vị trí bằng chứng
- `build/vip-billing-b05/TEST-com.tscanner.app.BillingEntitlementStoreTest.xml`
- `build/vip-billing-b05/TEST-com.tscanner.app.BillingEntitlementContractTest.xml`

---

## 4. Trạng thái và bước tiếp theo

Gói **B05** đã hoàn thành trọn vẹn, vượt qua mọi kiểm thử đơn vị và đóng gói thành công.
Sẵn sàng thực hiện gói tiếp theo theo kế hoạch:
👉 **`B06 — Verify và session ownership ở client (F04/F05)`**:
- Tích hợp `PurchaseVerifier` và `BillingEntitlementStore` vào `BillingManager.kt`.
- Gắn `accountIdentifiers` (SHA-256 canonical userId) vào purchase params khi khởi chạy flow mua.
- Đảm bảo phiên mua không bị cấp nhầm cho tài khoản khác hoặc token trôi nổi.
