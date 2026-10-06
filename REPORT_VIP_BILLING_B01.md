# BÁO CÁO THỰC THI GÓI B01 — BỎ CẤP TRIAL KHI THANH TOÁN KHÔNG THÀNH CÔNG (F01)

**Ngày thực hiện:** 26/09/2026  
**Repository:** `E:\DU AN AI\T-Scanner`, module `:app`  
**Kế hoạch tham chiếu:** `PLAN_FIX_VIP_PLAY_BILLING_SMALL_MODEL_2026-09-26.md`  
**Khuyết tật giải quyết:** `F01` (P1) — Lỗi kết nối/thanh toán tự cấp VIP miễn phí; có đường cấp hai lần.  

---

## 1. Mục tiêu và phạm vi của Gói B01

- **Mục tiêu:**
  1. Loại bỏ triệt để đường cấp VIP dùng thử (trial fallback) 365/730 ngày khi thanh toán qua Google Play bị lỗi, ngắt kết nối, thiếu Activity, hoặc cache sản phẩm chưa sẵn sàng.
  2. Xử lý các trạng thái lỗi/mất kết nối/cache trống thành `loading` / `error` / `retry` rõ ràng trên giao diện.
  3. Xóa bỏ hoàn toàn việc gọi hậu xử lý mua hàng (`onPostUpgradeFlow`) khi luồng mua hàng chưa thành công trên Google Play.
  4. Tách biệt hoàn toàn tính năng dùng thử cho môi trường phát triển (dev trial) khỏi nút mua hàng chính và khóa chặt không cho phép kích hoạt trong bản phát hành (release builds).
  5. Đảm bảo giữ nguyên vẹn 100% tính tương thích với các luồng tiếp tục đăng nhập (`Vip*Continuation*`).
- **Phạm vi whitelist đã sửa/tạo mới:**
  - `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt` (Tạo mới — seam điều phối)
  - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt` (Cập nhật — tích hợp coordinator và cô lập dev trial)
  - `app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt` (Tạo mới — bộ test hồi quy F01)
  - Thư mục lưu bằng chứng: `build/vip-billing-b01/`
  - Báo cáo: `REPORT_VIP_BILLING_B01.md`

---

## 2. Chi tiết thay đổi trước và sau khi sửa (Before vs After)

| Thành phần | Trước khi sửa (Lỗi F01) | Sau khi sửa (Gói B01) |
|---|---|---|
| **Nút "Nâng cấp VIP"** (`btnConfirmVipUpgrade`) | Khi `launchBillingFlow` trả về `false`, mất kết nối, hoặc thiếu Activity $\rightarrow$ gọi `activateTrialVip(email)` tự cấp VIP 365 ngày. | Ủy quyền xử lý cho [`VipPurchaseActionCoordinator`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt). Tuyệt đối không bao giờ tự cấp VIP khi thanh toán chưa hoàn tất. |
| **Xử lý lỗi kép (Double trial grant)** | Khi `launchBillingFlow` gọi callback `onError` và trả về `false` $\rightarrow$ gọi `activateTrialVip` 2 lần liên tiếp (cộng dồn 730 ngày). | Cơ chế cờ nguyên tử `AtomicBoolean errorReported` trong Coordinator đảm bảo chỉ phát sinh duy nhất 1 callback `onError` và 0 lần cấp quyền. |
| **Trạng thái mất kết nối / Đang kết nối** | Trả về `false` ngay lập tức $\rightarrow$ tự cấp VIP miễn phí cho người dùng. | Hiển thị thông báo đang kết nối lại (`onLoading`), thử kết nối với Google Play; nếu thất bại thông báo lỗi cho phép thử lại (`canRetry = true`). |
| **Cache sản phẩm trống (Empty cache)** | `BillingManager` trả về `false` đồng bộ trong khi truy vấn ngầm $\rightarrow$ UI cấp trial và đóng dialog trước khi Google Play sheet mở ra. | Coordinator hiển thị loading, chờ kết quả nạp lại thông tin sản phẩm từ Play Store; nếu nạp thành công mới mở luồng mua hàng, nếu thất bại báo lỗi rõ ràng. |
| **Chống nhấn đúp (Double-click debounce)** | Không có rào chắn $\rightarrow$ nhấn nhanh 2 lần có thể gửi nhiều yêu cầu mua trùng lặp. | Sử dụng cờ nguyên tử `AtomicBoolean isProcessing` chặn hoàn toàn các lượt nhấn liên tiếp trong khi một hành động đang được thực thi. |
| **Dùng thử dev (Dev Trial)** | Nằm trực tiếp trong đường thanh toán chính của bản release. | Tách riêng thành phương thức [`activateDevTrialForTesting`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt#L176), kiểm tra nghiêm ngặt cờ `ApplicationInfo.FLAG_DEBUGGABLE`. Trong bản phát hành thương mại (release), phương thức lập tức trả về `false` và từ chối kích hoạt. |

---

## 3. Kết quả kiểm thử và nghiệm thu

### 1. Bộ kiểm thử hồi quy mới ([`VipPurchaseActionCoordinatorTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/VipPurchaseActionCoordinatorTest.kt))
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipPurchaseActionCoordinatorTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Kết quả:** **11 / 11 tests PASS** (100%):
  1. `testUpgrade_whenDisconnected_initiatesReconnectAndDoesNotGrantVip`: Ngắt kết nối $\rightarrow$ thử kết nối lại, báo lỗi thử lại, KHÔNG cấp VIP (`isVipActive == false`).
  2. `testUpgrade_whenConnecting_awaitsConnectionAndLaunchesWhenReady`: Đang kết nối $\rightarrow$ chờ kết nối xong và kích hoạt luồng mua hàng thành công.
  3. `testUpgrade_whenActivityMissing_reportsErrorAndDoesNotGrantVip`: Thiếu Activity $\rightarrow$ báo lỗi màn hình không khả dụng, KHÔNG cấp VIP.
  4. `testUpgrade_whenActivityFinishing_reportsErrorAndDoesNotGrantVip`: Activity đang đóng $\rightarrow$ từ chối an toàn, KHÔNG cấp VIP.
  5. `testUpgrade_whenLaunchErrorAndReturnsFalse_emitsSingleErrorWithoutDuplicateCompletion`: Lỗi launch kép $\rightarrow$ phát đúng 1 thông báo lỗi, KHÔNG cấp VIP.
  6. `testUpgrade_whenProductNotCached_queriesProductsAndDoesNotGrantTrial`: Cache sản phẩm trống $\rightarrow$ nạp lại từ Play, nếu thất bại báo lỗi, KHÔNG rơi vào trial.
  7. `testUpgrade_whenProductNotCached_reloadsSuccessfullyThenLaunches`: Cache trống nạp lại thành công $\rightarrow$ mở luồng mua hàng Google Play bình thường.
  8. `testUpgrade_rapidDoubleClicks_processesOnlyOneAction`: Nhấn nhanh 2 lần $\rightarrow$ lượt nhấn thứ 2 bị hủy bỏ, chỉ xử lý đúng 1 hành động duy nhất.
  9. `testUpgrade_guestWithSignInCallback_requestsSignInWithoutGrantingVip`: Khách có callback đăng nhập $\rightarrow$ điều hướng đăng nhập, KHÔNG cấp VIP.
  10. `testUpgrade_guestWithoutSignInCallback_promptsSignInWithoutGrantingVip`: Khách không có callback $\rightarrow$ hiện thông báo yêu cầu đăng nhập, KHÔNG cấp VIP.
  11. `testDevTrial_blockedInNonDebuggableContext`: Môi trường phát hành (non-debuggable) $\rightarrow$ chặn hoàn toàn việc kích hoạt trial dev.

### 2. Bộ kiểm thử tiếp tục đăng nhập ([`Vip*Continuation*`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/))
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.Vip*Continuation* --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Kết quả:** **12 / 12 tests PASS** (Bao gồm `VipIdCardLoginContinuationTest`, `VipLoginContinuationTest`, `VipViewerLoginContinuationTest`). Xác nhận 100% không làm suy thoái các luồng đăng nhập VIP đã kiểm định ở các vòng trước.

### 3. Bộ kiểm thử quản trị thanh toán ([`BillingManagerTest`](file:///e:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/BillingManagerTest.kt))
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingManagerTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL)
- **Kết quả:** **10 / 10 tests PASS**.

### 4. Bằng chứng lưu trữ
Tất cả các bản ghi log và XML nghiệm thu được lưu tại thư mục [`build/vip-billing-b01/`](file:///e:/DU%20AN%20AI/T-Scanner/build/vip-billing-b01/):
- `coordinator-tests.log`
- `continuation-tests.log`
- `billing-manager-tests.log`
- `TEST-VipPurchaseActionCoordinatorTest.xml`
- `TEST-BillingManagerTest.xml`
- `TEST-com.tscanner.app.VipIdCardLoginContinuationTest.xml`
- `TEST-com.tscanner.app.VipLoginContinuationTest.xml`
- `TEST-com.tscanner.app.VipViewerLoginContinuationTest.xml`
- `b01-summary.json`

---

## 4. Giới hạn & Phần chưa chạy (Gates)

1. **Kiểm thử trên thiết bị thật (Device Dialog Gate):** Cần thiết bị thật để xác nhận trải nghiệm hiển thị dialog và hiệu ứng Toast khi người dùng thao tác. Cổng này được mở đến gói nghiệm thu cuối cùng (B12).
2. **Google Play Console / Thanh toán thực tế:** Cần cấu hình sản phẩm và kích hoạt tài khoản thử nghiệm cấp phép trên Play Console (B12).

---

## 5. Đầu vào và bàn giao cho Gói tiếp theo (B02)

- **Gói tiếp theo:** `B02 — Nâng Billing Library còn được hỗ trợ (F11)`
- **Phạm vi whitelist của B02:**
  - `app/build.gradle` (chỉ nâng phiên bản thư viện Play Billing)
  - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt` (chỉ wrapper và API migration nếu cần)
  - `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt`
  - `app/src/test/java/com/tscanner/app/BillingManagerTest.kt`
- **Mục tiêu của B02:** Nâng Google Play Billing Library lên phiên bản được Google hỗ trợ tại thời điểm hiện tại (tối thiểu bản 8), thực hiện di chuyển API và đảm bảo toàn bộ bộ test compile & pass.
