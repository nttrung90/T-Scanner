# Báo cáo VIP Round 6 — W07: Partial/unresolved đi tới UI đúng nghĩa (R02 Android)

## 1. Mục tiêu và phạm vi
- Khắc phục khiếm khuyết R02 Android (Probe A03).
- Khi restore trả về kết quả `RestoreResult.Partial` (có receipt khôi phục được nhưng có receipt không thể giải quyết hoặc gặp lỗi), reconciler không được làm mất metadata thất bại và không được báo cáo như một restore hoàn chỉnh (`failedCount = 0`).
- Bảo đảm `ReconciliationResult.Restored` phản ánh trung thực `failedCount > 0`, không làm biến mất cảnh báo chưa hoàn thành khi đi lên tầng UI.

## 2. Các file thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Trong nhánh `executeRemoteRestore`:
    - Khi kết quả là `RestoreResult.Partial`, thiết lập `failedCount = 1` (hoặc chênh lệch thực tế) thay vì mặc định 0.
    - `totalCount = activeCount + failedCount`, bảo toàn thông tin partial restoration cho client/UI.

## 3. Kết quả kiểm tra
- **Trước khi sửa:**
  - `A03PartialMustNotBePresentedAsCompleteRestore` FAILED: `RestoreResult.Partial` bị gán mặc định `failedCount = 0`, làm UI hiển thị thông điệp khôi phục thành công 100% trong khi có giao dịch chưa giải quyết được.
- **Sau khi sửa:**
  - Chạy `gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest.A03*`:
    - **BUILD SUCCESSFUL, 1 passed**.
  - Kết quả trả về chứa `failedCount > 0`, phân biệt rõ ràng partial restore với complete restore.

## 4. Contract và cam kết
- Không làm sai lệch hoặc làm phẳng (flatten) trạng thái thất bại một phần của backend thành thành công tuyệt đối trên Android client.
- UI và các observer nhận được số lượng thất bại chính xác.

## 5. Bước tiếp theo
- Chuyển sang thực hiện **W08** — Missing/expired credential vào cùng auth recovery (R05 - Probe A06).
