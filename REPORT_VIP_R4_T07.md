# Báo Cáo Triển Khai VIP Vòng 4 — Gói T07: Migration Identity Không Vượt Equal-Version Guard (R08)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R08, loại trừ kẽ hở cho phép đổi chuỗi ID của cùng một token để lách qua hàng rào kiểm soát xung đột cùng phiên bản (`equal-version conflict`). Đảm bảo khi `snapshotVersion` bằng nhau mà trạng thái hoặc payload xung đột (ví dụ một bên `REVOKED`, một bên `ACTIVE`), hệ thống ném `IllegalArgumentException` và trả về `ApplySnapshotResult.Conflict`, không cho phép snapshot active cũ hồi sinh quyền đã thu hồi.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/billing/BillingEntitlement.kt`:
  - Trong `mergeNewerSnapshot`:
    1. Khi `incomingItem.snapshotVersion == existingItem.snapshotVersion`: kiểm tra `isPayloadIdentical(incomingItem, existingItem)`.
    2. Nếu payload giống nhau: giữ nguyên bản ghi hiện tại (idempotent replay an toàn).
    3. Nếu payload khác nhau (trạng thái xung đột): ném lỗi `IllegalArgumentException` fail-closed ngay lập tức, bất kể `existingItem.id` và `incomingItem.id` giống hay khác nhau.
    4. Xóa bỏ hoàn toàn nhánh code cũ `if (existingItem.id != incomingItem.id) { ... }` từng cho phép ID khác thay thế bản ghi cùng version.
- `REPORT_VIP_R4_T07.md`: Báo cáo nội bộ gói T07.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound4RegressionTest.equalVersionDifferentIdMustNotResurrectRevokedToken`
  - Trước (T00): **FAIL** (Bản ghi `REVOKED v9` bị ghi đè bởi `ACTIVE v9` có `id: "legacy-id"`)
  - Sau (T07): **PASS** (Phát hiện xung đột cùng version 9, từ chối cập nhật `Conflict`, `REVOKED` được bảo toàn và `isVipActive()` giữ nguyên `false`)

### 2.2. Kiểm Tra Focused Contract Suites
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingEntitlementContractTest --tests com.tscanner.app.BillingEntitlementStoreTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL**, toàn bộ 22/22 tests **PASS**.

---

## 3. Handoff Cho Gói Sau (T08)

- Hàng rào kiểm soát version conflict đã được khóa chặt tuyệt đối.
- Gói T08 tiếp nhận: Sửa R07 (Tombstone đúng token và chỉ do server cấp):
  - File: `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`, `PurchaseVerifier.kt`.
  - Khắc phục 2 probe đỏ:
    1. `rejectionWithoutSnapshotMustNotMintServerVersion` (rejection không có snapshot không được tự ý tăng version của client lên 11).
    2. `rejectionOfOldTokenMustNotRevokeAnotherTokenForSameProduct` (rejection của token X cũ không được thu hồi token Y mới cùng SKU).
  - Loại bỏ hoàn toàn fallback match theo SKU trong nhánh `Rejected` của `BillingManager`; chỉ thu hồi khi có snapshot tombstone có thẩm quyền do server cấp.
- Chuyển tiếp tự động sang T08.
