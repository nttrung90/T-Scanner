# BÁO CÁO GÓI G04 — DRIVE CONSUME THEO REQUEST IDENTITY (T03/P2)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói G03, probe T03 (`probeCopiedDriveAttemptCannotBeConsumedTwice`) là ca kiểm thử duy nhất còn đỏ trong `VipLoginRound3RegressionTest`.
- **Vấn đề T03:** Trong `DriveAuthorizationAttempt`, cờ `consumed` được đánh dấu `@Transient` và chỉ mang tính cục bộ của từng instance. Khi một token Drive được serialize trước khi callback đến (cơ chế lưu `savedInstanceState` khi Activity mở consent dialog), bản deserialize có cờ `consumed == false`. Khi nhận kết quả, cả bản gốc và bản deserialize đều có thể gọi `consume()` thành công, khiến callback xử lý cấp quyền và đồng bộ Drive chạy 2 lần cho cùng một request, vi phạm nguyên tắc single-consume. Hơn nữa, việc thiếu registry đối chiếu có thể dẫn đến việc mượn request của launcher khác hoặc replay sau process kill.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/DriveAuthorizationAttempt.kt`:
  - Bỏ `@Transient` khỏi biến `consumed`, duy trì `@Volatile` và `@Synchronized fun consume()` để bản thân đối tượng vẫn tự bảo vệ khi qua serialization.
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Thiết lập authoritative registry tập trung cho các yêu cầu Drive:
    - `pendingDriveAttempts = mutableMapOf<Long, DriveAuthorizationAttempt>()`
    - `consumedDriveRequestIds = mutableSetOf<Long>()`
  - Đăng ký mọi attempt mới tạo vào registry authoritative với `requestId` tăng dần và `sessionGeneration` hiện hành.
  - Trong `handleDrivePermissionResult`:
    - Tra cứu và đối chiếu danh tính request (`requestId`, `sessionGeneration`, `userId`, `userEmail`) với registry authoritative dưới khóa `authStateLock`.
    - Kiểm tra `consumedDriveRequestIds.contains(targetAttempt.requestId)`: Nếu request ID đã từng được tiêu thụ (cho dù instance gửi đến là bản gốc, bản deserialize hay đối tượng dựng lại), lập tức hủy bỏ (discard) và không gọi callback.
    - Xóa khỏi `pendingDriveAttempts` và đưa vào `consumedDriveRequestIds` một cách nguyên tử trước khi gọi router.
  - Bổ sung `cancelDriveAuthorizationAttempt(attempt)` để dọn dẹp registry khi hủy bỏ hoặc gặp lỗi khởi chạy consent.
  - Xóa sạch pending và consumed registry khi `signOut()`, `nextSessionGeneration()`, hoặc `resetForTesting()`.
  - Process restart policy: Các request cũ từ process trước không tồn tại trong registry authoritative của process mới sẽ bị reject an toàn và cho phép người dùng khởi tạo request mới để retry.
- Các callsite UI (`HomeFragment.kt`, `MoreFragment.kt`, `PdfViewerActivity.kt`, `IdCardComposeActivity.kt`):
  - Bắt lỗi khi `driveAuthorizationLauncher.launch(intent)` gặp ngoại lệ để gọi `AppAuthManager.cancelDriveAuthorizationAttempt(attempt)` dọn dẹp registry kịp thời.
- `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`:
  - Bổ sung 4 regression tests cho G04:
    - `regressionDrive_serializeAfterConsume_rejected`: Token serialize sau khi đã consume không thể replay.
    - `regressionDrive_twoHosts_consumeIndependentlyWithoutCollision`: Hai launcher/host khác nhau tạo request độc lập được cấp quyền riêng rẽ không xung đột.
    - `regressionDrive_sameRequestIdDifferentGeneration_rejected`: Request ID cũ từ session generation trước bị loại bỏ an toàn.
    - `regressionDrive_processResetPolicy_rejectsUnregisteredAttempt`: Request từ process cũ không có trong registry bị từ chối và cho phép retry tạo token mới thành công.

## 3. Production paths được test
- `AppAuthManager.handleDrivePermissionResult` với token Drive gốc và token deserialize từ byte stream (T03).
- `AppAuthManager.handleDrivePermissionResult` sau khi serialize sau consume.
- Phân quyền Drive độc lập trên hai host đồng thời.
- Cơ chế đối chiếu và từ chối request lệch session generation hoặc không có trong registry sau process restart.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Trước G04:** `probeCopiedDriveAttemptCannotBeConsumedTwice FAILED` (`AssertionError: Single-consume must bind request identity across host reconstruction expected:<1> but was:<2>`).
- **Sau G04:**
  - `probeCopiedDriveAttemptCannotBeConsumedTwice`: **PASSED** (T03 giải quyết triệt để).
  - `regressionDrive_serializeAfterConsume_rejected`: **PASSED**.
  - `regressionDrive_twoHosts_consumeIndependentlyWithoutCollision`: **PASSED**.
  - `regressionDrive_sameRequestIdDifferentGeneration_rejected`: **PASSED**.
  - `regressionDrive_processResetPolicy_rejectsUnregisteredAttempt`: **PASSED**.
  - Toàn bộ 20 tests trong `VipLoginRound3RegressionTest`: **PASSED** (0 failures).
- **Kiểm tra không hồi quy (toàn bộ suite Drive & Auth):**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain` → **BUILD SUCCESSFUL** (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- G05: T05/P2 — Repository không chạy backup blocking trong callback UI.
- G06: T06/P2 — Thao tác retry/cấp quyền thực sự.

## 6. Runtime chưa chạy
- Quy trình cấp quyền Google Drive OAuth thực tế với Google Play Services consent dialog: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói G04
Hoàn tất G04. T03 đã được khắc phục hoàn toàn. Registry authoritative bảo đảm mọi yêu cầu Drive chỉ được tiêu thụ đúng một lần duy nhất.
