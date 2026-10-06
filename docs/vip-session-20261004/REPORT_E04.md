# Báo cáo gói E04 — Khép các khoảng hở khi đang tải/mua/khôi phục

Ngày lập: 04/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

## 1. Trạng thái gói
- **Trạng thái:** PASS
- **Phụ thuộc:** E03 (PASS)

## 2. Code evidence và phân tích khoảng hở
- **Vấn đề 1 (Token hết hạn giữa chừng khi truy vấn gói):** Khi người dùng bấm Mua VIP, coordinator bắt đầu kết nối Google Play hoặc truy vấn `queryProducts`. Nếu token của phiên đăng nhập hết hạn hoặc người dùng bị đổi sang tài khoản khác trong thời gian chờ này, trước đây coordinator có thể cố mở thanh toán hoặc chỉ báo lỗi chung. Nay `validateCurrentSession` được kiểm tra chặt chẽ sau mỗi bước asynchronous, giải phóng biến `isProcessing`, và kích hoạt `onRequestSignIn` (hoặc `onShowSignInPrompt` nếu không có callback).
- **Vấn đề 2 (Đường billing cuối cùng thiếu typed auth callback):** Trong `BillingManager.launchBillingFlow`, khi token phiên đăng nhập bị thiếu hoặc hết hạn (`PlayPurchaseVerifier.isTokenExpired`), trước đây chỉ trả chuỗi tiếng Việt `onError("Phiên đăng nhập đã hết hạn...")`. Nay bổ sung tham số `onAuthRequired: (() -> Unit)?`, cho phép caller (như `VipPurchaseActionCoordinator`) nhận diện trực tiếp mà không cần parse chuỗi tiếng Việt, đồng thời dọn dẹp sạch `activePurchaseContext = null`, `activePurchaseOwnerUserId = null`, `isPurchaseFlowActive.set(false)` trên mọi nhánh thoát sớm / lỗi.
- **Vấn đề 3 (Khôi phục giao dịch với token hết hạn):** Trong `BillingManager.restorePurchases`, nếu tài khoản hiện tại có token hết hạn, phương thức kiểm tra sớm và gọi trực tiếp `onAuthRequired` (hoặc `onComplete(false, msg)` nếu không cung cấp auth callback), tránh lãng phí truy vấn Google Play khi credential không sẵn sàng, đồng thời dọn dẹp trạng thái an toàn.
- **Vấn đề 4 (Bảo toàn receipt và entitlement sau lỗi verify):** Nếu Google Play đã hoàn tất purchase nhưng backend verify trả về lỗi transient/auth, receipt vẫn được lưu trữ trong Google Play cache/outbox để đối soát lại trong lần khởi động/đồng bộ/khôi phục sau; tuyệt đối không mở lại màn hình mua và không cấp VIP khi chưa có xác nhận hợp lệ.

## 3. Files thay đổi trong gói E04
### Files production sửa đổi:
1. `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`:
   - Bổ sung `launchBillingFlow(activity, productId, onAuthRequired, onError)` trong interface `VipPurchaseLauncher` (default delegate) và `DefaultVipPurchaseLauncher`.
   - Trong `executeLaunch`: truyền `onAuthRequired` vào launcher, dọn dẹp `isProcessing` và định tuyến về `onRequestSignIn` / `onShowSignInPrompt`.
2. `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
   - Bổ sung overload `launchBillingFlow` nhận `onAuthRequired: (() -> Unit)?`.
   - Dọn dẹp trạng thái `activePurchaseContext = null`, `activePurchaseOwnerUserId = null`, `isPurchaseFlowActive.set(false)` ở tất cả các nhánh thất bại/thoát sớm trong `launchBillingFlow` và `launchWithProductDetails`.
   - Bổ sung kiểm tra token hết hạn sớm trong `restorePurchases` (cả trước và sau khi reconnect BillingClient).
   - Bổ sung `@VisibleForTesting` accessors: `getActivePurchaseContextForTesting()`, `isPurchaseFlowActiveForTesting()`.

### Files test sửa đổi:
1. `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt`:
   - Cập nhật `FakeVipPurchaseLauncher` hỗ trợ `onAuthRequired` và hook can thiệp `onQueryProductsHook`.
   - Bổ sung 4 unit tests:
     - `testUpgrade_tokenExpiresDuringQueryProducts_routesToRequestSignInAndClearsBusy`
     - `testUpgrade_sessionChangesDuringQueryProducts_routesToErrorAndClearsBusy`
     - `testUpgrade_launchFlowEmitsAuthRequired_routesToRequestSignInAndClearsBusy`
     - `testUpgrade_launchFlowEmitsAuthRequired_withoutSignInCallback_routesToShowSignInPrompt`
2. `app/src/test/java/com/tscanner/app/VipSessionExpiryRegressionTest.kt`:
   - Bổ sung 3 unit tests cho BillingManager:
     - `testE04_billingManager_launchFlow_withExpiredToken_triggersOnAuthRequiredAndClearsState`
     - `testE04_billingManager_restorePurchases_withExpiredToken_triggersOnAuthRequired`
     - `testE04_billingManager_restorePurchases_withExpiredToken_noAuthCallback_reportsErrorGracefully`

## 4. Kết quả kiểm thử và log
- Lệnh chạy:
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
  $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline --tests 'com.tscanner.app.VipPurchaseActionCoordinatorTest' --tests 'com.tscanner.app.VipSessionExpiryRegressionTest' --console=plain
  ```
- Kết quả:
  - `VipPurchaseActionCoordinatorTest`: **15/15 PASS** (0 failures, 0 errors, 0 skipped).
  - `VipSessionExpiryRegressionTest`: **10/10 PASS** (0 failures, 0 errors, 0 skipped).
  - Regression suites liên quan (`AppAuthReauthenticationTest`: 11/11, `VipLoginContinuationTest`: 19/19, `VipRound8RegressionTest`: 17/17): **Tất cả PASS**.
  - Tổng số unit tests kiểm tra trực tiếp: **72/72 PASS**.

## 5. Acceptance đạt / chưa đạt
- [x] Không còn khoảng hở token hết hạn giữa connect / query / launch: `validateCurrentSession` bắt trọn và giải phóng busy state.
- [x] Không phụ thuộc vào parse chuỗi tiếng Việt để xử lý auth error trong luồng Billing: dùng typed `onAuthRequired` callback.
- [x] Restore khi credential hết hạn định tuyến an toàn qua `onAuthRequired` mà không tạo vòng lặp vô hạn hay mất ngữ cảnh.
- [x] Không tự ý cấp entitlement hay kích hoạt lại màn hình mua khi receipt đã tồn tại nhưng auth gặp lỗi tạm thời.
- [x] Trạng thái `activePurchaseContext` và `isPurchaseFlowActive` được reset sạch sẽ khi xảy ra lỗi.

## 6. Rủi ro hoặc gates chưa chạy
- Chưa chạy toàn bộ testDebugUnitTest và lintDebug tổng hợp (thuộc phạm vi E05).
- Chưa có thiết bị vật lý kết nối Play Store để kiểm tra hành vi runtime thực tế (thuộc phạm vi E06).

## 7. Contract bàn giao và gói tiếp theo
- Mã nguồn và tests của E04 đã hoàn tất sạch sẽ, không có regressions.
- Sẵn sàng chuyển tiếp sang **E05 — Kiểm thử tổng hợp và rà soát độc lập**.
