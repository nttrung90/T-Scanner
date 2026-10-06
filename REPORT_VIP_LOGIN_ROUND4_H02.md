# BÁO CÁO GÓI H02 — DRIVE RESULT BẮT BUỘC TOKEN HOST (U02/P2)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói H01, 11 tests trong `VipLoginRound4RegressionTest` chạy với 3 ca đỏ còn lại (`probeDriveNullHostTokenDoesNotBorrowOtherRequest`, `probeOldDriveTokenDoesNotMatchNewProcessCounters`, `probeSyncActionMustRecheckSessionAtClick`).
- **Vấn đề U02:** Trong `AppAuthManager.handleDrivePermissionResult`, biểu thức `val targetAttempt = attempt ?: pendingDriveAuthAttempt.getAndSet(null)` và các overload không nhận attempt vẫn cho phép mượn request đang chờ từ singleton. Khi host B tạo request và đang chờ kết quả, một callback trễ hoặc trùng lặp từ host A đến với `attempt = null` sẽ mượn và tiêu thụ mất request của host B, dẫn đến mất tương quan kết quả nội bộ.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Loại bỏ hoàn toàn fallback `attempt ?: pendingDriveAuthAttempt.getAndSet(null)`.
  - Nếu `attempt == null`: Lập tức discard kết quả ngay tại guard đầu tiên trước khi tra cứu registry hay gọi parser/consume.
  - Sửa các overload backward-compatible của `handleDrivePermissionResult` để truyền `attempt = null` và đánh dấu `@Deprecated("Callers must provide explicit attempt token from launch")`, triệt tiêu hoàn toàn đường bypass mượn request từ singleton.
- `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt`:
  - Cập nhật test `controlValidDriveAuthorization_withPendingRequest_succeeds` để truyền `attempt` rõ ràng theo đúng hợp đồng token bắt buộc.
- `app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt`:
  - Cập nhật probe U02 (`probeDriveNullHostTokenDoesNotBorrowOtherRequest`): Xác nhận callback null token bị loại và request hợp lệ tiếp theo có token vẫn thành công bình thường.
  - Bổ sung 2 regression tests cho H02:
    - `regressionDrive_duplicateCallbackAfterHostClearedToken_doesNotConsumeNewRequest`: Host A nhận kết quả và clear token, callback trùng tiếp theo không thể tiêu thụ request mới của host B.
    - `regressionDrive_cancelLaunch_clearsOnlyOwnerRequest`: Khi host A hủy request do lỗi launch, request của host B trong registry vẫn được bảo toàn nguyên vẹn.

## 3. Invariant & Production paths được test
- Đường callback phân quyền Google Drive bắt buộc phải có host token xác thực (U02).
- Các host UI (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) đều capture explicit token từ launch và truyền vào callback.
- Không mượn request đang chờ của launcher khác khi nhận callback không token.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Trước H02:** `probeDriveNullHostTokenDoesNotBorrowOtherRequest FAILED` (`AssertionError: Missing host token must not consume current request expected:<0> but was:<1>`).
- **Sau H02:**
  - `probeDriveNullHostTokenDoesNotBorrowOtherRequest`: **PASSED** (U02 giải quyết triệt để).
  - `regressionDrive_duplicateCallbackAfterHostClearedToken_doesNotConsumeNewRequest`: **PASSED**.
  - `regressionDrive_cancelLaunch_clearsOnlyOwnerRequest`: **PASSED**.
  - Toàn bộ tests của H00 và H01: **PASSED**.
- **Kiểm tra không hồi quy (Toàn bộ suite Drive & Auth):**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.AppAuthDriveAuthorizationTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain` → **BUILD SUCCESSFUL** (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- **H03:** `probeOldDriveTokenDoesNotMatchNewProcessCounters` (U03/P2) — Counter reset làm token cũ trùng request mới.
- **H04a & H04b:** `probeSyncActionMustRecheckSessionAtClick` (U04/P2) — Action UI cũ chạy sau khi đổi phiên.

## 6. Runtime chưa chạy
- Quy trình callback trên thiết bị thực khi Android OS hủy Activity của launcher: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói H02
Hoàn tất H02. Toàn bộ các đường phân quyền Drive production bắt buộc explicit token; không còn cơ chế mượn request từ singleton.
