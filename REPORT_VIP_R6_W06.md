# Báo cáo VIP Round 6 — W06: Một commit và propagation lỗi profile (R04)

## 1. Mục tiêu và phạm vi
- Khắc phục khiếm khuyết R04 (Probe A05).
- Loại bỏ double commit trong đường dẫn remote restore của `BillingReconciliation.kt`.
- Sử dụng trực tiếp `AppAuthManager.projectSnapshotToProfile` để chiếu snapshot đã commit lên hồ sơ in-memory và lưu trữ cục bộ, thay vì gọi lại `applyEntitlementSnapshot` vốn gây ra lần commit store thứ hai không cần thiết.
- Kiểm tra tính hợp lệ và trạng thái trả về của `projectSnapshotToProfile`. Nếu quá trình lưu thông tin profile thất bại, reconciliation không được báo thành công (`ProcessingFailed`), đảm bảo tính nhất quán tuyệt đối giữa trạng thái store và profile người dùng.

## 2. Các file thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Thay thế `AppAuthManager.applyEntitlementSnapshot(context, committedSnapshot)` bằng `AppAuthManager.projectSnapshotToProfile(context, committedSnapshot)`.
  - Bổ sung kiểm tra kết quả `if (!projected) { onResult(ReconciliationResult.ProcessingFailed(...)); return@launch }`.
  - Áp dụng cấu trúc chuẩn cho cả hai nhánh empty catalog và nonempty catalog thông qua `executeRemoteRestore`.

## 3. Kết quả kiểm tra
- **Trước khi sửa:**
  - `A05ProjectionFailureMustNotProduceSuccessfulRestore` FAILED: Lần commit thứ hai vào store trả về `false`, nhưng reconciler bỏ qua và vẫn trả `Restored`, profile người dùng vẫn ở trạng thái Free trong khi thông báo khôi phục thành công.
- **Sau khi sửa:**
  - Chạy `gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest.A05*`:
    - **BUILD SUCCESSFUL, 1 passed**.
  - Kiểm tra đối chứng:
    - Nếu persist profile thất bại, reconciler trả về `ProcessingFailed`.
    - Khi persist profile thành công, profile và store đồng bộ trạng thái VIP.

## 4. Contract và cam kết
- Chỉ duy nhất 1 lần ghi bền vững (single durable commit) vào `BillingEntitlementStore` trong một chu trình xử lý restore.
- Không nuốt lỗi (error swallowing) khi lưu profile người dùng.

## 5. Bước tiếp theo
- Chuyển sang hoàn tất kiểm tra và báo cáo cho **W07** (R02 Android - Probe A03).
