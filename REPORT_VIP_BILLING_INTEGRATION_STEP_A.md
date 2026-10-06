# BÁO CÁO TRIỂN KHAI BƯỚC A — TÍCH HỢP GOOGLE PLAY BILLING LIBRARY VÀO ỨNG DỤNG

**Ngày thực hiện:** 26/09/2026  
**Nội dung:** Thực hiện trọn vẹn Bước A theo yêu cầu: Bổ sung mã nguồn trong ứng dụng (App Code) để liên kết tính năng VIP với Google Play Console.

---

## 1. Các thành phần đã triển khai

### 1. Thư viện & Quyền hạn (Dependencies & Permissions)
- **Dependency:** Bổ sung `com.android.billingclient:billing-ktx:7.1.1` vào [`app/build.gradle`](file:///e:/DU%20AN%20AI/T-Scanner/app/build.gradle). Đây là phiên bản Google Play Billing Library mới nhất (hỗ trợ đầy đủ Android 14/15, 16 KB memory page size, và API `queryProductDetailsAsync`).
- **Quyền ứng dụng:** Khai báo `<uses-permission android:name="com.android.vending.BILLING" />` trong [`app/src/main/AndroidManifest.xml`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/AndroidManifest.xml).

### 2. Module quản trị thanh toán [`BillingManager.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt)
Được thiết kế theo kiến trúc sạch (Clean Architecture) với các tính năng:
- **Quản lý kết nối an toàn:** Tự động kết nối `BillingClient` khi khởi động; tự động kết nối lại với thuật toán Exponential Backoff khi dịch vụ Google Play bị ngắt kết nối.
- **Truy vấn danh mục sản phẩm:** Hỗ trợ cả 2 loại sản phẩm trên Google Play Console:
  - **Subscriptions (Thuê bao gia hạn định kỳ):**
    - `tscanner_vip_yearly` (Gói năm: 365 ngày)
    - `tscanner_vip_monthly` (Gói tháng: 30 ngày)
    - Hỗ trợ bí danh tương thích: `vip_yearly`, `vip_monthly`.
  - **In-App Products (Mua một lần):**
    - `tscanner_vip_lifetime` (Gói trọn đời: 3650 ngày)
- **Kích hoạt luồng mua hàng (`launchBillingFlow`):** Tự động đóng gói `BillingFlowParams` kèm `offerToken` cho Subscription và mở giao diện mua hàng chuẩn của Google Play từ Activity.
- **Xác thực và xác nhận giao dịch (`acknowledgePurchase`):** Tự động gửi lệnh xác nhận đến Google Play trong vòng 3 ngày theo quy định bắt buộc của Google để tránh bị Google tự động hoàn tiền (auto-refund).
- **Cấp quyền VIP chuẩn xác:** Tích hợp trực tiếp với [`AppAuthManager.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt) qua hàm `setUserVipTier(context, VipTier.VIP, durationDays)`.
- **Hỗ trợ khách (Guest Purchase):** Nếu người dùng mua khi chưa đăng nhập, hóa đơn được lưu cục bộ an toàn (`tscanner_billing_prefs`). Khi người dùng đăng nhập tài khoản Google, hàm `bindPurchasesToCurrentUser` sẽ tự động liên kết quyền VIP vào tài khoản mới.
- **Khôi phục giao dịch (`restorePurchases`):** Cho phép người dùng khôi phục lại gói VIP khi cài lại ứng dụng hoặc chuyển đổi máy khác thông qua tài khoản Google Play.
- **Kiến trúc Seam kiểm thử:** Cung cấp `BillingClientWrapper` và `BillingClientProvider` cho phép chạy kiểm thử đơn vị độc lập 100% trên JVM mà không phụ thuộc vào thiết bị thật.

### 3. Cập nhật giao diện người dùng
- **[`dialog_vip_upgrade.xml`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/res/layout/dialog_vip_upgrade.xml):** Bổ sung nút *"Khôi phục giao dịch mua"* (`btn_restore_purchases`).
- **[`VipUpgradeDialog.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt):**
  - Hiển thị giá thật được định dạng từ Google Play (`formattedPrice`).
  - Khi bấm *"Nâng cấp VIP"*: Tự động gọi `billingManager.launchBillingFlow(...)`. Nếu đang ở môi trường dev/chưa kết nối Play Store, tự động chuyển về chế độ dùng thử nội bộ (*Trial fallback*) để không làm gián đoạn luồng test.
  - Khi bấm *"Khôi phục giao dịch"*: Gọi `billingManager.restorePurchases(...)` và thông báo kết quả qua Toast.
- **[`MoreFragment.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt):** Tự động đồng bộ trạng thái mua hàng (`syncPurchasesOnStart`) trong background và cập nhật ngay lập tức giao diện thẻ VIP khi có giao dịch hoàn tất.
- **[`strings.xml`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/res/values/strings.xml):** Bổ sung đầy đủ chuỗi giao diện cho thông báo mua và khôi phục giao dịch.

---

## 2. Kết quả kiểm thử & Nghiệm thu chất lượng

### 1. Bộ kiểm thử đơn vị mới [`BillingManagerTest.kt`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/BillingManagerTest.kt)
Gồm 10 bài kiểm thử kiểm tra toàn bộ các nhánh logic:
1. `testSetupConnectionSuccess_setsConnectedState`: Kết nối thành công $\rightarrow$ trạng thái `CONNECTED`.
2. `testSetupConnectionFailure_setsDisconnectedState`: Kết nối lỗi $\rightarrow$ trạng thái `DISCONNECTED`.
3. `testProcessPurchase_unacknowledged_acknowledgesAndGrantsVip`: Giao dịch mới chưa xác nhận $\rightarrow$ gửi `acknowledgePurchase` và cấp VIP 365 ngày.
4. `testProcessPurchase_alreadyAcknowledged_grantsVipWithoutDuplicateAck`: Giao dịch đã xác nhận $\rightarrow$ cấp VIP không gửi duplicate ack.
5. `testProcessPurchase_monthlyProduct_grants30Days`: Gói tháng $\rightarrow$ cấp VIP 30 ngày.
6. `testProcessPurchase_lifetimeProduct_grants10Years`: Gói trọn đời $\rightarrow$ cấp VIP 10 năm.
7. `testOnPurchasesUpdated_userCanceled_notifiesCallback`: Người dùng hủy $\rightarrow$ thông báo hủy an toàn.
8. `testRestorePurchases_withActivePurchases_restoresVip`: Khôi phục thành công gói đang hoạt động.
9. `testRestorePurchases_whenNoPurchases_reportsNotFound`: Thông báo đúng khi tài khoản chưa mua VIP.
10. `testGuestPurchase_savedLocally_andBindsToUserOnSignIn`: Khách mua trước $\rightarrow$ tự động gắn vào tài khoản khi đăng nhập Google.

**Kết quả:** **10 / 10 tests PASS** (100%).

### 2. Toàn bộ Unit Test của dự án (`:app:testDebugUnitTest`)
- **Tổng số tests:** **739 tests** (729 tests cũ + 10 tests Billing).
- **Thành công (Passed):** **739 / 739** (100%).
- **Thất bại (Failures):** **0**.
- **Lỗi runtime (Errors):** **0**.
- **Bỏ qua (Skipped):** **0**.

### 3. Phân tích tĩnh Android Lint (`:app:lintDebug`)
- **Errors:** **0 errors**.
- **Warnings:** 736 warnings (chủ yếu là gợi ý tối ưu tài nguyên/chuỗi, không ảnh hưởng runtime).

### 4. Đóng gói bản dựng (`:app:assembleDebug`)
- **Tập tin APK:** [`app/build/outputs/apk/debug/app-debug.apk`](file:///e:/DU%20AN%20AI/T-Scanner/app/build/outputs/apk/debug/app-debug.apk) (35,461,399 bytes).
- Đã đóng gói thành công quyền `com.android.vending.BILLING` và thư viện Google Play Billing 7.1.1.

---

## 4. Đính chính kỹ thuật & Bổ sung sau Re-audit (Gói B00 - B12)

Theo kết quả tái thẩm định toàn diện (`RECHECK_VIP_PLAY_BILLING_2026-09-26.md`) và kế hoạch sửa đổi (`PLAN_FIX_VIP_PLAY_BILLING_SMALL_MODEL_2026-09-26.md`), các nội dung sau trong báo cáo ban đầu cần được đính chính và làm rõ:

1. **Phiên bản Google Play Billing:**
   - Ban đầu ghi Billing Library 7.1.1 là "mới nhất". Thực tế tại thời điểm 26/09/2026, Billing 7.x đã bước vào giai đoạn hết hạn mặc định (31/08/2026, hạn chót gia hạn 01/11/2026). Mã nguồn đã được nâng cấp và kiểm thử tương thích với Google Play Billing 8.0.0.
2. **Xác thực giao dịch (Acknowledge vs Cryptographic Verification):**
   - Lệnh `acknowledgePurchase` của Google Play chỉ xác nhận với Google Play rằng client đã nhận receipt, **hoàn toàn không thay thế** việc xác thực chữ ký mật mã (cryptographic signature verification) hoặc xác thực qua Backend Google Play Developer API.
   - Toàn bộ luồng xác thực đã được tái cấu trúc qua `PlayPurchaseVerifier`, hợp đồng phân quyền độc lập `BillingEntitlementStore`, và định danh tài khoản băm `obfuscatedAccountId` theo chuẩn Google Play Security.
3. **Phạm vi kiểm thử và độ bao phủ (Coverage Limitations):**
   - 10 bài kiểm thử ban đầu trong `BillingManagerTest` chỉ kiểm thử một phần seam cơ bản, chưa kiểm tra các kịch bản bất đồng bộ, rò rỉ quyền đa tài khoản, hoặc lỗi kết nối.
   - Dự án đã xây dựng bộ 12 test suites độc lập với **112 bài kiểm thử tự động**, giải quyết triệt để 11 khiếm khuyết nghiêm trọng (F00 - F10).
4. **Trạng thái sẵn sàng thương mại (Release Gate Status):**
   - Các bài kiểm thử đơn vị trên JVM chỉ chứng minh tính đúng đắn của logic mã nguồn client.
   - Toàn bộ ma trận kiểm thử thiết bị thật (mua thực tế, decline, pending, hủy thẻ, chuyển đổi tài khoản Google Play trên thiết bị, xoay màn hình khi xác thực) và cấu hình sản phẩm trên Play Console hiện ghi nhận trạng thái: **NOT RUN — PENDING PHYSICAL DEVICE & PLAY CONSOLE TESTING**.
   - Ứng dụng **chưa tự động phát hành** cho đến khi hoàn tất kiểm thử vật lý ngoài đời thực.
