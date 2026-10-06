# Báo Cáo Triển Khai VIP Vòng 4 — Gói T10: Android Restore Thật Theo App Account (R01 Client)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R01 phía client: triển khai phương thức `restorePurchases` thực tế gọi remote endpoint `/api/v1/billing/restore` của backend verifier; đảm bảo thiết bị mới cài đặt hoặc thiết bị có danh mục Google Play rỗng vẫn kết nối tới máy chủ để truy vấn và khôi phục các quyền VIP có thẩm quyền của app account.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`:
  - Định nghĩa các kiểu dữ liệu hợp đồng khôi phục: `PurchaseCandidate`, `RestoreRequest`, `RestoreResult` (`Success`, `Partial`, `TransientError`, `Rejected`, `NotConfigured`).
  - Mở rộng giao diện `PurchaseVerifier` với phương thức `suspend fun restorePurchases(request: RestoreRequest): RestoreResult`.
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Hiện thực hóa `restorePurchases`: gửi yêu cầu HTTPS POST tới `/api/v1/billing/restore` kèm Bearer token xác thực và danh sách candidate purchases (nếu có).
  - Phân tích phản hồi với `parseRestoreResponse`: phân tích nghiêm ngặt từng entitlement trong snapshot trả về, cập nhật đúng danh sách quyền của `ownerAppUserId`.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Trong `evaluateAndProcess`: khi danh mục Play trên thiết bị rỗng (`validPurchasedItems.isEmpty()`), hệ thống không chỉ kiểm tra cache cục bộ mà thực hiện gọi `verifier.restorePurchases(RestoreRequest(targetOwnerId, emptyList()))` trên coroutine scope.
  - Áp dụng snapshot có thẩm quyền từ máy chủ vào `BillingEntitlementStore` và `AppAuthManager`, trả về kết quả `Restored` nếu tài khoản có quyền active trên máy chủ.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Truyền `verifier` và `ioDispatcher` khi khởi tạo `BillingReconciliation` trong các luồng `performReconciliation` và `syncActivePurchasesInternal`.
- `REPORT_VIP_R4_T10.md`: Báo cáo nội bộ gói T10.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound4RegressionTest.emptyCatalogMustReachRemoteRestore`
  - Trước (T00): **FAIL** (Thiết bị mới có catalog rỗng không bao giờ gửi request HTTP tới máy chủ khôi phục: `requests == 0`)
  - Sau (T10): **PASS** (Reconciler thực hiện gửi request HTTP POST tới `/api/v1/billing/restore`: `requests > 0`)

### 2.2. Kiểm Tra Tiến Độ Regression Android
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound4RegressionTest --offline --console=plain`
- Kết quả: **7 / 9 PASS**, chỉ còn 2 probe chưa đạt (thuộc T11 và T12).

---

## 3. Handoff Cho Gói Sau (T11)

- Client restore theo tài khoản ứng dụng đã kết nối trọn vẹn với backend.
- Gói T11 tiếp nhận: Sửa R10 (Ack authority và durable grant trên Android):
  - File: `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`.
  - Khắc phục probe đỏ: `durableVerifiedEntitlementMustSurviveRedundantClientAckFailure`.
  - Chốt thẩm quyền: Backend là bên chịu trách nhiệm acknowledge và retry outbox bền vững (đã hoàn tất ở M05). Client không chặn việc cấp quyền VIP đã được server xác thực và cam kết bền vững chỉ vì lệnh gọi acknowledge thứ cấp của Play Billing Client gặp lỗi (`BillingClient.BillingResponseCode.ERROR`).
- Chuyển tiếp tự động sang T11.
