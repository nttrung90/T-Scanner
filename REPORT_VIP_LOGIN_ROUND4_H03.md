# BÁO CÁO GÓI H03 — IDENTITY KHÔNG LẶP GIỮA PROCESS (U03/P2)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói H02, 13 tests trong `VipLoginRound4RegressionTest` chạy với 2 ca đỏ còn lại (`probeOldDriveTokenDoesNotMatchNewProcessCounters`, `probeSyncActionMustRecheckSessionAtClick`).
- **Vấn đề U03:** Các bộ đếm request ID (`driveRequestGeneration`, `signInRequestGeneration`) và `sessionGeneration` khởi tạo lại từ 0 và 1 khi process khởi động lại (sau process death). Nếu không có epoch nonce phân biệt giữa các chu kỳ process, một token được lưu từ process trước (trong `savedInstanceState`) có thể tình cờ mang đúng bộ giá trị `(requestId, sessionGeneration, userId)` của một request mới sinh ra trong process mới, khiến registry nhận nhầm token cũ của process trước.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/DriveAuthorizationAttempt.kt`:
  - Thêm thuộc tính bất biến `val processEpoch: String = ""` với giá trị mặc định để bảo đảm tính tương thích tuần tự hóa (serialization compatibility).
- `app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt`:
  - Thêm thuộc tính bất biến `val processEpoch: String = ""`.
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Khởi tạo `@Volatile private var processEpoch: String = java.util.UUID.randomUUID().toString()`, mỗi process có một epoch UUID duy nhất không bao giờ trùng lặp.
  - Cung cấp `fun getProcessEpoch(): String = processEpoch`.
  - Trong `createDriveAuthorizationAttempt()` và `setPendingDriveAuthSessionForTesting()`: Gắn `processEpoch` hiện hành vào attempt token và registry.
  - Trong `handleDrivePermissionResult()`:
    - Kiểm tra `attempt.processEpoch.isBlank() || attempt.processEpoch != processEpoch`: Nếu token thiếu epoch hoặc thuộc process cũ, lập tức discard an toàn.
    - Trong registry validation: Bắt buộc `pending.processEpoch == attempt.processEpoch`.
  - Trong `cancelDriveAuthorizationAttempt()`: Nếu `attempt.processEpoch` khác với `processEpoch` hiện hành thì bỏ qua, không bao giờ cho phép token cũ hủy nhầm request của process mới.
  - Trong `resetForTesting()`: Tạo `processEpoch = UUID.randomUUID().toString()` mới mô phỏng quá trình tái khởi động process sạch sẽ.
- `app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt`:
  - Cập nhật probe U03 (`probeOldDriveTokenDoesNotMatchNewProcessCounters`): Khẳng định token cũ bị loại bỏ và token mới tạo trong process mới thành công.
  - Bổ sung 3 regression tests cho H03:
    - `regressionOldTokenWithSameCounters_doesNotCancelNewRequest`: Token cũ từ process trước không thể hủy nhầm request mới có cùng counters trong process mới.
    - `regressionSameProcessTokenSurvivesSerializationRoundTrip_andMatchesEpoch`: Token serialize/deserialize trong cùng process bảo toàn trọn vẹn `processEpoch` và được chấp nhận hợp lệ.
    - `regressionLegacyTokenMissingEpoch_isSafelyDiscarded`: Token cũ thiếu `processEpoch` (chuỗi rỗng) được discard an toàn, không gây crash và cho phép retry.

## 3. Invariant & Production paths được test
- Định danh yêu cầu cấp quyền sở hữu tính duy nhất tuyệt đối qua các chu kỳ process lifecycle (U03).
- Token thuộc process cũ bị từ chối an toàn, không tiêu thụ và không hủy request của process mới.
- Khả năng tương thích ngược của serialization token.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Trước H03:** `probeOldDriveTokenDoesNotMatchNewProcessCounters FAILED` (`AssertionError: Old process token must not match a newly registered request expected:<0> but was:<1>`).
- **Sau H03:**
  - `probeOldDriveTokenDoesNotMatchNewProcessCounters`: **PASSED** (U03 giải quyết triệt để).
  - `regressionOldTokenWithSameCounters_doesNotCancelNewRequest`: **PASSED**.
  - `regressionSameProcessTokenSurvivesSerializationRoundTrip_andMatchesEpoch`: **PASSED**.
  - `regressionLegacyTokenMissingEpoch_isSafelyDiscarded`: **PASSED**.
  - Toàn bộ tests của H00, H01, H02: **PASSED**.
- **Kiểm tra không hồi quy:**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain` → **BUILD SUCCESSFUL** (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- **H04a & H04b:** `probeSyncActionMustRecheckSessionAtClick` (U04/P2) — Action UI cũ chạy sau khi đổi phiên.

## 6. Runtime chưa chạy
- Lưu ý: Không xem `resetForTesting()` là quy trình Android OS process-kill thực tế đã test trên thiết bị thật (**NOT RUN**).

## 7. Điểm dừng gói H03
Hoàn tất H03. U03 đã được giải quyết triệt để với `processEpoch` UUID bảo đảm định danh request không bao giờ lặp giữa các chu kỳ process.
