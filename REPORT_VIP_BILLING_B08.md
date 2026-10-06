# Báo cáo kết quả gói B08 — Kết nối và waiter không bị bỏ rơi (F07)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B08

- **Khắc phục lỗi F07 (Thao tác khi trạng thái BillingClient đang `CONNECTING` làm bỏ rơi hoặc lờ đi các waiter):**
  - Trước đây: Trong `BillingManager.kt`, phương thức `startConnection` chỉ kiểm tra:
    ```kotlin
    if (_connectionState.value == ConnectionState.CONNECTING) {
        return
    }
    ```
    Khi ứng dụng đang kết nối Google Play (ví dụ do `init` tự kích hoạt), nếu người dùng nhấn "Khôi phục giao dịch" (`restorePurchases`), callback `onSetupFinished` bị bỏ qua hoàn toàn và không bao giờ được lưu vào hàng đợi. Khi kết nối thành công, waiter này bị bỏ rơi trong im lặng, dẫn đến giao diện treo loading vô hạn hoặc không hoàn thành.
  - Ngoài ra, không có cơ chế chặn callback cũ tới muộn (`stale callback guard`), khi dịch vụ bị ngắt và kết nối lại, một callback từ thế hệ trước có thể làm sai lệch trạng thái hoặc hoàn tất sai waiter.
  - Không có cơ chế đóng (`teardown/close`) an toàn ngăn chặn việc kết nối bị hồi sinh (`zombie connection`) sau khi đã hủy scope.
- **Giải pháp thực hiện trong gói B08:**
  - Tạo module điều phối kết nối chuyên trách `BillingConnectionCoordinator.kt` tại `com.tscanner.app.utils.billing`.
  - Hợp nhất các yêu cầu kết nối đồng thời (`coalesce concurrent setup requests`) và lưu các waiter vào hàng đợi `pendingWaiters` khi đang ở trạng thái `CONNECTING`.
  - Quản lý thế hệ kết nối đơn điệu (`monotonic generation counter`) để loại bỏ hoàn toàn các callback cũ tới muộn (`stale callback guard`).
  - Khi hoàn tất kết nối (thành công hoặc thất bại), giải phóng toàn bộ hàng đợi (`drainWaiters`) và gọi từng waiter chính xác một lần.
  - Xử lý ngắt kết nối (`onBillingServiceDisconnected`): chuyển trạng thái về `DISCONNECTED`, tăng `generation` để vô hiệu hóa callback đang bay, và thông báo thất bại cho các waiter đang chờ.
  - Cơ chế thử lại kết nối có giới hạn hữu hạn (`maxReconnectAttempts = 5`), không chạy song song hay xung đột với `enableAutoServiceReconnection()` của Play Billing Library v8.
  - Phương thức `close()`: chuyển sang `CLOSED`, hủy tất cả waiter đang chờ với kết quả `false`, và ngăn chặn tuyệt đối việc mở kết nối mới.
  - Cập nhật `BillingManager.kt`: Ủy quyền toàn bộ quản lý kết nối và trạng thái cho `BillingConnectionCoordinator`, thêm phương thức `destroy()`.
- **Tập tin đã sửa/tạo (Strict Whitelist):**
  - `app/src/main/java/com/tscanner/app/utils/billing/BillingConnectionCoordinator.kt` (Tạo mới)
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (Cập nhật vùng connection/retry, tích hợp coordinator và thêm destroy)
  - `app/src/test/java/com/tscanner/app/BillingConnectionCoordinatorTest.kt` (Tạo mới 9 bài kiểm thử bao phủ toàn bộ 6 kịch bản hồi quy)

---

## 2. Chi tiết thực hiện

### 2.1. Module điều phối `BillingConnectionCoordinator.kt`
- Trạng thái `connectionState: StateFlow<ConnectionState>` (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `CLOSED`).
- Danh sách waiter an toàn luồng `pendingWaiters` được bảo vệ bằng monitor lock.
- Hàm `startConnection(onSetupFinished)`:
  - Nếu `CONNECTED`: lập tức gọi `onSetupFinished(true)`.
  - Nếu `CLOSED`: lập tức gọi `onSetupFinished(false)`.
  - Nếu `CONNECTING`: thêm `onSetupFinished` vào `pendingWaiters` (coalesce, không gọi `client.startConnection` lần 2).
  - Nếu `DISCONNECTED`: chuyển sang `CONNECTING`, tăng `generation`, thêm vào `pendingWaiters`, và kích hoạt `client.startConnection(...)`.
- Hàm `handleBillingSetupFinished(callbackGen, billingResult)`:
  - Kiểm tra `callbackGen == generation` và trạng thái khác `CLOSED`. Nếu không khớp, ghi log cảnh báo và bỏ qua (chống stale callback).
  - Phân loại mã phản hồi Google Play: nếu `OK`, chuyển `CONNECTED`, reset số lần retry; nếu lỗi, chuyển `DISCONNECTED`.
  - Rút cạn hàng đợi `pendingWaiters` và kích hoạt toàn bộ waiter với kết quả tương ứng.
- Hàm `close()`: Đánh dấu `CLOSED`, tăng `generation`, rút cạn và báo `false` cho toàn bộ waiter.

### 2.2. Tích hợp trong `BillingManager.kt`
- Khởi tạo `connectionCoordinator` bằng `by lazy`, truyền vào `clientProvider = { billingClient }`, hành động `onConnected` (truy vấn sản phẩm và đồng bộ purchases), và `onDisconnected` (thử lại với backoff).
- `connectionState` là thuộc tính ủy quyền trực tiếp từ `connectionCoordinator.connectionState`.
- `destroy()` gọi `connectionCoordinator.close()`, `scope.cancel()`, và `billingClient.endConnection()`.
- `resetInstanceForTesting()` gọi `instance?.destroy()` trước khi null hóa.

---

## 3. Kết quả kiểm thử & Bằng chứng

### 3.1. Unit Tests (`BillingConnectionCoordinatorTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingConnectionCoordinatorTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **9/9 PASS** (0 failed, 0 skipped).
Chi tiết 9 bài kiểm thử:
1. `testStartConnection_whenDisconnected_transitionsToConnecting`: PASS (Chuyển trạng thái sang CONNECTING và gọi client).
2. `testConcurrentWaiters_coalescedAndAllNotifiedOnSuccess`: PASS (Hai waiter trong lúc CONNECTING được gom lại và đều nhận true khi thành công).
3. `testRestoreDuringConnecting_receivesFailureOnSetupError`: PASS (Waiter trong lúc CONNECTING nhận false khi setup gặp lỗi, không bị treo).
4. `testAlreadyConnected_immediatelyInvokesWaiterWithTrue`: PASS (Gọi khi đã CONNECTED lập tức trả về true, không gọi lại client).
5. `testStaleCallbackGuard_ignoresCallbackFromPreviousGeneration`: PASS (Callback từ generation cũ bị bỏ qua, không làm sai lệch generation mới).
6. `testServiceDisconnected_drainsWaitersWithFalse_andSetsDisconnected`: PASS (Mất kết nối giữa chừng giải phóng waiter với false và chuyển DISCONNECTED).
7. `testRepeatedSetupFailures_allowSubsequentAttempts_withoutReinvokingOldWaiters`: PASS (Setup lỗi lặp lại dọn dẹp sạch sẽ, không gọi đúp waiter cũ).
8. `testTeardownClose_cancelsWaiters_andPreventsNewConnections`: PASS (Scope teardown hủy waiter và từ chối mở kết nối mới).
9. `testRetryConnection_finiteAttemptsEnforced`: PASS (Thử lại kết nối bị giới hạn hữu hạn bởi maxReconnectAttempts).

### 3.2. Chuyển xanh Probe lỗi F07 (`BillingReauditRegressionTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingReauditRegressionTest.probeRestoreDuringConnectingMustComplete'
```
Kết quả: **BUILD SUCCESSFUL**, probe `probeRestoreDuringConnectingMustComplete` chính thức **PASSED**!

### 3.3. Bộ test tổng hợp toàn bộ các module Billing
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.BillingConnectionCoordinatorTest" --tests "com.tscanner.app.BillingReconciliationTest" --tests "com.tscanner.app.BillingPurchaseVerificationTest" --tests "com.tscanner.app.BillingEntitlementStoreTest" --tests "com.tscanner.app.BillingEntitlementContractTest" --tests "com.tscanner.app.BillingManagerTest" --tests "com.tscanner.app.VipPurchaseActionCoordinatorTest" --tests "com.tscanner.app.AppAuthCanonicalIdentityTest"
```
Kết quả: **BUILD SUCCESSFUL**, **76/76 tests PASS** (0 failed, 0 skipped).

### 3.4. Đóng gói ứng dụng (`assembleDebug`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:assembleDebug
```
Kết quả: **BUILD SUCCESSFUL** in 4s.

---

## 4. Bằng chứng lưu trữ
- Thư mục bằng chứng: `build/vip-billing-b08/`
  - `TEST-com.tscanner.app.BillingConnectionCoordinatorTest.xml`
  - `TEST-com.tscanner.app.BillingReauditRegressionTest.xml`

---

## 5. Trạng thái các Probe ban đầu và chuyển giao gói tiếp theo

- Bảng tổng kết 9 probe từ B00:
  1. `probeMissingActivityOrBillingUnavailableMustNotGrantTrialVip` (F01) -> **PASS** (gói B01)
  2. `probeRapidDoubleClickMustNotDuplicatePurchase` (F01) -> **PASS** (gói B01)
  3. `probeDuplicateTokenMustNotExtendExpiry` (F02) -> **PASS** (gói B05/B06)
  4. `probeRepeatedBindingMustNotExtendExpiry` (F02) -> **PASS** (gói B05/B06)
  5. `probeEmptyAuthoritativeSyncMustRevokeBillingVip` (F03) -> **PASS** (gói B07)
  6. `probeAckFailureMustNotReportRestoreSuccess` (F06) -> **PASS** (gói B07)
  7. `probeQueryErrorMustPreserveLastKnownBillingState` (F06) -> **PASS** (gói B07)
  8. `probeRestoreDuringConnectingMustComplete` (F07) -> **PASS** (gói B08)
  9. `probeSilentSyncMustNotEmitPurchaseSuccessEvent` (F08) -> **FAIL** (Probe duy nhất còn lại, mục tiêu gói B09)

Handoff sang **B09 — Tách state sync và sự kiện UI mua/restore (F08 + F01 async)**:
- Tách biệt rõ ràng giữa việc đồng bộ nền (silent reconciliation / state sync) và sự kiện tương tác người dùng (user-initiated purchase / restore).
- Đảm bảo silent sync không bao giờ phát ra sự kiện purchase completion gây hiển thị toast, dismiss dialog, hoặc yêu cầu Google Drive trái ý muốn.
