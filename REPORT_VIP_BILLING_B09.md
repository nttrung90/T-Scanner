# Báo cáo kết quả gói B09 — Tách state sync và sự kiện UI mua/restore (F08 + F01 async)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B09

- **Khắc phục lỗi F08 (Silent background reconciliation phát ra sự kiện mua hàng UI tương tác):**
  - Trước đây: Mỗi khi `syncPurchasesOnStart()`, `syncPurchases()` hoặc reconciliation nền chạy, hàm `processPurchase` đều tự động gọi `notifyCallbacks(true, "Thanh toán thành công! Gói VIP đã được kích hoạt.", purchase)`.
  - Hậu quả: Nếu người dùng đang mở `VipUpgradeDialog` khi ứng dụng thực hiện silent sync nền (hoặc khi vừa mở app), dialog sẽ tự động hiện thông báo Toast mua thành công, tự đóng dialog và tự kích hoạt `onPostUpgradeFlow()` (xin quyền Google Drive) mặc dù người dùng chưa từng nhấn mua hàng!
- **Khắc phục lỗi F01 async & Double Completion:**
  - Khôi phục giao dịch (`restorePurchases`): trước đây vừa gọi `purchaseCallback` thông qua `processPurchase`, vừa gọi callback `onComplete` của `restorePurchases`, dẫn đến hiện 2 Toast và gọi 2 lần `onPostUpgradeFlow()`. Khi có nhiều purchase trong cùng một lần restore, sự kiện bị nhân bản nhiều lần.
  - Callback sau khi đăng xuất (late callback after logout): khi người dùng mua hàng nhưng đăng xuất trước khi callback Play Store trả về, client trước đây vẫn hiện Toast kích hoạt thành công cho phiên đăng xuất hoặc tài khoản khác.
  - Phân luồng UI thread: các callback từ coroutine IO của BillingClient trước đây không đảm bảo chạy trên MainLooper, có nguy cơ gây crash UI/Toast trên Android thật.
- **Giải pháp thực hiện trong gói B09:**
  - Định nghĩa enum `BillingOperationOrigin` (`PURCHASE`, `RESTORE`, `RECONCILE`) để phân biệt nguồn gốc thao tác.
  - Cập nhật `BillingManager.kt`:
    - Chỉ phát sự kiện tương tác `PurchaseCallback` khi `origin == BillingOperationOrigin.PURCHASE`.
    - Khi `origin == RESTORE` hoặc `RECONCILE`: cập nhật đầy đủ quyền sở hữu vào `BillingEntitlementStore` và `AppAuthManager` nhưng tuyệt đối **không** phát sự kiện UI mua hàng tương tác.
    - Cơ chế coalescing theo phiên mua hàng (`isPurchaseFlowActive` và `purchaseSuccessNotifiedForFlow`): khi Google Play trả về danh sách nhiều purchase trong một batch, chỉ phát duy nhất một sự kiện thành công cho người dùng.
    - Bảo vệ callback sau đăng xuất: kiểm tra `currentOwner == targetOwnerId`. Nếu người dùng đã đăng xuất hoặc đổi tài khoản, bảo lưu quyền cho chủ sở hữu trong store nhưng bỏ qua sự kiện UI tương tác.
    - Đảm bảo `notifyCallbacks` luôn được dispatch lên `Looper.getMainLooper()`.
  - Cập nhật `VipUpgradeDialog.kt`:
    - Thêm guard kiểm tra `isShowing` và trạng thái `Activity` (`!isFinishing && !isDestroyed`) trước khi xử lý callback.
    - Vô hiệu hóa nút `btnRestorePurchases` trong lúc khôi phục và dispatch kết quả lên UI thread an toàn, đảm bảo `onPostUpgradeFlow()` chỉ được gọi tối đa 1 lần.
  - Cập nhật `MoreFragment.kt`:
    - Kiểm tra `isAdded && !isDetached && view != null` và chạy trên `runOnUiThread`.
- **Tập tin đã sửa/tạo (Strict Whitelist):**
  - `app/src/main/java/com/tscanner/app/utils/billing/BillingOperationOrigin.kt` (Tạo mới)
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (Cập nhật event API, phân tách origin, UI thread dispatch, late callback guard)
  - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt` (Guard lifecycle, debounce restore, điều phối onPostUpgradeFlow duy nhất)
  - `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` (Guard lifecycle subscriber)
  - `app/src/test/java/com/tscanner/app/BillingOperationEventsTest.kt` (Tạo mới 6 bài kiểm thử bao phủ toàn bộ kịch bản hồi quy)

---

## 2. Chi tiết thực hiện

### 2.1. Phân loại nguồn gốc `BillingOperationOrigin.kt`
- `PURCHASE`: Luồng mua tương tác do người dùng kích hoạt từ `launchBillingFlow`. Chỉ luồng này mới phát `PurchaseCallback` (UI toast/dismiss dialog).
- `RESTORE`: Luồng khôi phục tương tác do người dùng nhấn `btnRestorePurchases`. Chỉ báo kết quả về callback riêng của `restorePurchases`.
- `RECONCILE`: Đồng bộ nền khi mở app hoặc foreground. Cập nhật entitlement âm thầm, không phát bất kỳ sự kiện UI nào.

### 2.2. Kiểm soát sự kiện trong `BillingManager.kt`
- `processPurchase(purchase, origin = PURCHASE, onComplete)`:
  - Gọi `tryNotifyPurchaseSuccess` và `tryNotifyPurchaseFailure`.
  - `tryNotifyPurchaseSuccess`:
    1. Kiểm tra `if (origin != BillingOperationOrigin.PURCHASE) return`.
    2. Kiểm tra `currentOwner == targetOwnerId`. Nếu người dùng đã logout/đổi user, log cảnh báo và drop UI event.
    3. Kiểm tra `purchaseSuccessNotifiedForFlow.compareAndSet(false, true)`: gom nhóm nhiều purchase trong cùng một batch mua thành một sự kiện thành công duy nhất.
- `performReconciliation`: truyền `origin = BillingOperationOrigin.RESTORE`.
- `syncActivePurchasesInternal`: truyền `origin = BillingOperationOrigin.RECONCILE`.
- `notifyCallbacks`: bọc trong `Runnable` và gửi qua `Handler(Looper.getMainLooper()).post` nếu không ở main thread.

### 2.3. Subscriber UI an toàn trong `VipUpgradeDialog` và `MoreFragment`
- `VipUpgradeDialog.kt`:
  - `purchaseCallback`: kiểm tra `!isShowing || act == null || act.isFinishing || act.isDestroyed` trước khi thao tác UI.
  - `btnRestorePurchases`: đặt `isEnabled = false` khi nhấn, chỉ mở lại khi restore kết thúc, dispatch kết quả trên main thread và chỉ gọi `onPostUpgradeFlow()` một lần duy nhất khi khôi phục thành công.
- `MoreFragment.kt`:
  - `billingPurchaseCallback`: kiểm tra `isAdded && !isDetached && view != null` và gọi `activity?.runOnUiThread`.

---

## 3. Kết quả kiểm thử & Bằng chứng

### 3.1. Unit Tests (`BillingOperationEventsTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingOperationEventsTest'
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **6/6 PASS** (0 failed, 0 skipped, 0 errors).
Chi tiết 6 bài kiểm thử:
1. `testSilentSync_doesNotEmitPurchaseSuccessCallback`: PASS (Đồng bộ nền cập nhật VIP vào store nhưng không kích hoạt PurchaseCallback).
2. `testRestoreMultiplePurchases_emitsExactlyOneRestoreCompletion_andNoPurchaseCallback`: PASS (Khôi phục nhiều purchase chỉ gọi completion đúng 1 lần và không gọi PurchaseCallback).
3. `testInteractivePurchase_emitsPurchaseCallback`: PASS (Mua hàng tương tác gọi PurchaseCallback thành công).
4. `testLogoutBeforePurchaseCallback_dropsInteractiveUiEvent`: PASS (Đăng xuất trước khi callback về: entitlement lưu đúng vào tài khoản mua, UI event bị drop).
5. `testSingleClickPurchase_withMultiplePurchasesInBatch_emitsSingleSuccessEvent`: PASS (Batch nhiều purchase trong một lần mua chỉ phát ra duy nhất 1 sự kiện thành công).
6. `testUserCanceledPurchase_notifiesInteractiveCallbackWithFailure`: PASS (Hủy mua hàng gửi callback thất bại tương ứng).

### 3.2. Chuyển xanh Probe cuối cùng F08 (`BillingReauditRegressionTest.kt`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.BillingReauditRegressionTest'
```
Kết quả: **BUILD SUCCESSFUL**, toàn bộ **14/14 tests PASS**!
- `probeSilentSyncMustNotEmitPurchaseSuccessEvent`: **PASSED**!

### 3.3. Bảng tổng kết 9 Probe từ B00 đến nay
| Probe ID | Tên bài kiểm thử | Defect | Trạng thái ban đầu | Trạng thái hiện tại | Gói hoàn thành |
| :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | `probeMissingActivityOrBillingUnavailableMustNotGrantTrialVip` | F01 | FAIL | **PASS** | B01 |
| 2 | `probeRapidDoubleClickMustNotDuplicatePurchase` | F01 | FAIL | **PASS** | B01 |
| 3 | `probeDuplicateTokenMustNotExtendExpiry` | F02 | FAIL | **PASS** | B05 / B06 |
| 4 | `probeRepeatedBindingMustNotExtendExpiry` | F02 | FAIL | **PASS** | B05 / B06 |
| 5 | `probeEmptyAuthoritativeSyncMustRevokeBillingVip` | F03 | FAIL | **PASS** | B07 |
| 6 | `probeAckFailureMustNotReportRestoreSuccess` | F06 | FAIL | **PASS** | B07 |
| 7 | `probeQueryErrorMustPreserveLastKnownBillingState` | F06 | FAIL | **PASS** | B07 |
| 8 | `probeRestoreDuringConnectingMustComplete` | F07 | FAIL | **PASS** | B08 |
| 9 | `probeSilentSyncMustNotEmitPurchaseSuccessEvent` | F08 | FAIL | **PASS** | B09 |

**Toàn bộ 9/9 probe lỗi kiến trúc ban đầu đã chính thức chuyển sang màu xanh (PASS)!**

### 3.4. Bộ test tổng hợp toàn bộ 10 Test Suites của hệ thống Billing
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.BillingOperationEventsTest" --tests "com.tscanner.app.BillingConnectionCoordinatorTest" --tests "com.tscanner.app.BillingReconciliationTest" --tests "com.tscanner.app.BillingPurchaseVerificationTest" --tests "com.tscanner.app.BillingEntitlementStoreTest" --tests "com.tscanner.app.BillingEntitlementContractTest" --tests "com.tscanner.app.BillingManagerTest" --tests "com.tscanner.app.BillingReauditRegressionTest" --tests "com.tscanner.app.VipPurchaseActionCoordinatorTest" --tests "com.tscanner.app.AppAuthCanonicalIdentityTest"
```
Kết quả: **BUILD SUCCESSFUL**, tổng cộng **96/96 tests PASS** (0 failed, 0 errors, 0 skipped).

### 3.5. Đóng gói ứng dụng (`assembleDebug`)
Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:assembleDebug
```
Kết quả: **BUILD SUCCESSFUL** in 4s.

---

## 4. Bằng chứng lưu trữ
- Thư mục bằng chứng: `build/vip-billing-b09/`
  - `TEST-com.tscanner.app.BillingOperationEventsTest.xml`
  - `TEST-com.tscanner.app.BillingReauditRegressionTest.xml`
  - Toàn bộ XML kết quả của 10 test suites.

---

## 5. Chuyển giao gói tiếp theo

Handoff sang **B10 — Hook foreground và đăng nhập thật (F09)**:
- Tích hợp hook đồng bộ purchases sau khi người dùng đăng nhập thành công (`onLoginCommit`) và khi ứng dụng vào foreground (`DefaultLifecycleObserver`).
- Đảm bảo loại bỏ việc gọi sync trùng lặp ở `MoreFragment`.
- Coalesce foreground queries để không truy vấn liên tục khi chuyển đổi giữa các Activity.
