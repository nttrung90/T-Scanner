# Báo Cáo Triển Khai VIP Vòng 3 — Gói M09: Durable Apply Một Lần & Kết Quả UI Đúng (F07, Thống Nhất Ack Client F09)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F07, xóa bỏ hoàn toàn lỗi double-write vào `BillingEntitlementStore`, đảm bảo trạng thái thành công phản ánh chính xác trạng thái quyền đã thực sự cam kết (`isCurrentlyActive()`), công nhận tính hợp lệ của gói `CANCELED_ACTIVE` còn hạn, và chặn đứng việc phát `success=true` khi snapshot active bị bỏ qua do stale hoặc khi profile projection thất bại.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Tách hàm `projectSnapshotToProfile(context, snapshot)` trực tiếp cập nhật in-memory user profile và lưu trữ SharedPreferences qua `.commit()`, không gọi ghi vào `BillingEntitlementStore` lần 2.
  - Cập nhật `applyEntitlementSnapshot` gọi `store.applySnapshotTyped` rồi chiếu snapshot đã cam kết vào profile.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Trong `processPurchase`: thay đổi điều kiện kiểm tra inactive từ `result.entitlement.state != VERIFIED_ACTIVE` thành `!result.entitlement.isCurrentlyActive()`, công nhận đầy đủ quyền VIP hợp lệ của người dùng ở trạng thái `CANCELED_ACTIVE` hoặc `IN_GRACE_PERIOD`.
  - Trong `applyVerifiedEntitlement`:
    1. Thực hiện đúng 1 lần commit duy nhất vào `BillingEntitlementStore` qua `store.applySnapshotTyped`.
    2. Kiểm tra `committedItem.isCurrentlyActive()` từ chính snapshot đã commit. Nếu snapshot active gửi đến bị store từ chối (do trên máy đã có bản ghi `REVOKED` version cao hơn), hàm trả về `false`, không phát thông báo thành công ảo cho UI.
    3. Chiếu `committedSnapshot` sang profile qua `AppAuthManager.projectSnapshotToProfile`. Nếu projection thất bại, trả về `false`.
- `app/src/test/java/com/tscanner/app/VipRound3RegressionTest.kt`:
  - Cập nhật `secondStoreCommitFailureMustNotReportSuccess` theo đúng hướng dẫn M09: kiểm tra lỗi projection failure và xác nhận số lần commit vào store bền vững bằng đúng 1 (`assertEquals(1, storeCommits)`).
- `REPORT_VIP_R3_M09.md`: Báo cáo nội bộ gói M09.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Ba Probe Đỏ M00 Chuyển Xanh
1. Test: `com.tscanner.app.VipRound3RegressionTest.canceledPaidPeriodMustRestoreSuccessfully`
   - Trước (M00): **FAIL** (`expected: <true> but was: <false>`)
   - Sau (M09): **PASS** (`CANCELED_ACTIVE` còn hạn được trả về `success = true`)
2. Test: `com.tscanner.app.VipRound3RegressionTest.staleActiveSnapshotMustNotReportPurchaseSuccess`
   - Trước (M00): **FAIL** (`expected: <false> but was: <true>`)
   - Sau (M09): **PASS** (Snapshot ACTIVE cũ v1 bị store drop vì máy có REVOKED v9, kết quả trả về `success = false`)
3. Test: `com.tscanner.app.VipRound3RegressionTest.secondStoreCommitFailureMustNotReportSuccess`
   - Trước (M00): **FAIL** (`expected: <false> but was: <true>`)
   - Sau (M09): **PASS** (Số lần commit bền vững vào store = 1, projection thất bại thì callback trả về `success = false`)

### 2.2. Kiểm Tra Tiến Độ Regression Android
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound3RegressionTest --offline --console=plain`
- Kết quả: **8/9 PASS** (Chỉ còn 1 test duy nhất chưa pass là probe F10 thuộc gói M10).

---

## 3. Handoff Cho Gói Sau (M10)

- Logic apply một lần, kiểm tra active chính xác và kết quả UI trung thực đã hoàn tất.
- Gói M10 tiếp nhận: Sửa F10 (Gate VIP/session của Drive worker sau chờ):
  - File: `app/src/main/java/com/tscanner/app/utils/GoogleDriveBackupWorker.kt` (và `CloudBackupManager.kt` nếu cần).
  - Tái kiểm tra quyền VIP (`AppAuthManager.isUserVip()`), thế hệ phiên (`sessionGeneration`), chủ sở hữu tài liệu, và `coroutineContext.ensureActive()` ngay sau khi lấy OAuth token từ Google và trước khi bắt đầu tải file lên Google Drive.
  - Giải quyết probe đỏ cuối cùng: `driveWorkerMustRecheckVipAfterTokenWait`.
- Chuyển tiếp tự động sang M10.
