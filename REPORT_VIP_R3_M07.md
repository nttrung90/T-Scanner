# Báo Cáo Triển Khai VIP Vòng 3 — Gói M07: Android Strict Response & Lưu Tombstone (F05, F06 Android)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F05 và F06 phía Android client: xác thực nghiêm ngặt mã trạng thái HTTP và schema phản hồi từ backend verifier, cấm trạng thái `UNKNOWN` hoặc thiếu `expiryTimeMillis` tự động cấp VIP trọn đời, đồng thời truyền tải và lưu trữ snapshot tombstone thu hồi quyền có thẩm quyền vào `BillingEntitlementStore` khi nhận được `PURCHASE_REVOKED` hoặc `PURCHASE_EXPIRED`.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`:
  - Mở rộng `VerificationResult.Rejected` bổ sung trường tùy chọn `val tombstone: BillingEntitlement? = null` nhằm mang theo bản ghi thu hồi thẩm quyền từ backend verifier.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Triển khai hàm phân tích nghiêm ngặt `parseEntitlementStrict`:
    1. Kiểm tra trạng thái `state`: từ chối nếu rỗng hoặc `UNKNOWN` hoặc không thuộc enum `EntitlementState`; tuyệt đối không ngầm định fallback về `VERIFIED_ACTIVE`.
    2. Đối với gói `subs`: bắt buộc phải có `expiryTimeMillis > 0`. Nếu thiếu hoặc null, từ chối và trả về `TransientError`, ngăn chặn hoàn toàn lỗi biến gói đăng ký thành quyền trọn đời vô hạn.
    3. Trạng thái `SUCCESS` bắt buộc phải đi kèm mã trạng thái HTTP 200 (không chấp nhận 400 hay 409).
    4. Trạng thái `REJECTED`: phân tích snapshot tombstone nếu backend cung cấp để trả về trong `VerificationResult.Rejected`.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Trong nhánh `VerificationResult.Rejected`: nếu lý do là `PURCHASE_REVOKED` hoặc `PURCHASE_EXPIRED`, hệ thống sẽ sử dụng tombstone trả về (hoặc tự tạo tombstone thẩm quyền cho sản phẩm/token tương ứng) và gọi `applyVerifiedEntitlement(tombstone, targetOwnerId)` để lưu vào `BillingEntitlementStore` và cập nhật profile người dùng.
- `app/src/test/java/com/tscanner/app/PlayPurchaseVerifierHttpTest.kt`:
  - Cập nhật và bổ sung các bài kiểm thử HTTP verifier đối soát đầy đủ các nhánh lỗi và thành công.
- `REPORT_VIP_R3_M07.md`: Báo cáo nội bộ gói M07.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Hai Probe Đỏ M00 Chuyển Xanh
1. Test: `com.tscanner.app.VipRound3RegressionTest.malformedStateMustNotBecomeActive`
   - Trước (M00): **FAIL** (Payload `{ state: "UNKNOWN" }` và thiếu expiry bị parse thành active lifetime VIP)
   - Sau (M07): **PASS** (Strict validation từ chối, `result` là `TransientError`, không cấp VIP)
2. Test: `com.tscanner.app.VipRound3RegressionTest.rejectionMustPersistAuthoritativeRevocation`
   - Trước (M00): **FAIL** (Rejection bị bỏ qua, store vẫn giữ quyền active)
   - Sau (M07): **PASS** (Tombstone `REVOKED` được áp dụng vào store, `isVipActive()` trở về `false`)

### 2.2. Kiểm Tra Focused Http Suite
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PlayPurchaseVerifierHttpTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, toàn bộ 11/11 tests **PASS**.

---

## 3. Handoff Cho Gói Sau (M08)

- Việc parse phản hồi nghiêm ngặt và lưu trữ tombstone thu hồi đã hoạt động chính xác.
- Gói M08 tiếp nhận: Sửa F03 và F04 phía client:
  - File: `BillingReconciliation.kt`, `BillingEntitlement.kt`, `BillingEntitlementStore.kt`, `PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt`, `BillingManager.kt`.
  - Xử lý probe `emptyDeviceCatalogMustPreserveServerEntitlement` (F03): danh mục Google Play trên thiết bị rỗng không được phép tự động thu hồi quyền hợp lệ của tài khoản trên server và client không được tự mint version.
  - Xử lý probe `expiredServerIdMustReplaceActiveToken` (F04 client): sửa thuật toán `mergeNewerSnapshot` trong `BillingEntitlement.kt` để khử trùng lặp (dedupe) theo `purchaseToken` thay vì chỉ theo `id`, xóa bỏ quyền active cũ khi nhận được bản ghi hết hạn mới cùng token nhưng khác định dạng ID.
- Chuyển tiếp tự động sang M08.
