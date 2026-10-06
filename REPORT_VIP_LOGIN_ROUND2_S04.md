# BÁO CÁO NGHIỆM THU GÓI S04 — DRIVE AUTHORIZATION CÓ REQUEST ID VÀ CONSUME MỘT LẦN

**Dự án**: T-Scanner (:app)  
**Thời gian thực hiện**: 2026-09-25  
**Tiến độ tổng thể**: S00 (Xong) → S01 (Xong) → S02 (Xong) → S03 (Xong) → **S04 (Hoàn thành)** → S05a → S05b → S06a → S06b → S07 → S08 → S09  

---

## 1. Mục tiêu và phạm vi gói S04

- Khắc phục triệt để lỗi **R04** đã xác định trong audit `RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md`:
  * Trước S04: Khi `pendingSnapshot` là `null`, `handleDrivePermissionResult` fallback lấy `currentUser?.email` và `sessionGeneration.get()`, cho phép kết quả Drive không gắn với request còn sống (hoặc kết quả trùng lặp, kết quả từ phiên khác) vẫn được xác thực thành công.
- Triển khai `DriveAuthorizationAttempt`: Token bất biến đại diện cho một yêu cầu cấp quyền Google Drive, mang `requestId`, `userId`, `userEmail`, `sessionGeneration`, thực hiện single-consume semantics (`consume()`), và tương thích `java.io.Serializable` để sống sót qua chu kỳ lưu state của Activity/Fragment.
- Cập nhật cả 4 callsite UI Drive launcher (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) để lưu trữ token attempt trong saved state và chuyển trực tiếp tới `handleDrivePermissionResult`.
- Loại bỏ hoàn toàn cơ chế fallback cho kết quả không có request hợp lệ; mọi kết quả không khớp request hoặc đã consume đều bị loại bỏ an toàn (discard silently without granting or running callbacks).

---

## 2. Các thay đổi chi tiết

### 2.1. File mới: `DriveAuthorizationAttempt.kt`
- Đường dẫn: `app/src/main/java/com/tscanner/app/utils/DriveAuthorizationAttempt.kt`
- Chứa immutable state:
  * `requestId: Long` (tăng đơn điệu qua `AtomicLong`)
  * `userId: String`
  * `userEmail: String`
  * `sessionGeneration: Long`
- Triển khai single-consume thread-safe:
  * Biến `@Transient @Volatile private var consumed: Boolean = false`
  * `@Synchronized fun consume(): Boolean` trả về `true` lần đầu, `false` cho mọi lần gọi sau.
  * Tương thích hoàn toàn với Java serialization (`serialVersionUID = 1L`) khi Activity/Fragment recreation qua process death.

### 2.2. Cập nhật `AppAuthManager.kt`
- Thêm `driveRequestGeneration = AtomicLong(0L)`.
- Thêm `pendingDriveAuthAttempt = AtomicReference<DriveAuthorizationAttempt?>(null)`.
- Thêm hàm `createDriveAuthorizationAttempt(): DriveAuthorizationAttempt?` chỉ tạo token khi có user đăng nhập hợp lệ.
- Cập nhật `getGoogleDriveSignInIntent(context, attempt, onAttemptCreated)`: tự động tạo và lưu trữ attempt, đồng thời thông báo qua callback `onAttemptCreated`.
- Cập nhật `handleDrivePermissionResult(context, resultCode, data, attempt, parser, onSuccess, onCancelled, onError)`:
  * Kiểm tra `currentUser != null` (nếu chưa đăng nhập báo `"Chưa đăng nhập tài khoản"`).
  * Lấy `targetAttempt = attempt ?: pendingDriveAuthAttempt.getAndSet(null)`.
  * Nếu `targetAttempt == null`: ghi nhận log và return an toàn, **không cấp quyền Drive và không gọi callback**.
  * Nếu `!targetAttempt.consume()`: ghi nhận duplicate result và return an toàn.
  * Kiểm tra `currentGen == targetAttempt.sessionGeneration`: nếu phiên đã chuyển giao thì loại bỏ.
  * Kiểm tra tài khoản khớp `currentUser.id` và `currentUser.email`.
  * Cập nhật hàm `signOut` giải phóng `_currentUser` ngay lập tức và hỗ trợ `mainDispatcher`.

### 2.3. Cập nhật 4 UI Drive Launchers
1. `HomeFragment.kt`:
   - Khai báo `pendingDriveAuthAttempt: DriveAuthorizationAttempt?`.
   - Lưu trữ trong `onSaveInstanceState` và phục hồi trong `onCreate`.
   - Nhận attempt trong `getGoogleDriveSignInIntent` và truyền vào `handleDrivePermissionResult`.
2. `MoreFragment.kt`:
   - Khai báo `pendingDriveAuthAttempt: DriveAuthorizationAttempt?`.
   - Lưu trữ trong `onSaveInstanceState` và phục hồi trong `onCreate`.
   - Nhận attempt trong `getGoogleDriveSignInIntent` và truyền vào `handleDrivePermissionResult`.
3. `PdfViewerActivity.kt`:
   - Khai báo `pendingDriveAuthAttempt: DriveAuthorizationAttempt?`.
   - Lưu trữ trong `onSaveInstanceState` và phục hồi trong `onCreate`.
   - Nhận attempt trong `getGoogleDriveSignInIntent` và truyền vào `handleDrivePermissionResult`.
4. `IdCardComposeActivity.kt`:
   - Khai báo `pendingDriveAuthAttempt: DriveAuthorizationAttempt?`.
   - Lưu trữ trong `onSaveInstanceState` (tích hợp cùng `IdCardSessionDraft`) và phục hồi trong `onCreate`.
   - Nhận attempt trong `getGoogleDriveSignInIntent` và truyền vào `handleDrivePermissionResult`.

### 2.4. Cập nhật và bổ sung Test Suites
1. **`VipLoginRound2RegressionTest.kt`**:
   - Probe **R04** (`probeDriveResultWithoutPendingRequestMustNotSucceed`) trước đây FAIL đỏ nay đã **PASS XANH 100%**.
   - Bổ sung 6 ca test hồi quy S04:
     * `regressionDrive_missingRequest_isDiscardedSafely`: Result không request bị bỏ qua an toàn.
     * `regressionDrive_duplicateResultSameRequest_consumedOnlyOnce`: Kết quả trùng cho cùng 1 attempt chỉ chạy đúng 1 lần.
     * `regressionDrive_overlappingHosts_doNotCollide`: 2 host A & B tạo request gối nhau không bị đè hay nhầm lẫn.
     * `regressionDrive_accountSwitchedWhileConsentOpen_rejected`: Đổi tài khoản/session generation trong khi mở consent bị từ chối.
     * `regressionDrive_logoutWhileConsentOpen_rejected`: Đăng xuất trong khi mở consent báo lỗi `"Chưa đăng nhập tài khoản"`.
     * `regressionDrive_attemptSurvivesSerializationRoundTrip`: Xác nhận ObjectOutputStream/ObjectInputStream khôi phục toàn vẹn dữ liệu token và single-consume.
2. **`DriveAuthorizationFlowTest.kt`**:
   - Cập nhật các ca kiểm thử hành vi hợp lệ sang truyền `DriveAuthorizationAttempt` tường minh.
3. **`GoogleIdentityOptionsTest.kt`**:
   - Cập nhật các ca kiểm thử Drive router sang truyền `DriveAuthorizationAttempt` tường minh.

---

## 3. Bằng chứng thực thi và kết quả kiểm thử

### Lệnh 1: Chạy bộ hồi quy VIP/Login vòng 2 và các test suite Drive
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.VipLoginRound2RegressionTest `
  --tests com.tscanner.app.DriveAuthorizationFlowTest `
  --tests com.tscanner.app.AppAuthDriveAuthorizationTest `
  --tests com.tscanner.app.GoogleIdentityOptionsTest `
  --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 22s
27 actionable tasks: 1 executed, 26 up-to-date
```
- Exit code: `0`
- Toàn bộ 26/26 tests trong `VipLoginRound2RegressionTest` đều **PASS**. Cả 4 probe baseline (R01, R02, R03, R04) đều đã chuyển sang trạng thái **GREEN**.

### Lệnh 2: Chạy toàn bộ test suite dự án `:app`
```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```
**Kết quả**:
```
BUILD SUCCESSFUL in 37s
27 actionable tasks: 1 executed, 26 up-to-date
```
- Báo cáo HTML `app/build/reports/tests/testDebugUnitTest/index.html`:
  * **Tổng số test**: `593`
  * **Thất bại**: `0`
  * **Bỏ qua**: `0`
  * **Tỷ lệ thành công**: `100%`

---

## 4. Khối bàn giao bắt buộc (Mandatory Handover Block)

- **Gói vừa hoàn thành**: S04 — Drive authorization có request ID và consume một lần
- **Trạng thái**: Hoàn thành, sạch lỗi, 593/593 test đạt, cả 4 probe R01–R04 đều đã xanh
- **Tập tin đã sửa/tạo**:
  * `app/src/main/java/com/tscanner/app/utils/DriveAuthorizationAttempt.kt` (mới)
  * `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
  * `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`
  * `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
  * `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`
  * `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`
  * `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt`
  * `app/src/test/java/com/tscanner/app/DriveAuthorizationFlowTest.kt`
  * `app/src/test/java/com/tscanner/app/GoogleIdentityOptionsTest.kt`
  * `REPORT_VIP_LOGIN_ROUND2_S04.md` (mới)
- **Lệnh kiểm chứng đã chạy**:
  * `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.GoogleIdentityOptionsTest --offline --console=plain` (Exit code 0)
  * `.\gradlew.bat :app:testDebugUnitTest --offline --console=plain` (Exit code 0, 593 tests passed)
- **Gói kế tiếp**: S05a — VipUpgradeDialog lifecycle và state machine (trang bị VIP dialog an toàn theo lifecycle)
- **Điểm dừng**: Dừng lại theo đúng quy tắc một gói mỗi lượt. Chờ lệnh "tiếp" từ người dùng.
