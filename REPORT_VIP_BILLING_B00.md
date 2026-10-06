# BÁO CÁO THỰC THI GÓI B00 — CỐ ĐỊNH BASELINE VÀ CHUYỂN PROBE THÀNH REGRESSION

**Ngày thực hiện:** 26/09/2026  
**Repository:** `E:\DU AN AI\T-Scanner`, module `:app`  
**Kế hoạch tham chiếu:** `PLAN_FIX_VIP_PLAY_BILLING_SMALL_MODEL_2026-09-26.md`  
**Tài liệu đầu vào:** `RECHECK_VIP_PLAY_BILLING_2026-09-26.md`, `build/vip-billing-reaudit/`  

---

## 1. Mục tiêu và phạm vi của Gói B00

- **Mục tiêu:** Cố định bộ test baseline, hoàn thiện harness kiểm thử với fixture tách riêng preferences theo tên và danh mục sản phẩm (SUBS vs INAPP), chuyển 9 probe phát hiện lỗi thành bộ regression kiểm thử vĩnh viễn trong mã nguồn test của dự án, giữ các ca đối chứng chuẩn để đảm bảo fixture không phát sinh false failure.
- **Phạm vi whitelist:**
  - `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt` (Tạo mới)
  - `app/src/test/java/com/tscanner/app/BillingReauditRegressionTest.kt` (Tạo mới)
  - `app/src/test/java/com/tscanner/app/BillingManagerTest.kt` (Cập nhật dùng shared fixture)
  - Thư mục lưu bằng chứng: `build/vip-billing-b00/`
  - Báo cáo: `REPORT_VIP_BILLING_B00.md`
- **Không sửa:** Bất kỳ file nào trong main source (`app/src/main/...`).

---

## 2. Các thành phần đã triển khai

### 1. `BillingTestFixtures.kt` — Harness kiểm thử chuẩn hóa
- **`BillingTestContext`:**
  - Quản lý `ConcurrentHashMap<String, BillingFakeSharedPreferences>` phân tách triệt để các tệp SharedPreferences theo tên (`"tscanner_billing_prefs"`, `"tscanner_auth_prefs"`, `"tscanner_vip_prefs"`, ...).
  - Khắc phục triệt để hiện tượng dùng chung 1 Map duy nhất của fake cũ làm rò rỉ trạng thái giữa các tầng xác thực và thanh toán.
- **`FakeBillingClientWrapper`:**
  - Tách riêng danh sách mua hàng: `subsPurchases` (dành cho gói thuê bao SUBS) và `inAppPurchases` (dành cho gói mua một lần INAPP).
  - Tách mã phản hồi truy vấn: `subsQueryResponse` và `inAppQueryResponse`, hỗ trợ mô phỏng lỗi riêng từng loại (hoặc gộp qua `queryResponse`).
  - Hỗ trợ cơ chế hoãn (deferred callbacks) cho kết nối (`deferSetup`/`completeDeferredSetup`), xác nhận giao dịch (`deferAck`/`completeDeferredAck`), chi tiết sản phẩm (`deferProductDetails`), và truy vấn (`deferQuery`/`executeDeferredQueries`).
  - Phương thức trích xuất an toàn `extractProductType` hỗ trợ nhận diện loại sản phẩm qua reflection cả với phương thức nội bộ `zza()` của Play Billing 7.1.1 lẫn các biến thể tương lai.
- **`createTestPurchase`:**
  - Factory helper khởi tạo đối tượng `Purchase` chuẩn từ chuỗi JSON hợp lệ của Google Play cho môi trường JVM.

### 2. `BillingReauditRegressionTest.kt` — Bộ kiểm thử hồi quy vĩnh viễn
- Chuyển 9 probe sang test hồi quy vĩnh viễn gọi trực tiếp production logic (`BillingManager`, `AppAuthManager`):
  1. `probeDuplicateTokenMustNotExtendExpiry` (Lỗi F02 - P1)
  2. `probeEmptyAuthoritativeSyncMustRevokeBillingVip` (Lỗi F03 - P1)
  3. `probeUnknownProductMustNotGrantVip` (Lỗi F04 - P1)
  4. `probeAckFailureMustNotReportRestoreSuccess` (Lỗi F06 - P1)
  5. `probeRestoreDuringConnectingMustComplete` (Lỗi F07 - P2)
  6. `probeLateAckMustNotGrantToDifferentAccount` (Lỗi F05 - P1)
  7. `probeRepeatedBindingMustNotExtendExpiry` (Lỗi F02 - P1)
  8. `probeQueryErrorMustPreserveLastKnownBillingState` (Lỗi F06 - P1)
  9. `probeSilentSyncMustNotEmitPurchaseSuccessEvent` (Lỗi F08 - P2)
- Giữ 5 ca đối chứng (controls) kiểm tra luồng chuẩn:
  1. `testControl_setupConnectionSuccess_setsConnectedState`
  2. `testControl_newPurchase_unacknowledged_acknowledgesAndGrantsVip`
  3. `testControl_alreadyAcknowledged_grantsVipWithoutDuplicateAck`
  4. `testControl_userCanceled_notifiesCallback`
  5. `testControl_restorePurchases_withActivePurchases_restoresVip`

### 3. Cập nhật `BillingManagerTest.kt`
- Tái cấu trúc sử dụng `BillingTestContext` và `FakeBillingClientWrapper` từ `BillingTestFixtures.kt`, loại bỏ các lớp nội bộ trùng lặp, bảo đảm tính nhất quán trên toàn bộ các bộ test của Billing.

---

## 3. Lệnh thực thi và kết quả kiểm thử

### 1. Bộ kiểm thử đối chứng baseline (`BillingManagerTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingManagerTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Kết quả:** **10 / 10 tests PASS** (0 failures, 0 errors, 0 skipped).

### 2. Bộ kiểm thử hồi quy (`BillingReauditRegressionTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingReauditRegressionTest --offline --console=plain
  ```
- **Exit Code:** `1` (BUILD FAILED theo đúng thiết kế của gói B00 nhằm xác lập baseline đỏ)
- **Tổng số tests:** 14 tests
  - **5 ca đối chứng (Controls):** **5 / 5 PASS** (chứng minh test fixture hoạt động chính xác, không tạo false failure).
  - **9 ca lỗi (Regressions):** **9 / 9 FAIL** (tái hiện chính xác 9 khiếm khuyết trong mã nguồn hiện tại):
    - `probeQueryErrorMustPreserveLastKnownBillingState` $\rightarrow$ `AssertionError` (Lỗi F06)
    - `probeAckFailureMustNotReportRestoreSuccess` $\rightarrow$ `AssertionError` (Lỗi F06)
    - `probeSilentSyncMustNotEmitPurchaseSuccessEvent` $\rightarrow$ `AssertionError` (Lỗi F08)
    - `probeEmptyAuthoritativeSyncMustRevokeBillingVip` $\rightarrow$ `AssertionError` (Lỗi F03)
    - `probeDuplicateTokenMustNotExtendExpiry` $\rightarrow$ `AssertionError` (Lỗi F02)
    - `probeRestoreDuringConnectingMustComplete` $\rightarrow$ `AssertionError` (Lỗi F07)
    - `probeLateAckMustNotGrantToDifferentAccount` $\rightarrow$ `AssertionError` (Lỗi F05)
    - `probeRepeatedBindingMustNotExtendExpiry` $\rightarrow$ `AssertionError` (Lỗi F02)
    - `probeUnknownProductMustNotGrantVip` $\rightarrow$ `AssertionError` (Lỗi F04)

### 3. Lưu trữ bằng chứng
Đã lưu giữ kết quả độc lập tại thư mục `build/vip-billing-b00/`:
- `billing-manager-tests.log`: Log chạy 10 ca kiểm thử đối chứng.
- `billing-reaudit-regression.log`: Log chạy 14 ca kiểm thử hồi quy.
- `TEST-BillingManagerTest.xml`: Báo cáo XML kết quả `BillingManagerTest`.
- `TEST-BillingReauditRegressionTest.xml`: Báo cáo XML kết quả `BillingReauditRegressionTest`.
- `regression-summary.json`: Tóm tắt JSON trạng thái 14 test cases.

---

## 4. Xác nhận nghiệm thu Gói B00

1. **Git Working Tree:** Giữ nguyên toàn bộ mã nguồn ứng dụng hiện tại; không có thay đổi nào trong `app/src/main/`.
2. **Fixture Validation:** Đã tách SharedPreferences theo tên và tách danh sách truy vấn SUBS vs INAPP. Fixture chạy ổn định và 5 ca đối chứng đều xanh.
3. **Reproducibility:** Cả 9 lỗi xác nhận trong báo cáo re-audit ngày 26/09/2026 đều được tái hiện chính xác bằng assertion thất bại trên code thật.
4. **Baseline Confirmation:** 739 test đã pass trước đó không bao phủ 9 ca lỗi này. Gói B00 đã thiết lập thành công rào chắn regression vĩnh viễn.

---

## 5. Đầu vào và bàn giao cho Gói tiếp theo (B01)

- **Gói tiếp theo:** `B01 — Bỏ cấp trial khi thanh toán không thành công (F01)`
- **Phạm vi file dự kiến của B01:**
  - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
  - Mới: `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt` (nếu cần seam điều phối)
  - Mới: `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt`
- **Mục tiêu của B01:** Loại bỏ hoàn toàn đường tự động cấp trial VIP 365/730 ngày khi thanh toán thất bại, ngắt kết nối hoặc cache trống.
