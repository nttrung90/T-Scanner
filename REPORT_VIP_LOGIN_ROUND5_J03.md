# BÁO CÁO GÓI J03 — ĐỒNG NHẤT TIÊU CHÍ IDENTITY VÀ LƯU TRỮ TRẠNG THÁI HOST

Ngày thực hiện: 25/09/2026.  
Phạm vi: Gói J03 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)

1. **Tiêu chuẩn nhận diện đồng nhất tuyệt đối (F02):**
   - Một `GoogleLoginAttempt` token chỉ được coi là hợp lệ khi và chỉ khi thỏa mãn đồng thời:
     - `active.requestId == token.requestId`
     - `active.initialSessionGeneration == token.initialSessionGeneration`
     - `sessionGeneration.get() == token.initialSessionGeneration`
     - `token.processEpoch.isNotEmpty()`
     - `active.processEpoch.isNotEmpty()`
     - `token.processEpoch == processEpoch`
     - `active.processEpoch == processEpoch`
   - Tuyệt đối **không whitelist epoch rỗng** (`""`). Mọi token thiếu epoch hoặc mang epoch rỗng từ bundle cũ/ngoài tiến trình đều bị từ chối triệt để.
2. **Giải phóng khóa đăng nhập an toàn (`clearActiveAttemptIfMatchingLocked`):**
   - Xác thực cả `requestId`, `initialSessionGeneration`, và `processEpoch == currentProcessEpoch`.
   - Một lệnh hủy mang token epoch cũ hoặc request ID khác tuyệt đối không thể xóa hay can thiệp vào attempt đang chạy của tiến trình hiện tại.
3. **Lưu trữ trọn vẹn trạng thái Host qua Bundle:**
   - Cả 3 Activity/Fragment (`MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) lưu và khôi phục trọn vẹn cả 3 trường `requestId`, `initialSessionGeneration`, và `processEpoch` thông qua helper `writeToBundle` và `fromBundle`.
   - Nếu Bundle phục hồi thiếu `processEpoch` (ví dụ từ bản lưu cũ hoặc tiến trình đã chết), `fromBundle` trả về `null`, yêu cầu người dùng thử lại thay vì mượn token sai lệch.
4. **Bảo toàn working tree & build artifacts:**
   - Không thực hiện git reset/clean, không xóa probe logs.
   - Không thay đổi SDK, gradle dependencies, OAuth credentials hay Billing.

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
- **Trước:**
  - `matchesActiveAttemptLocked`: Có biểu thức whitelist epoch rỗng: `(token.processEpoch.isEmpty() || active.processEpoch.isEmpty() || active.processEpoch == token.processEpoch)`. Khi token phục hồi không có epoch, nó vẫn khớp với attempt mới trong tiến trình mới.
  - `clearActiveAttemptIfMatchingLocked`: Chỉ so sánh `requestId` và `initialSessionGeneration`, bỏ qua hoàn toàn `processEpoch`. Khi nhận lệnh hủy từ epoch cũ, active attempt của tiến trình mới bị xóa nhầm (`null`).
- **Sau:**
  - Cập nhật `matchesActiveAttemptLocked`: Yêu cầu nghiêm ngặt cả `token.processEpoch` và `active.processEpoch` phải `isNotEmpty()` và đều bằng với `currentProcessEpoch` (`processEpoch`).
  - Cập nhật `clearActiveAttemptIfMatchingLocked`: Bắt buộc kiểm tra `requestId`, `initialSessionGeneration` và đảm bảo `token.processEpoch.isNotEmpty() && active.processEpoch.isNotEmpty() && token.processEpoch == currentProcessEpoch && active.processEpoch == currentProcessEpoch` trước khi giải phóng khóa.

### 2.2. `app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt`
- **Trước:**
  - Data class chỉ chứa `requestId`, `initialSessionGeneration`, `processEpoch = ""`. Không có phương thức serialize/deserialize vào `Bundle`.
- **Sau:**
  - Bổ sung `writeToBundle(bundle: Bundle)`.
  - Bổ sung `fromBundle(bundle: Bundle?): GoogleLoginAttempt?` và `fromValues(requestId: Long, sessionGeneration: Long, processEpoch: String?): GoogleLoginAttempt?`.
  - Nếu `epoch` rỗng hoặc `null`, từ chối tạo đối tượng và trả về `null`.

### 2.3. Cập nhật 3 UI Hosts lưu và phục hồi `processEpoch`:
- **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`**:
  - `onCreate`: Phục hồi `pendingSignInAttempt = GoogleLoginAttempt.fromBundle(savedInstanceState)`.
  - `onSaveInstanceState`: Lưu `pendingSignInAttempt?.writeToBundle(outState)`.
- **`app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`**:
  - `onCreate`: Phục hồi `pendingSignInAttempt = GoogleLoginAttempt.fromBundle(savedInstanceState)`.
  - `onSaveInstanceState`: Lưu `pendingSignInAttempt?.writeToBundle(outState)`.
- **`app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`**:
  - `onCreate`: Phục hồi `pendingSignInAttempt = GoogleLoginAttempt.fromBundle(savedInstanceState)`.
  - `onSaveInstanceState`: Lưu `pendingSignInAttempt?.writeToBundle(outState)`.

### 2.4. `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`
- Cập nhật 4 bài test mô phỏng tái tạo token trong cùng tiến trình (`probeRecreatedLoginCancelReleasesAttempt`, `regressionRecreatedLoginSuccess...`, `regressionRecreatedLoginError...`, `regressionStaleRecreatedAttemptA...`) truyền `original.processEpoch` để phản ánh đúng ngữ cảnh tái tạo cùng tiến trình.

### 2.5. Tạo mới: `app/src/test/java/com/tscanner/app/GoogleLoginAttemptSerializationTest.kt`
- Bổ sung 10 ca kiểm thử đơn vị kiểm tra:
  - Khả năng ghi và đọc bundle/values an toàn.
  - Từ chối bundle thiếu `processEpoch` hoặc epoch rỗng.
  - Từ chối requestId hoặc sessionGen âm/bằng 0.
  - Xác thực tính hợp lệ của token tái tạo cùng epoch và từ chối token epoch rỗng / epoch khác.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

### 3.1. Kiểm thử bộ hồi quy vòng 5 (`VipLoginRound5RegressionTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound5RegressionTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **5 / 5 tests PASSED (GREEN)**
  1. `pendingProviderTaskSurvivesCoroutineCompletion`: PASSED (F01a)
  2. `replacedLogoutMustNotReleaseOldWaiter`: PASSED (F01b)
  3. `legacyRestoredLoginTokenMustNotMatchNewProcess`: PASSED (F02-1)
  4. `oldEpochCancellationMustNotClearNewAttempt`: PASSED (F02-2)
  5. `completedProviderAllowsLoginControl`: PASSED (Control)

### 3.2. Kiểm thử bộ hồi quy vòng 3 (`VipLoginRound3RegressionTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **20 / 20 tests PASSED (GREEN)**

### 3.3. Kiểm thử bộ tuần tự hóa (`GoogleLoginAttemptSerializationTest`)
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginAttemptSerializationTest --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Kết quả:** **10 / 10 tests PASSED (GREEN)**

### 3.4. Kiểm thử tích hợp toàn bộ các suite liên quan
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipLoginRound*" --tests "com.tscanner.app.GoogleLogin*" --tests "com.tscanner.app.LogoutCoordinatorTest" --tests "com.tscanner.app.AppAuth*" --offline --console=plain
  ```
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Chi tiết:**
  - `AppAuthCanonicalIdentityTest`: 7/7 PASSED
  - `AppAuthDriveAuthorizationTest`: 4/4 PASSED
  - `AppAuthGoogleLogoutIntegrationTest`: 5/5 PASSED
  - `GoogleLoginAttemptSerializationTest`: 10/10 PASSED
  - `GoogleLoginFlowTest`: 7/7 PASSED
  - `LogoutCoordinatorTest`: 7/7 PASSED
  - `VipLoginRound2RegressionTest`: 26/26 PASSED
  - `VipLoginRound3RegressionTest`: 20/20 PASSED
  - `VipLoginRound4RegressionTest`: 16/16 PASSED
  - `VipLoginRound5RegressionTest`: 5/5 PASSED
  - **Tổng:** **107 / 107 tests PASSED**

---

## 4. Trạng thái regression gói sau & Giới hạn JVM

- **Trạng thái regression gói sau:**
  - J00, J01, J02, J03 đều đã hoàn tất và xanh 100%. Không còn bài test nào đỏ trong phạm vi F01/F02.
  - Gói tiếp theo: **J04 — Nghiệm thu toàn diện và bàn giao**.
- **Giới hạn mô phỏng JVM:**
  - `resetForTesting()` mô phỏng việc tái khởi tạo singleton, bộ đếm thế hệ (session generation), request counter và định danh tiến trình `processEpoch` trong bộ nhớ JVM.
  - Môi trường JVM unit test sử dụng compile-time stub `android.jar`, không thay thế cho bài test instrumentation trên thiết bị vật lý với process lifecycle thật (OS-level `killProcess` và Activity recreation từ WindowManager).
  - Trạng thái Google Play Console / Thiết bị thật: **NOT RUN**.
