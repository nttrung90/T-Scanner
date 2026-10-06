# Báo Cáo Triển Khai VIP Vòng 4 — Gói T08: Tombstone Đúng Token và Chỉ Do Server Cấp (R07)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R07, xóa bỏ hoàn toàn cơ chế fallback tự chế snapshot tombstone và tự tăng `snapshotVersion + 1L` trên Android client khi phản hồi rejection không mang snapshot có thẩm quyền từ server; loại bỏ việc tìm kiếm theo `productId` (SKU match) vốn dẫn đến việc thu hồi nhầm các token khác còn hạn cùng loại sản phẩm.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Trong nhánh `VerificationResult.Rejected`:
    1. Chỉ áp dụng lưu trữ tombstone thu hồi vào store khi phản hồi từ máy chủ thực sự mang snapshot có thẩm quyền (`if (result.tombstone != null)`).
    2. Nếu `result.tombstone == null`: tuyệt đối không tự chế ra bản ghi tombstone và không tự ý tăng `snapshotVersion + 1L` của server; không tìm kiếm theo `productId` để xóa token khác. Ghi nhận cảnh báo và giữ nguyên các entitlement hợp lệ hiện có.
- `app/src/test/java/com/tscanner/app/VipRound3RegressionTest.kt`:
  - Cập nhật `rejectionMustPersistAuthoritativeRevocation`: truyền `tombstone` có thẩm quyền do server cấp trong `VerificationResult.Rejected` đúng theo quy tắc hợp đồng Round 4.
- `app/src/test/java/com/tscanner/app/BillingRevocationReconciliationTest.kt`:
  - Cập nhật `testRevoke_thenReload_thenRestart_persistedStateRemainsRevoked`: truyền snapshot tombstone thẩm quyền từ server.
- `REPORT_VIP_R4_T08.md`: Báo cáo nội bộ gói T08.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Hai Probe Đỏ T00 Chuyển Xanh
1. Test: `com.tscanner.app.VipRound4RegressionTest.rejectionWithoutSnapshotMustNotMintServerVersion`
   - Trước (T00): **FAIL** (Client tự ý tăng version 10 thành 11 khi nhận rejection không mang snapshot)
   - Sau (T08): **PASS** (Không tự chế version, version 10 của server được bảo toàn nguyên vẹn)
2. Test: `com.tscanner.app.VipRound4RegressionTest.rejectionOfOldTokenMustNotRevokeAnotherTokenForSameProduct`
   - Trước (T00): **FAIL** (Receipt cũ bị hết hạn đã thu hồi nhầm receipt `new-valid-token` còn hạn vì cùng SKU)
   - Sau (T08): **PASS** (Xóa bỏ SKU match, token mới `new-valid-token` vẫn duy trì active bình thường)

### 2.2. Kiểm Tra Hồi Quy Suite Round 3 & Reconciliation
- `VipRound3RegressionTest.kt`: **9/9 PASS (100% GREEN)**
- `BillingRevocationReconciliationTest.kt`: **7/7 PASS (100% GREEN)**

---

## 3. Handoff Cho Gói Sau (T09)

- Quyền sở hữu và phiên bản do server cấp được bảo toàn nghiêm ngặt.
- Gói T09 tiếp nhận: Sửa R09 (Phiên hợp lệ trước mua và recovery 401):
  - File: `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`, `PlayPurchaseVerifier.kt`, `VipPurchaseActionCoordinator.kt`, `BillingManager.kt`.
  - Khắc phục probe đỏ: `expiredSessionMustNotBePurchaseReady`.
  - Trong `PlayPurchaseVerifier.isConfigured()` / preflight: kiểm tra phiên đăng nhập của người dùng có token còn hạn sử dụng hay không (parse claim `exp` của ID token); nếu token đã hết hạn (`System.currentTimeMillis() / 1000 >= exp`), preflight phải trả về `false`, ngăn chặn mở Play Billing Sheet trước khi người dùng làm mới phiên đăng nhập.
- Chuyển tiếp tự động sang T09.
