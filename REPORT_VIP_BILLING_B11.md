# Báo cáo kết quả gói B11 — Giá, offer và ngôn ngữ mua hàng (F10)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B11

- **Khắc phục lỗi F10 (Giá và nhãn mua chưa theo trạng thái sản phẩm thực tế):**
  - Trước đây: `VipUpgradeDialog` đọc giá từ cache đúng một lần tại thời điểm khởi tạo; nếu dữ liệu Google Play trả về sau khi dialog đã mở (truy vấn bất đồng bộ), dialog vẫn giữ nguyên giá tĩnh hoặc nhãn ban đầu mà không cập nhật.
  - Nhãn nút mua trước đây luôn hứa hẹn "Dùng thử VIP" (`activate_vip_trial_email_format`), ngay cả khi gói đăng ký mua thực tế là gói trả phí trực tiếp không có ưu đãi dùng thử miễn phí.
  - Phụ đề giá trong layout (`dialog_vip_upgrade.xml`) cố định giá năm và giá quy đổi tháng giả định (`20.000 đ/năm`, `~1.600 đ/tháng`), hoàn toàn sai lệch khi người dùng ở quốc gia khác sử dụng các loại tiền tệ khác (USD, EUR, JPY, v.v.) hoặc khi cấu hình giá trên Google Play Console thay đổi.
  - Khi sản phẩm chưa tải xong hoặc không có sẵn, UI trước đây vẫn hiển thị giá fallback tĩnh như giá giao dịch thật.
- **Giải pháp thực hiện trong gói B11:**
  - **Theo dõi vòng đời sản phẩm (`BillingManager.kt` & `VipUpgradeDialog.kt`):**
    - Cài đặt cơ chế lắng nghe có đăng ký/hủy (`addProductsObserver` / `removeProductsObserver`) gắn liền với vòng đời cửa sổ của Dialog (`onAttachedToWindow` / `onDetachedFromWindow`), đảm bảo khi Google Play trả về sản phẩm muộn, giao diện lập tức cập nhật giá mà không bị rò rỉ bộ nhớ.
    - Định nghĩa mô hình trạng thái trình bày `ProductPresentationState`:
      - `Loading`: Đang truy vấn từ Google Play (`tv_vip_price` hiện "Đang tải giá...", ẩn phụ đề, vô hiệu hóa nút mua).
      - `Unavailable`: Không tìm thấy sản phẩm hoặc sản phẩm không có base plan/offer hợp lệ (hiện "Chưa sẵn sàng trên Google Play", vô hiệu hóa nút mua).
      - `Available`: Trích xuất thông tin thực tế từ `ProductDetails` (giá, chu kỳ, đơn vị tiền tệ, ưu đãi dùng thử, giá tái tục).
  - **Thuật toán lựa chọn Offer chuẩn xác (`selectBestOffer`):**
    - Phân tích danh sách `subscriptionOfferDetails`:
      1. Ưu tiên offer có giai đoạn dùng thử miễn phí (`priceAmountMicros == 0L`).
      2. Nếu không có dùng thử, chọn base plan offer (`offerId.isNullOrEmpty()`).
      3. Hoặc chọn offer khả dụng đầu tiên.
    - Khớp mã `offerToken` giữa UI và luồng khởi chạy thanh toán: `launchBillingFlow` sử dụng chính xác cùng thuật toán `selectBestOffer` hoặc nhận trực tiếp `offerToken` từ trạng thái UI, loại bỏ hoàn toàn khả năng người dùng thấy một offer nhưng Google Play lại thanh toán offer khác.
  - **Loại bỏ giá giả và nhãn không phù hợp:**
    - Loại bỏ giá giả (`20.000 đ/năm`) trong enum `VipTier.kt`.
    - Loại bỏ text tĩnh quy đổi tháng cố định trong `dialog_vip_upgrade.xml`, thêm `id` `tv_vip_price_sub` để hiển thị động:
      - Nếu có dùng thử: hiển thị thời gian dùng thử kèm giá và chu kỳ tái tục (ví dụ: "Dùng thử 7 ngày, sau đó 199.000 ₫ / năm • Hủy bất cứ lúc nào").
      - Nếu không có dùng thử: hiển thị điều khoản tái tục ("Hủy bất cứ lúc nào trên Google Play").
    - Nút hành động:
      - Có dùng thử: "Bắt đầu Dùng thử Miễn phí (email)" / "Đăng nhập để nhận Dùng thử Miễn phí".
      - Không có dùng thử: "Nâng cấp VIP (email)" / "Đăng nhập để nâng cấp VIP".
  - **Bản địa hóa 8 ngôn ngữ:**
    - Bổ sung 12 chuỗi tài nguyên tương ứng cho cả 8 locale được ứng dụng hỗ trợ:
      `values` (tiếng Anh mặc định), `values-vi` (tiếng Việt), `values-de` (tiếng Đức), `values-es` (tiếng Tây Ban Nha), `values-fr` (tiếng Pháp), `values-in` (tiếng Indonesia), `values-ja` (tiếng Nhật), `values-pt` (tiếng Bồ Đào Nha).
  - **Tập tin đã sửa/tạo (Strict Whitelist):**
    - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (Product/offer presentation, selection, formatters, observers)
    - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt` (Observer lifecycle, dynamic price/sub/button presentation)
    - `app/src/main/res/layout/dialog_vip_upgrade.xml` (Cập nhật `tv_vip_price` loading, thêm `tv_vip_price_sub` dynamic)
    - `app/src/main/java/com/tscanner/app/data/model/VipTier.kt` (Loại bỏ giá giả trong enum)
    - 8 file `strings.xml`: `res/values/strings.xml`, `res/values-vi/strings.xml`, `res/values-de/strings.xml`, `res/values-es/strings.xml`, `res/values-fr/strings.xml`, `res/values-in/strings.xml`, `res/values-ja/strings.xml`, `res/values-pt/strings.xml`
    - `app/src/test/java/com/tscanner/app/BillingOfferPresentationTest.kt` (Tạo mới 10 bài kiểm thử)

---

## 2. Chi tiết thực hiện

### 2.1. Quản lý trạng thái và lựa chọn Offer (`BillingManager.kt`)
1. **`ProductPresentationState`**:
   - Tách biệt rõ ràng 3 trạng thái của sản phẩm: `Loading`, `Unavailable`, `Available`.
   - Ngăn chặn tuyệt đối việc đưa giá fallback giả định lên UI khi dữ liệu chưa sẵn sàng hoặc gặp lỗi.
2. **`selectBestOffer(details)`**:
   - Kiểm tra `pricingPhaseList`: nếu có phase có `priceAmountMicros == 0L`, đánh dấu `hasFreeTrial = true` và chọn offer dùng thử này.
   - Nếu tất cả các phase đều có phí, chọn base plan và đánh dấu `hasFreeTrial = false`.
3. **Đồng bộ với `launchBillingFlow`**:
   - `launchBillingFlow(activity, productId, offerToken, onError)` hỗ trợ truyền token cụ thể, hoặc tự động dùng `selectBestOffer(details)?.offerToken`.
   - Đảm bảo token hiển thị trên UI chính là token gửi sang Google Play Billing.

### 2.2. Trình bày UI linh hoạt (`VipUpgradeDialog.kt` & `dialog_vip_upgrade.xml`)
- `tv_vip_price`: mặc định ở trạng thái loading (`@string/vip_price_loading`).
- `tv_vip_price_sub`: ẩn khi loading/unavailable, chỉ hiện khi đã lấy được offer hợp lệ từ Play Store.
- Gắn `productsObserver` tại `onAttachedToWindow` và gỡ tại `onDetachedFromWindow`.

---

## 3. Kết quả kiểm thử và nghiệm thu

### 3.1. Các kịch bản kiểm thử trong `BillingOfferPresentationTest.kt`
1. `testProductState_cacheEmpty_returnsLoading_thenTransitionsToAvailable`: Cache trống trả về `Loading` (không hiện giá giả), sau khi sản phẩm về chuyển sang `Available`.
2. `testProductState_productNotFoundInCatalog_returnsUnavailable`: Sản phẩm không có trong catalog trả về `Unavailable`, `getFormattedPrice` trả về `null`.
3. `testProductState_subscriptionWithNoOffers_returnsUnavailable`: Gói subscription không có offer/base plan khả dụng trả về `Unavailable`.
4. `testProductState_freeTrialOffer_displaysTrialAndRecurringPrice`: Offer có dùng thử hiển thị chính xác giá tái tục, thời gian dùng thử (7 ngày), và nhãn nút cam kết dùng thử.
5. `testProductState_regularSubscriptionWithoutTrial_doesNotPromiseTrial`: Gói thanh toán trực tiếp không hứa hẹn dùng thử, nhãn chuyển thành "Nâng cấp VIP".
6. `testProductState_nonVndCurrency_formatsWithoutVndHardcoded`: Tiền tệ ngoại tệ (USD `$9.99`) hiển thị đúng định dạng mà không bị dính ký hiệu VND/₫ cố định.
7. `testSelectBestOffer_multipleOffers_prefersTrialOffer_andMatchesLaunchToken`: Khi có nhiều offer, ưu tiên chọn offer dùng thử và offer token khớp với token khởi chạy.
8. `testSelectBestOffer_multipleOffersWithoutTrial_selectsBasePlan`: Khi có nhiều offer không dùng thử, chọn đúng base plan offer.
9. `testVipTier_removedFakePrices`: Xác minh enum `VipTier` đã loại bỏ hoàn toàn giá giả cũ.
10. `testBillingPeriodFormatting_isoPeriods`: Kiểm tra chuyển đổi các chu kỳ ISO 8601 (`P1Y`, `P1M`, `P7D`, `P14D`).

### 3.2. Kết quả chạy kiểm thử toàn bộ hệ thống
Chạy toàn bộ 12 test suite của hệ thống Billing (`112/112 PASS`, `0 Failures`, `0 Errors`):

| Test Suite | Số test | Kết quả |
| :--- | :---: | :---: |
| `AppAuthCanonicalIdentityTest` | 7 | **PASS** |
| `BillingConnectionCoordinatorTest` | 9 | **PASS** |
| `BillingEntitlementContractTest` | 13 | **PASS** |
| `BillingEntitlementStoreTest` | 9 | **PASS** |
| `BillingLifecycleIntegrationTest` | 6 | **PASS** |
| `BillingManagerTest` | 14 | **PASS** |
| `BillingOfferPresentationTest` | 10 | **PASS** |
| `BillingOperationEventsTest` | 6 | **PASS** |
| `BillingPurchaseVerificationTest` | 7 | **PASS** |
| `BillingReauditRegressionTest` | 14 | **PASS** |
| `BillingReconciliationTest` | 6 | **PASS** |
| `VipPurchaseActionCoordinatorTest` | 11 | **PASS** |
| **Tổng cộng** | **112** | **112/112 PASS (100%)** |

### 3.3. Kết quả biên dịch APK Debug
- Lệnh: `.\gradlew.bat :app:assembleDebug`
- Trạng thái: **BUILD SUCCESSFUL** (39 actionable tasks: 3 executed, 36 up-to-date).

---

## 4. Bằng chứng lưu trữ
Toàn bộ log và file kết quả kiểm thử XML của gói B11 được lưu tại thư mục:
`build/vip-billing-b11/test-results/`
