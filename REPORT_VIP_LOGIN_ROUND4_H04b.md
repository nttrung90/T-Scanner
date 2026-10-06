# BÁO CÁO GÓI H04b — NỐI ORIGIN VÀ LIFECYCLE TỪ TẤT CẢ CALLER UI

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói H04a, `SyncResultPresenter` đã được trang bị cơ chế kiểm tra đa lớp tại thời điểm click (`safeAction` tap-time verification). Hợp đồng đã sẵn sàng nhưng các callsite UI chưa truyền metadata nguồn gốc (`originUserId`, `originSessionGen`) và lifecycle view.
- **Vấn đề giải quyết trong H04b:** Khi các màn hình UI (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`, `VipUpgradeDialog`) bắt đầu tiến trình đồng bộ hoặc nhận callback, nếu không capture session generation và user ID tại thời điểm bắt đầu thao tác, hoặc nếu Activity còn sống nhưng Fragment view đã bị hủy/thay thế, callback cũ có thể hiển thị hoặc kích hoạt action trên phiên làm việc mới.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/SyncResultPresenter.kt`:
  - Chuẩn hóa chữ ký phương thức `present()` cho production: Bắt buộc truyền `expectedSessionGeneration: Long`, `expectedUserId: String?`, và `isHostValid: (() -> Boolean)?`.
  - Loại bỏ các giá trị mặc định tùy chọn lỏng lẻo ở production API nhằm triệt tiêu hoàn toàn nguy cơ caller vô tình bỏ qua việc bảo vệ nguồn gốc.
  - Cung cấp overload `@VisibleForTesting` phục vụ các test case không thuộc phạm vi lifecycle.
- `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`:
  - Capture `startUser = AppAuthManager.getCurrentUser()?.id` và `startGen = AppAuthManager.getSessionGeneration()` ngay khi bắt đầu gọi `runPostAuthorizationSync` (kể cả từ launcher kết quả Drive hay từ nút VIP).
  - Truyền `originUserId`, `originSessionGen`, và view lifecycle guard `isHostValid = { isAdded && _binding != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }` vào `SyncResultPresenter.present`.
  - Khi người dùng bấm "Thử lại", tạo danh tính phiên mới (`retryUser`, `retryGen`) để retry có lifecycle độc lập.
- `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:
  - Nối `startUser` và `startGen` tại tất cả 4 điểm gọi `runPostAuthorizationSync` và dialog callback.
  - Áp dụng view lifecycle guard `isHostValid = { isAdded && _binding != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }`.
- `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`:
  - Nối `startUser` và `startGen` tại các điểm kích hoạt đồng bộ sau phân quyền Drive, sau đăng nhập, trong `CreatePdfDialog` (`createNewPdf`), và `SaveExportDialog` (`saveFinalDocument`).
  - Áp dụng Activity lifecycle guard `isHostValid = { !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }`.
- `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`:
  - Nối `startUser` và `startGen` tại các điểm kích hoạt đồng bộ sau phân quyền Drive, sau đăng nhập, và trong dialog options.
  - Áp dụng Activity lifecycle guard `isHostValid = { !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }`.
- `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`:
  - Bắt `startUser` và `startGen` trước khi kích hoạt `runPostAuthorizationSync` và chuyển tiếp chính xác vào `SyncResultPresenter.present`.
- `app/src/test/java/com/tscanner/app/PostAuthorizationSyncResultTest.kt`:
  - Bổ sung 3 integration tests xác thực việc nối host & producer:
    - `testHostIntegration_callbackFromOldSessionAfterLogoutLoginB_discardedWithoutRenderingOnB`: Callback từ User A sau khi đăng xuất và User B đăng nhập bị loại bỏ hoàn toàn, không render prompt/toast trên phiên B.
    - `testHostIntegration_fragmentReplaceInSameActivity_discardsActionAtTapTime`: Khi Fragment bị thay thế / view bị destroy trong khi Activity còn sống, action tap bị loại bỏ an toàn.
    - `testHostIntegration_retryCreatesFreshOperationIdentity`: Thao tác thử lại tạo ra danh tính phiên mới và hoàn tất thành công.

## 3. Invariant & Production paths được test
- Toàn bộ 5 caller UI production (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`, `VipUpgradeDialog`) capture và truyền đầy đủ metadata nguồn gốc.
- Hàng rào vòng đời View của Fragment và Lifecycle của Activity ngăn chặn rò rỉ hoặc thực thi hành động trên view đã mất.
- Đảm bảo tính liên tục của thao tác Retry.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PostAuthorizationSyncResultTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Kết quả:** `BUILD SUCCESSFUL` (19 tests trong `PostAuthorizationSyncResultTest`, 16 tests trong `VipLoginRound4RegressionTest`, 0 failures).
- **Kiểm tra hồi quy toàn diện:**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --tests com.tscanner.app.VipLoginRound3RegressionTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.DocumentRepoBackupDispatchTest --offline --console=plain` → `BUILD SUCCESSFUL` (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- Không còn ca đỏ nào trong toàn bộ các gói H00 - H04b.
- Gói tiếp theo: **H05** — Nghiệm thu tổng hợp và điểm dừng.

## 6. Runtime chưa chạy
- Thao tác chuyển tab trong khi Snackbar đang hiển thị trên thiết bị thực tế: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói H04b
Hoàn tất H04b. Nguồn gốc session, user ID, và lifecycle guard đã được kết nối hoàn chỉnh tại toàn bộ các callsite UI production.
