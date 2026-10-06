# Báo Cáo Triển Khai VIP Vòng 3 — Gói M08: Restore Server-Authoritative & Migration Identity Client (F03, F04 Client)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F03 (danh sách Play rỗng tự thu hồi quyền server và tự tăng version) và F04 phía client (ID entitlement thay đổi khiến client giữ cả quyền active cũ). Đảm bảo quyền trên server được bảo toàn khi thiết bị không có receipt cục bộ, và snapshot merge khử trùng lặp theo `purchaseToken`.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Loại bỏ hoàn toàn khối mã Subcase 2A tự ý đánh dấu `REVOKED` và tự tăng `snapshotVersion + 1L` khi `validPurchasedItems.isEmpty()`.
  - Thiết lập quy tắc hợp đồng Round 3: Thiết bị có danh mục Google Play rỗng chỉ có nghĩa là Play Store trên máy hiện không có receipt; quyền VIP có thẩm quyền từ máy chủ vẫn được giữ nguyên trong bộ nhớ cache hợp lệ và cập nhật profile tương ứng.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlement.kt`:
  - Sửa đổi thuật toán `mergeNewerSnapshot`:
    1. Khi khởi tạo danh sách hiện tại: khử trùng lặp theo `purchaseToken`, giữ bản ghi có `snapshotVersion` cao hơn.
    2. Khi áp dụng snapshot mới đến (`incomingItem`): tìm kiếm bản ghi cũ theo `purchaseToken` (kể cả khi `id` khác nhau do định dạng cũ/mới). Nếu bản ghi mới có `snapshotVersion` lớn hơn, xóa bản ghi cũ có `id` khác ra khỏi map và áp dụng bản ghi mới.
    3. Triệt tiêu hoàn toàn lỗi giữ song song 1 bản ghi `VERIFIED_ACTIVE` cũ và 1 bản ghi `EXPIRED` mới cho cùng một token.
- `app/src/test/java/com/tscanner/app/BillingRevocationReconciliationTest.kt`:
  - Đính chính test cũ `testRevoke_thenReload_thenRestart_persistedStateRemainsRevoked`: Bài test cũ trước đây kiểm tra hành vi lỗi (empty Play catalog tự thu hồi quyền). Bài test được cập nhật theo đúng hợp đồng Round 3: kiểm tra thu hồi có thẩm quyền qua phản hồi `PURCHASE_REVOKED` của verifier, xác minh tính bền vững của trạng thái thu hồi sau khi reload profile và restart store.
- `REPORT_VIP_R3_M08.md`: Báo cáo nội bộ gói M08.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Hai Probe Đỏ M00 Chuyển Xanh
1. Test: `com.tscanner.app.VipRound3RegressionTest.emptyDeviceCatalogMustPreserveServerEntitlement`
   - Trước (M00): **FAIL** (Restore với device catalog rỗng đã xóa quyền server)
   - Sau (M08): **PASS** (Quyền server còn hạn được bảo toàn nguyên vẹn khi thiết bị không có receipt Play)
2. Test: `com.tscanner.app.VipRound3RegressionTest.expiredServerIdMustReplaceActiveToken`
   - Trước (M00): **FAIL** (Bản ghi EXPIRED v2 với ID khác không xóa bản ghi ACTIVE v1)
   - Sau (M08): **PASS** (Bản ghi mới cùng token thay thế hoàn toàn bản ghi cũ, snapshot không còn active)

### 2.2. Kiểm Tra Focused Reconciliation Suite
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRevocationReconciliationTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, 7/7 tests **PASS**.

---

## 3. Handoff Cho Gói Sau (M09)

- Quyền server và cơ chế dedupe identity token trên Android đã hoạt động chính xác.
- Gói M09 tiếp nhận: Sửa F07 (Durable apply một lần và kết quả UI đúng):
  - File: `BillingManager.kt`, `AppAuthManager.kt`, `BillingEntitlementStore.kt`, `BillingReconciliation.kt`, `VipUpgradeDialog.kt`.
  - Xử lý 3 probe đỏ:
    1. `canceledPaidPeriodMustRestoreSuccessfully`: gói `CANCELED_ACTIVE` còn hạn trả về `completion(true)` thay vì `false`.
    2. `staleActiveSnapshotMustNotReportPurchaseSuccess`: snapshot ACTIVE cũ (v1) bị store bỏ qua do trên máy đã có REVOKED v9 phải trả về `completion(false)`.
    3. `secondStoreCommitFailureMustNotReportSuccess`: loại bỏ cơ chế double-write (Store commit 2 lần gây lỗi phân rã); suy ra kết quả và profile trực tiếp từ snapshot đã commit bền vững.
- Chuyển tiếp tự động sang M09.
