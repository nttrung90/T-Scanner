# Báo Cáo Nghiệm Thu Gói B02 — Nâng Billing Library Còn Được Hỗ Trợ (F11)

**Thời gian:** 26/09/2026  
**Dự án:** T-Scanner (Android, `:app`)  
**Mục tiêu gói B02:** Nâng cấp Google Play Billing Library từ phiên bản sắp lỗi thời (7.1.1) lên phiên bản mới nhất đang được Google hỗ trợ (8.0.0), xử lý toàn diện các thay đổi phá vỡ (breaking changes) về API ProductDetails/unfetched products, auto-reconnect, prepaid plans, và bảo đảm biên dịch artifact thực tế (`assembleDebug`).

---

## 1. Danh Sách File Sửa Đổi (Tuân thủ nghiêm ngặt Whitelist B02)

| File | Loại thay đổi | Rationale kỹ thuật |
|---|---|---|
| `app/build.gradle` | Sửa đổi | Cập nhật `com.android.billingclient:billing-ktx:8.0.0` (pin cố định, không dùng dynamic range). |
| `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` | Sửa đổi | **Chỉ wrapper và API migration:**<br>1. Thêm import `QueryProductDetailsResult`, `UnfetchedProduct`.<br>2. Cập nhật `BillingClientWrapper.queryProductDetailsAsync` nhận `(BillingResult, QueryProductDetailsResult) -> Unit`.<br>3. `DefaultBillingClientWrapper` kích hoạt `.enableAutoServiceReconnection()` và `.enablePrepaidPlans()` trong `PendingPurchasesParams`.<br>4. `queryAllProducts` đọc `queryResult.productDetailsList` và log `queryResult.unfetchedProductList`. |
| `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt` | Sửa đổi | Cập nhật `FakeBillingClientWrapper` trả về `QueryProductDetailsResult.create(...)`; bổ sung JVM factories `createTestProductDetails` (với cấu trúc mảng JSON `pricingPhases` chuẩn Play Store) và `createTestUnfetchedProduct`. |
| `app/src/test/java/com/tscanner/app/BillingManagerTest.kt` | Sửa đổi | Bổ sung 4 unit test mới kiểm tra `queryAllProducts` trên API Billing 8: thành công kèm định dạng giá, xử lý kết quả cục bộ có sản phẩm unfetched, an toàn dữ liệu khi có lỗi trả về, và an toàn khi BillingClient chưa sẵn sàng. |
| `REPORT_VIP_BILLING_B02.md` | Tạo mới | Báo cáo nghiệm thu và bàn giao gói B02. |

---

## 2. Các Thay Đổi API Phá Vỡ Của Play Billing 8.0.0 Đã Được Xử Lý

1. **`queryProductDetailsAsync` Callback Signature:**
   - Trong Play Billing 7: Callback nhận `(BillingResult, List<ProductDetails>)`.
   - Trong Play Billing 8: Callback nhận `(BillingResult, QueryProductDetailsResult)`.
   - Đối tượng `QueryProductDetailsResult` phân tách rõ:
     - `productDetailsList: List<ProductDetails>`: Các sản phẩm query thành công và có sẵn ưu đãi.
     - `unfetchedProductList: List<UnfetchedProduct>`: Các sản phẩm không tìm thấy hoặc không đủ điều kiện (kèm mã `statusCode`: `UNKNOWN`, `INVALID_PRODUCT_ID_FORMAT`, `PRODUCT_NOT_FOUND`, `NO_ELIGIBLE_OFFER`).
   - `BillingManager.queryAllProducts` đã được cập nhật để nạp tất cả sản phẩm thành công vào cache StateFlow `_products`, đồng thời ghi nhận cảnh báo chi tiết với từng `unfetchedProduct`.

2. **Yêu Cầu Pending Purchases Cho Prepaid Plans:**
   - `PendingPurchasesParams.Builder` trong Billing 8 yêu cầu/hỗ trợ `.enablePrepaidPlans()`. Đã được bổ sung vào `DefaultBillingClientWrapper`.

3. **Cơ Chế Auto Service Reconnection Của Thư Viện:**
   - `BillingClient.Builder` trong Billing 8 cung cấp `.enableAutoServiceReconnection()`. Đã kích hoạt trên client production.

4. **Tương Thích Ngầm Phương Thức Nội Bộ (Reflection Seams):**
   - Đã kiểm tra bytecode của `QueryPurchasesParams`: phương thức `zza()` trả về product type (`subs` hoặc `inapp`) vẫn được giữ nguyên trong Billing 8.0.0, giúp `FakeBillingClientWrapper.extractProductType` hoạt động 100% chính xác trên JVM mà không cần Play Services thật.

---

## 3. Bằng Chứng Xác Minh & Nghiệm Thu (Evidence)

Thư mục lưu trữ bằng chứng: `build/vip-billing-b02/`

### 3.1. Dependency Resolution
Xác nhận Gradle giải quyết chính xác phiên bản `8.0.0`:
```text
+--- com.android.billingclient:billing-ktx:8.0.0
|    +--- com.android.billingclient:billing:8.0.0
```
Lưu tại: `build/vip-billing-b02/billing_dependency_resolution.txt`.

### 3.2. Manifest Metadata Khớp Bản Hỗ Trợ
Kiểm tra file manifest sau sáp nhập (`merged_manifest/debug`):
```xml
<uses-permission android:name="com.android.vending.BILLING" />
<meta-data
    android:name="com.google.android.play.billingclient.version"
    android:value="8.0.0" />
<activity
    android:name="com.android.billingclient.api.ProxyBillingActivity" ... />
<activity
    android:name="com.android.billingclient.api.ProxyBillingActivityV2" ... />
```
Lưu tại: `build/vip-billing-b02/merged_manifest_billing_meta.txt`.

### 3.3. Kiểm Tra Đóng Gói Thực Tế (`:app:assembleDebug`)
- Lệnh: `.\gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL** (39 actionable tasks, 6 executed, 33 up-to-date trong 33s).
- Không phát sinh lỗi dexing, R8/ProGuard hoặc xung đột lớp nhị phân.

### 3.4. Kết Quả Unit Tests & Regression
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.BillingManagerTest" --tests "com.tscanner.app.VipPurchaseActionCoordinatorTest"`
- Kết quả: **BUILD SUCCESSFUL**
  - `BillingManagerTest`: **14/14 PASS** (0 failures, 0 errors)
  - `VipPurchaseActionCoordinatorTest`: **11/11 PASS** (0 failures, 0 errors)
- Báo cáo XML lưu tại:
  - `build/vip-billing-b02/TEST-com.tscanner.app.BillingManagerTest.xml`
  - `build/vip-billing-b02/TEST-com.tscanner.app.VipPurchaseActionCoordinatorTest.xml`

Danh sách 4 bài test mới cho Billing 8:
1. `testQueryAllProducts_success_populatesProductsMapAndFormatsPrice` — PASS
2. `testQueryAllProducts_partialResultWithUnfetchedProducts_stillLoadsAvailableProducts` — PASS
3. `testQueryAllProducts_errorResponse_doesNotCorruptProductsMap` — PASS
4. `testQueryAllProducts_whenBillingNotReady_returnsExistingProductsWithoutCrashing` — PASS

---

## 4. Handoff Sang Gói B03

- **Trạng thái hiện tại:**
  - Ứng dụng đã hoàn toàn chạy trên Play Billing Library 8.0.0.
  - Mã biên dịch sạch sẽ, APK debug đóng gói thành công.
  - Toàn bộ suite test wrapper và coordinator hoạt động ổn định.
- **Tiếp theo theo kế hoạch (B03):**
  - Chốt contract entitlement, ownership và backend (`docs/billing/ENTITLEMENT_CONTRACT.md`, `utils/billing/BillingEntitlement.kt`, `utils/billing/PurchaseVerifier.kt`, `BillingEntitlementContractTest.kt`).
  - Chuẩn bị nền tảng phân biệt pending, active, grace period, và ngăn chặn cấp VIP vô hạn.
