# Báo cáo kết quả gói B10 — Điểm khôi phục và đồng bộ vòng đời (F09)

Ngày: 26/09/2026
Dự án: `T-Scanner` (`:app`)

---

## 1. Mục tiêu và phạm vi gói B10

- **Khắc phục lỗi F09 (Điểm khôi phục và đồng bộ vòng đời bị thiếu/phân mảnh):**
  - Trước đây: Không có điểm đồng bộ quyền VIP rõ ràng tại khởi động lạnh ứng dụng (cold-start) sau khi xác thực danh tính. Việc đồng bộ chỉ diễn ra ngẫu nhiên nếu người dùng mở `MoreFragment` (`syncPurchasesOnStart()` trong `onViewCreated`), gây lặp vô ích mỗi khi chuyển tab hoặc xoay màn hình.
  - Khi người dùng đăng nhập tài khoản mới (Google Sign-In), không có hook tự động kích hoạt đồng bộ và gán quyền Play Store của tài khoản đó.
  - Thiếu cơ chế đồng bộ định kỳ khi ứng dụng từ trạng thái background chuyển sang foreground, hoặc bị gọi chồng chéo nếu người dùng chuyển đổi qua lại giữa các Activity.
- **Giải pháp thực hiện trong gói B10:**
  - **Khởi động ứng dụng và vòng đời Foreground (`TScannerApplication.kt`):**
    - Gọi đồng bộ quyền VIP ngay tại cold-start sau khi `AppAuthManager.init(this)` hoàn tất.
    - Cài đặt `ActivityLifecycleCallbacks` để theo dõi chính xác trạng thái foreground của ứng dụng (đếm số activity active từ 0 lên 1).
    - Áp dụng cơ chế throttling 30 giây (`FOREGROUND_SYNC_THROTTLE_MS = 30_000L`) để gom nhóm và ngăn chặn đồng bộ dồn dập khi người dùng nhảy qua lại giữa nhiều Activity trong thời gian ngắn.
  - **Hook sau đăng nhập (`AppAuthManager.kt`):**
    - Thiết lập hàm hook `onLoginCommitted(context, profile)` được gọi tự động sau khi đăng nhập thành công (`handleSignInResult` và `signInWithDemoAccount`).
    - Thực hiện tự động liên kết quyền và đồng bộ purchases cho user mới:
      ```kotlin
      val billingManager = BillingManager.getInstance(context)
      billingManager.bindPurchasesToCurrentUser(context)
      billingManager.syncPurchases()
      ```
    - Bọc an toàn với `catch (e: Throwable)` để ngăn ngừa lỗi môi trường unit test hoặc thiếu Play Services làm gián đoạn luồng đăng nhập.
    - Cung cấp `postLoginHook` dành cho kiểm thử vòng đời tự động.
  - **Tối ưu hóa `MoreFragment.kt`:**
    - Loại bỏ lệnh gọi `syncPurchasesOnStart()` dư thừa trong `onViewCreated`. `MoreFragment` giờ đây chỉ quan sát trạng thái qua LiveData / listener từ `AppAuthManager` và `BillingManager`, loại bỏ hoàn toàn hiện tượng re-sync khi chuyển tab hay xoay màn hình.
  - **Cập nhật `BillingManager.kt`:**
    - Cung cấp phương thức `syncPurchases(onComplete)` chuẩn hóa để các thành phần vòng đời gọi nhất quán, giữ tương thích `syncPurchasesOnStart()`.
  - **Tập tin đã sửa/tạo (Strict Whitelist):**
    - `app/src/main/java/com/tscanner/app/TScannerApplication.kt`
    - `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
    - `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
    - `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
    - `app/src/test/java/com/tscanner/app/BillingLifecycleIntegrationTest.kt` (Tạo mới 6 bài kiểm thử toàn diện)

---

## 2. Chi tiết thực hiện

### 2.1. Quản lý vòng đời tại tầng Application (`TScannerApplication.kt`)
1. **Cold Start Sync:**
   - Trong `onCreate()`, sau khi `AppAuthManager.init(this)` khôi phục profile hiện tại, ứng dụng gọi:
     ```kotlin
     BillingManager.getInstance(this).syncPurchases()
     ```
2. **Foreground Detection & Throttling:**
   - Theo dõi biến đếm `startedActivityCount`: khi tăng từ 0 lên 1 nghĩa là app vừa quay trở lại foreground.
   - So sánh `SystemClock.elapsedRealtime() - lastForegroundSyncTimestamp >= FOREGROUND_SYNC_THROTTLE_MS`.
   - Nếu đủ điều kiện, tiến hành chạy đồng bộ và cập nhật timestamp; nếu chưa đủ 30 giây, bỏ qua để tiết kiệm tài nguyên mạng và tránh thắt cổ chai Play Billing API.

### 2.2. Hook sau đăng nhập (`AppAuthManager.kt`)
- Tích hợp gọi `onLoginCommitted` ngay sau khi phiên đăng nhập được lưu trữ:
  - Khi Google Sign-In thành công -> `saveUserProfile(context, profile)` -> `onLoginCommitted(context, profile)`.
  - Khi demo sign-in thành công -> `saveUserProfile(context, demoUser)` -> `onLoginCommitted(context, demoUser)`.
- Đảm bảo quyền được bind đúng vào canonical user ID mới trước khi truy vấn Play Store.

### 2.3. Tránh lặp đồng bộ tại Fragment (`MoreFragment.kt`)
- Loại bỏ `syncPurchasesOnStart()` tại `onViewCreated()`.
- Giao diện `MoreFragment` chỉ hiển thị badge/nút VIP dựa trên `currentUser.isVipActive` đã được nạp sẵn từ `BillingEntitlementStore`.

---

## 3. Kết quả kiểm thử và nghiệm thu

### 3.1. Các kịch bản kiểm thử trong `BillingLifecycleIntegrationTest.kt`
1. `testColdStart_guestUser_queriesStoreAndRetainsGuestEntitlement`: Cold-start với guest user truy vấn snapshot của guest mà không can thiệp vào tài khoản khác.
2. `testLoginSwitch_bindsNewUser_andIsolatesSessionEntitlements`: Chuyển đổi tài khoản từ Google User 1 sang Google User 2 cô lập hoàn toàn session và quyền VIP giữa hai người dùng.
3. `testForegroundSync_throttledWithinWindow_coalescesRedundantCalls`: Chuyển đổi activity nhiều lần trong khoảng thời gian < 30s được gom nhóm, chỉ thực hiện duy nhất 1 lần đồng bộ Play Billing.
4. `testForegroundSync_afterThrottleWindow_triggersNewSync`: Chuyển đổi activity sau khi hết thời gian 30s kích hoạt đồng bộ mới đúng quy định.
5. `testMoreFragment_recreation_doesNotTriggerDuplicateSync`: Tái tạo Fragment không kích hoạt đồng bộ trùng lặp lên Play Billing.
6. `testPostLoginHook_invokedUponSuccessfulSignIn`: Hook đăng nhập được kích hoạt tự động với đúng thông tin profile đã đăng nhập.

### 3.2. Kết quả chạy kiểm thử toàn bộ hệ thống
Chạy toàn bộ 11 test suite của hệ thống Billing (`102/102 PASS`, `0 Failures`, `0 Errors`):

| Test Suite | Số test | Kết quả |
| :--- | :---: | :---: |
| `AppAuthCanonicalIdentityTest` | 7 | **PASS** |
| `BillingConnectionCoordinatorTest` | 9 | **PASS** |
| `BillingEntitlementContractTest` | 13 | **PASS** |
| `BillingEntitlementStoreTest` | 9 | **PASS** |
| `BillingLifecycleIntegrationTest` | 6 | **PASS** |
| `BillingManagerTest` | 14 | **PASS** |
| `BillingOperationEventsTest` | 6 | **PASS** |
| `BillingPurchaseVerificationTest` | 7 | **PASS** |
| `BillingReauditRegressionTest` | 14 | **PASS** |
| `BillingReconciliationTest` | 6 | **PASS** |
| `VipPurchaseActionCoordinatorTest` | 11 | **PASS** |
| **Tổng cộng** | **102** | **102/102 PASS (100%)** |

### 3.3. Kết quả biên dịch APK Debug
- Lệnh: `.\gradlew.bat :app:assembleDebug`
- Trạng thái: **BUILD SUCCESSFUL** (39 actionable tasks: 3 executed, 36 up-to-date).

---

## 4. Bằng chứng lưu trữ
Toàn bộ log và file kết quả kiểm thử XML của gói B10 được lưu tại thư mục:
`build/vip-billing-b10/test-results/`
