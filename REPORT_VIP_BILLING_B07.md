# Báo cáo kết quả gói B07 — Reconcile và restore chờ đủ kết quả (F03/F06)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B07

- **Khắc phục lỗi F03 / F06 (Restore và Sync báo thành công giả hoặc xóa nhầm khi có lỗi mạng):**
  - Trước đây: `restorePurchases` gọi query và báo callback hoàn tất trước khi quá trình xác thực (`verify`) và xác nhận (`acknowledge`) kết thúc. Khi ack thất bại, client vẫn thông báo khôi phục thành công.
  - Khi một trong các query (SUBS hoặc INAPP) gặp lỗi mạng, hệ thống có thể đối xử như danh sách rỗng hoặc gây xóa nhầm quyền VIP đã lưu trữ.
  - Khi cả hai query đều trả về rỗng một cách có thẩm quyền (người dùng đã hủy gói hoặc không có giao dịch), hệ thống không thu hồi quyền VIP cục bộ (`billing_vip_active`), dẫn đến việc duy trì VIP vĩnh viễn dù gói đã hết hạn.
- **Giải pháp thực hiện trong gói B07:**
  - Tách module điều phối đối soát độc lập `BillingReconciliation.kt` trong package `com.tscanner.app.utils.billing`.
  - Hợp nhất kết quả từ cả hai nguồn `ProductType.SUBS` và `ProductType.INAPP` vào một danh sách giao dịch duy nhất, khử trùng lặp theo `purchaseToken`.
  - Kết quả đối soát trả về kiểu dữ liệu rõ ràng (`ReconciliationResult`):
    - `Restored`: Khôi phục thành công các giao dịch hợp lệ sau khi toàn bộ đã được verify và acknowledge.
    - `NoActivePurchases`: Đồng bộ rỗng có thẩm quyền (cả hai query đều thành công và không có purchase hợp lệ). Thu hồi quyền VIP Play Store cục bộ, bảo lưu các nguồn khác nếu có.
    - `NetworkError`: Một hoặc cả hai query gặp lỗi mạng. Giữ nguyên trạng thái VIP hiện tại, không thu hồi nhầm, trả về mã lỗi thích hợp.
    - `ProcessingFailed`: Xác thực hoặc xác nhận thất bại với các giao dịch tìm thấy. Không báo thành công giả.
  - Cập nhật `BillingManager.kt`:
    - `reconcilePurchases`: Điều phối query song song/tuần tự cho SUBS và INAPP, chuyển giao cho `BillingReconciliation.reconcile`.
    - `restorePurchases`: Đảm bảo chỉ báo kết quả hoàn tất (`onComplete(true)`) khi toàn bộ giao dịch được verify và acknowledge thành công.
- **Tập tin đã sửa/tạo (Strict Whitelist):**
  - `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt` (Tạo mới)
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (Cập nhật `queryPurchasesAsync`, `restorePurchases`, `reconcilePurchases`, thêm dependency `ioDispatcher`)
  - `app/src/test/java/com/tscanner/app/BillingReconciliationTest.kt` (Tạo mới 6 bài kiểm thử)
  - `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt` (Cập nhật default timestamp)

---

## 2. Chi tiết thực hiện

### 2.1. Module điều phối `BillingReconciliation.kt`
- Định nghĩa sealed class `ReconciliationResult`:
  - `Restored(val activeEntitlements: List<BillingEntitlement>)`
  - `NoActivePurchases(val message: String)`
  - `NetworkError(val responseCode: Int, val debugMessage: String)`
  - `ProcessingFailed(val reason: String)`
- Hàm `reconcile`:
  1. Kiểm tra mã lỗi từ `subsResult` và `inappResult`. Nếu có lỗi kết nối/mạng, lập tức trả về `NetworkError` và giữ nguyên trạng thái cache, không thu hồi nhầm.
  2. Gom danh sách purchases từ cả 2 nguồn, khử trùng lặp theo `purchaseToken`.
  3. Nếu không có purchase nào (authoritative empty): Thu hồi snapshot `BILLING_PLAY_STORE` trong `BillingEntitlementStore`, trả về `NoActivePurchases`.
  4. Lọc các purchase có trạng thái `PurchaseState.PURCHASED`.
  5. Xử lý từng purchase qua `processPurchaseFn`. Nếu có bất kỳ giao dịch nào verify hoặc acknowledge thất bại, trả về `ProcessingFailed` (chống false success).
  6. Áp dụng snapshot và trả về `Restored(entitlements)`.

### 2.2. Tích hợp trong `BillingManager.kt`
- Hỗ trợ coroutine IO dispatcher có thể inject (`ioDispatcher: CoroutineDispatcher = Dispatchers.IO`) để đảm bảo test JVM chạy tuần tự/unconfined không bị race condition.
- `restorePurchases`: Kết nối nếu chưa sẵn sàng, gọi `reconcilePurchases` và chỉ báo `onComplete(true)` khi kết quả là `Restored`. Nếu là `NoActivePurchases`, `NetworkError` hoặc `ProcessingFailed`, báo `onComplete(false)`.
- `queryPurchasesAsync`: Tương thích Play Billing Library 8.0.0.

---

## 3. Kết quả kiểm thử & Bằng chứng

### 3.1. Unit Tests (`BillingReconciliationTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingReconciliationTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **6/6 PASS** (0 failed, 0 skipped).
Chi tiết 6 bài kiểm thử:
1. `testAuthoritativeEmptySync_revokesBillingVip_andReturnsNoActivePurchases`: PASS (Đồng bộ rỗng có thẩm quyền thu hồi VIP Play Store cục bộ).
2. `testQueryNetworkError_preservesCachedState_andReturnsNetworkError`: PASS (Lỗi mạng SUBS hoặc INAPP không thu hồi VIP và trả về NetworkError).
3. `testAckFailure_doesNotReportSuccess`: PASS (Ack thất bại trả về ProcessingFailed, không báo thành công).
4. `testSuccessfulRestore_combinesSubsAndInapp_andReturnsRestored`: PASS (Gom SUBS + INAPP, áp dụng entitlement và trả về Restored).
5. `testDuplicatePurchases_areDeduplicatedByToken`: PASS (Khử trùng lặp purchaseToken khi cả 2 query trả về cùng token).
6. `testPendingPurchase_isNotRestored`: PASS (Giao dịch đang chờ thanh toán PENDING không được kích hoạt VIP).

### 3.2. Chuyển xanh các Probe lỗi B00 (`BillingReauditRegressionTest.kt`)
- `probeEmptyAuthoritativeSyncMustRevokeBillingVip`: **PASS**
- `probeAckFailureMustNotReportRestoreSuccess`: **PASS**
- `probeQueryErrorMustPreserveLastKnownBillingState`: **PASS**

### 3.3. Đóng gói ứng dụng (`assembleDebug`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:assembleDebug
```
Kết quả: **BUILD SUCCESSFUL** in 8s.

---

## 4. Bằng chứng lưu trữ
- Thư mục bằng chứng: `build/vip-billing-b07/`
  - `TEST-com.tscanner.app.BillingReconciliationTest.xml`
  - `TEST-com.tscanner.app.BillingReauditRegressionTest.xml`

---

## 5. Trạng thái các Probe còn lại và chuyển giao gói tiếp theo

- Danh sách 9 probe ban đầu:
  1. `probeMissingActivityOrBillingUnavailableMustNotGrantTrialVip` (F01) -> **PASS** (gói B01)
  2. `probeRapidDoubleClickMustNotDuplicatePurchase` (F01) -> **PASS** (gói B01)
  3. `probeDuplicateTokenMustNotExtendExpiry` (F02) -> **PASS** (gói B05/B06)
  4. `probeRepeatedBindingMustNotExtendExpiry` (F02) -> **PASS** (gói B05/B06)
  5. `probeEmptyAuthoritativeSyncMustRevokeBillingVip` (F03) -> **PASS** (gói B07)
  6. `probeAckFailureMustNotReportRestoreSuccess` (F06) -> **PASS** (gói B07)
  7. `probeQueryErrorMustPreserveLastKnownBillingState` (F06) -> **PASS** (gói B07)
  8. `probeRestoreDuringConnectingMustComplete` (F07) -> **FAIL** (Mục tiêu gói B08)
  9. `probeSilentSyncMustNotEmitPurchaseSuccessEvent` (F08) -> **FAIL** (Mục tiêu gói B09)

Handoff sang **B08 — Kết nối và waiter không bị bỏ rơi (F07)**:
- Tập trung vào quản lý trạng thái kết nối (`CONNECTING`), hàng đợi `pendingSetupCallbacks` và xử lý timeout/error không bỏ rơi waiter.
