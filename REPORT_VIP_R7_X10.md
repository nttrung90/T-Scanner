# Báo cáo VIP R7 — Gói X10: Partial/pending/unresolved đi tới UI và feature gates (F06 Android)

## 1. Trạng thái và Mục tiêu
- **Mục tiêu:** Sửa lỗi F06 phía Android — phân biệt chính xác số receipts thành công (`count`), số receipts thất bại/chưa giải quyết (`failedCount`), và truyền đạt đúng thông điệp khôi phục một phần (Partial) lên `BillingManager` và UI (`VipUpgradeDialog`), không xóa thông tin lỗi và không báo hoàn tất toàn bộ khi có giao dịch unresolved/failed.
- **Trạng thái:** **DONE** (toàn bộ 18/18 Android probes PASS: A01–A08, C01–C06, D01–D04).

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
  - Trong callback `reconciler.reconcile`: Khi nhận `ReconciliationResult.Restored`, kiểm tra `result.failedCount > 0`. Nếu có lỗi hoặc một phần chưa giải quyết được, thông báo: `"Khôi phục một phần: Một số giao dịch chưa thể hoàn tất."`. Nếu toàn bộ thành công (`failedCount == 0`), thông báo: `"Đã khôi phục thành công gói VIP từ Google Play!"`.
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`:
  - Trong `executeRemoteRestore`:
    - Khi backend restore trả về `TransientError` nhưng trên thiết bị có local purchases thành công, trả về `ReconciliationResult.Restored` với `totalCount = deviceSuccessfulPurchases.size + 1` và `failedCount = 1`, đảm bảo không che giấu lỗi remote restore.
    - Khi backend restore trả về `Rejected` nhưng trên thiết bị có local purchases, giữ `failedCount = 1`.
    - Tính toán `failedCount` chuẩn xác từ `results[]` (các item có status khác SUCCESS) cộng với số lượng deviceUnresolved và playQueryUnresolved.

## 3. Kết quả kiểm thử
- Probe A02 (`A02RemoteFailureAfterLocalSuccessMustNotReportCompleteRestore`): **PASS**
- Probe A03 (`A03PartialResultMustReachManagerCallbackAsPartial`): **PASS**
- Probe A04 (`A04ParserMustPreserveActualPerTokenFailureCount`): **PASS**
- Probe A05 (`A05CachedUnresolvedEntitlementMustNotCountAsFreshRestoreSuccess`): **PASS**
- Toàn bộ suite `VipRound7RegressionTest`: **18/18 tests PASS** (100%).

## 4. Contract đã áp dụng
- Không giả lập toàn bộ thành công khi có item lỗi/pending/unresolved.
- Tách bạch số lượng quyền đang hoạt động được khôi phục mới với số lượng giao dịch thất bại.
- Message hiển thị trên UI phản ánh trung thực kết quả khôi phục một phần.

## 5. Bước kế tiếp
- Tự động chuyển sang gói **X11**: Kiểm chứng host độc lập và acceptance toàn bộ ma trận (27 probes vòng 7, 12 probes vòng 6, 16 probes vòng 5, full unit test suites, lintDebug 0 errors, assembleDebug).
