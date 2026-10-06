# Báo Cáo Triển Khai VIP Vòng 4 — Gói T11: Ack Authority & Durable Grant Trên Android (R10)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R10, xác lập rõ ràng thẩm quyền acknowledge: backend verifier đã cam kết cấp quyền và đưa job acknowledge vào outbox một cách nguyên tử (hoàn tất ở M05). Android client áp dụng quyền VIP bền vững ngay lập tức khi nhận được xác thực thành công từ server, không để lỗi phụ từ lệnh gọi acknowledge thứ cấp của Play Billing Client SDK làm nghẽn hoặc trì hoãn việc cấp quyền cho người dùng.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Trong luồng xử lý `processPurchase`:
    1. Khi nhận được `VerificationResult.Success(entitlement)` từ máy chủ, gọi ngay `applyVerifiedEntitlement(result.entitlement, targetOwnerId)`.
    2. Nếu việc lưu trữ và chiếu snapshot thành công và phiên không bị stale, phát thông báo thành công và gọi `onComplete?.invoke(true)`.
    3. Lệnh gọi acknowledge thứ cấp qua `billingClient.acknowledgePurchase` chạy ở chế độ hỗ trợ (best-effort/auxiliary). Nếu SDK acknowledge trả về `BillingResponseCode.ERROR` hoặc mất mạng, ghi log cảnh báo và không hủy bỏ quyền VIP trong store vì server outbox đảm bảo việc retry tự động.
- `REPORT_VIP_R4_T11.md`: Báo cáo nội bộ gói T11.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `com.tscanner.app.VipRound4RegressionTest.durableVerifiedEntitlementMustSurviveRedundantClientAckFailure`
  - Trước (T00): **FAIL** (SDK acknowledge báo ERROR làm quyền VIP bền vững từ server không được cấp vào store)
  - Sau (T11): **PASS** (Quyền VIP được cấp và lưu trữ bền vững độc lập với kết quả acknowledge thứ cấp của SDK client)

### 2.2. Kiểm Tra Hồi Quy Suite Round 4
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound4RegressionTest --offline --console=plain`
- Kết quả: **8 / 9 PASS**, chỉ còn duy nhất 1 probe `canceledDriveWorkerMustNotStartUploadAfterTokenWait` (thuộc T12).

---

## 3. Handoff Cho Gói Sau (T12)

- Luồng thanh toán và acknowledge client/server đã hoàn toàn đồng bộ và bền vững.
- Gói T12 tiếp nhận: Sửa R11 (Cancellation worker Drive):
  - File: `app/src/main/java/com/tscanner/app/utils/GoogleDriveBackupWorker.kt`.
  - Khắc phục probe đỏ cuối cùng: `canceledDriveWorkerMustNotStartUploadAfterTokenWait`.
  - Tích hợp kiểm tra coroutine cancellation cooperative (`coroutineContext.ensureActive()` hoặc `coroutineContext.isActive`) ngay sau khi hoàn tất token lookup / folder wait và trước khi bắt đầu tải file lên Google Drive. Nếu Job bị hủy, ném `CancellationException` và dừng ngay lập tức, không bắt đầu upload.
- Chuyển tiếp tự động sang T12.
