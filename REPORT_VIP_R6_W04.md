# Báo cáo VIP Round 6 — W04: Restore/sync thuộc scope và operation có thể hủy (R03)

## 1. Mục tiêu và phạm vi
- Khắc phục khiếm khuyết R03 (Probe A04).
- Đảm bảo các tiến trình restore/sync bất đồng bộ (`BillingReconciliation.reconcile` và `BillingManager.syncPurchases`/`restorePurchasesAsync`) gắn chặt chẽ vào lifecycle của `BillingManager` thông qua `CoroutineScope` quản lý.
- Khi `BillingManager.destroy()` được gọi, toàn bộ công việc coroutine in-flight phải bị hủy kịp thời (`isDestroyed = true`, cancel `scope`), không cho phép response muộn sau khi destroy thực hiện ghi snapshot xuống DB/datastore hoặc phát sinh callback UI.

## 2. Các file thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Thêm tham số `coroutineScope: CoroutineScope? = null` vào `reconcile`.
  - Thay thế việc khởi tạo `CoroutineScope(ioDispatcher).launch` độc lập (unmanaged) bằng `(coroutineScope ?: CoroutineScope(ioDispatcher)).launch`.
  - Thêm kiểm tra `if (!isActive) return@launch` sau lệnh gọi remote restore `verifier.restorePurchases`, đồng thời bảo đảm re-throw `CancellationException` để coroutine framework hủy sạch sẽ.
- `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
  - Thêm flag `@Volatile private var isDestroyed = false`.
  - Trong `destroy()`, gán `isDestroyed = true` và `scope.cancel()`.
  - Truyền `scope` vào `BillingReconciliation.reconcile(..., coroutineScope = scope)`.
  - Trong callback `onComplete` của reconcile, kiểm tra `if (isDestroyed || !scope.isActive) return@reconcile` trước khi kích hoạt `notifyPurchaseUpdated` hoặc UI callbacks.

## 3. Kết quả kiểm tra
- **Trước khi sửa:**
  - `A04DestroyedManagerMustNotCommitSuspendedRestore` FAIL: DB snapshot vẫn bị ghi và callback vẫn kích hoạt sau khi `mgr.destroy()`.
- **Sau khi sửa:**
  - Chạy `gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest.A04*`:
    - **BUILD SUCCESSFUL, 1 passed**.
  - Chạy toàn bộ suite `VipRound6RegressionTest`:
    - **4 passed / 5 failed** (A04, A07, A08, A09 PASS).
    - 5 bài test còn lại (A01, A02, A03, A05, A06) dành cho các gói tiếp theo W05-W08.

## 4. Contract và cam kết
- Không làm rò rỉ bất kỳ unmanaged coroutine nào ra ngoài vòng đời component.
- Cancellation semantics chuẩn Kotlin Coroutines (`CancellationException` không bị nuốt thành lỗi logic thông thường).
- Không ảnh hưởng tiêu cực đến các luồng thanh toán / restore bình thường khi manager còn hoạt động (`isActive == true`).

## 5. Bước tiếp theo
- Chuyển sang thực hiện **W05** — Hợp nhất toàn bộ account receipts cho mọi nhánh Play (R01 - Probes A01, A02).
