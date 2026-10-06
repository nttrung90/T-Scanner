# BÁO CÁO GÓI G06 — THAO TÁC RETRY/CẤP QUYỀN THỰC SỰ (T06/P2)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói G05, việc điều phối sao lưu tài liệu đã hoàn toàn bất đồng bộ và monitor của `DocumentRepo` không còn bị giữ khi gọi từ UI. Toàn bộ tests G00 - G05 đều xanh.
- **Vấn đề T06:** Trong `SyncResultPresenter.present`, phương thức nhận các tham số callback `onRequestDrivePermission` và `onRetry`. Tuy nhiên, trong implementation trước đây, các tham số này hoàn toàn bị bỏ qua, presenter chỉ hiển thị `Toast` thụ động mà không cung cấp nút bấm hay thao tác tương tác cho người dùng. Người dùng gặp `AuthRequired` hay `Failure` không có cách bấm thử lại hay bấm cấp quyền ngay trên giao diện kết quả.

## 2. File thay đổi
- `app/src/main/res/values/strings.xml` và các file đa ngôn ngữ (`values-vi`, `values-de`, `values-es`, `values-fr`, `values-in`, `values-ja`, `values-pt`):
  - Bổ sung chuỗi hành động cấp quyền `<string name="grant_permission">` trên toàn bộ 8 ngôn ngữ được hỗ trợ:
    - Default (en): `Grant`
    - vi: `Cấp quyền`
    - de: `Erteilen`
    - es: `Conceder`
    - fr: `Autoriser`
    - in: `Izinkan`
    - ja: `許可`
    - pt: `Conceder`
- `app/src/main/java/com/tscanner/app/utils/SyncResultPresenter.kt`:
  - Thiết kế cấu trúc `ActionPrompt(val message: String, val actionLabel: String?, val onAction: (() -> Unit)?)`.
  - Cung cấp phương thức `showActionablePrompt`:
    - Tìm kiếm host Activity từ `context` (hỗ trợ `Activity`, `ContextWrapper`, `Fragment`).
    - Kiểm tra vòng đời: Nếu host Activity đang kết thúc (`isFinishing`) hoặc đã bị hủy (`isDestroyed`), lập tức hủy bỏ render và không giữ tham chiếu lambda/activity.
    - Hiển thị `Snackbar` với action button gắn trên `android.R.id.content` của host view.
    - Bảo đảm gọi action đúng một lần (`AtomicBoolean(false).compareAndSet(false, true)`), chống click đúp hoặc spam action.
    - Khi người dùng click action, tự động giải phóng debounce key (`lastPresentedKey = null`) để kết quả thử lại tiếp theo được hiển thị ngay.
    - Nếu không có host Activity/rootView hoặc không có callback hành động, chuyển sang hiển thị thông báo văn bản (Toast) mà không tạo nút bấm vô tác dụng (dead button).
    - Không bao giờ tự động gọi `onRequestDrivePermission` hoặc `onRetry` trong `present()`, chỉ kích hoạt khi người dùng thực sự bấm nút.
    - Hỗ trợ `expectedSessionGeneration`: Từ chối hiển thị kết quả từ session cũ nếu session generation đã thay đổi.
    - Cung cấp seam test `@VisibleForTesting var actionPresenter` và phương thức `resetForTesting()`.
- `app/src/test/java/com/tscanner/app/PostAuthorizationSyncResultTest.kt`:
  - Bổ sung 6 tests nghiệm thu toàn diện cho G06:
    - `testSyncResultPresenter_authRequired_surfacesActionablePromptWithoutAutoConsenting`: Kiểm tra hiển thị nút cấp quyền và click action gọi callback đúng 1 lần.
    - `testSyncResultPresenter_failure_surfacesRetryActionAndInvokesOnceOnTap`: Kiểm tra hiển thị nút thử lại và click action gọi `onRetry` đúng 1 lần.
    - `testSyncResultPresenter_withoutCallback_doesNotCreateDeadActionButton`: Khi thiếu callback, không tạo nút bấm vô tác dụng.
    - `testSyncResultPresenter_destroyedHost_discardsPresentationAndLambdas`: Host bị hủy không hiển thị prompt hoặc giữ lambda.
    - `testSyncResultPresenter_staleSession_isDiscarded`: Kết quả của session cũ bị loại bỏ an toàn.
    - `testSyncResultPresenter_freeSkipped_isCompletelySilent`: Gói Free/Skipped hoàn toàn im lặng.

## 3. Production paths được test
- `SyncResultPresenter.present` khi nhận `SyncCatalogResult.AuthRequired` với action cấp quyền.
- `SyncResultPresenter.present` khi nhận `SyncCatalogResult.Failure` với action thử lại.
- Cơ chế bảo vệ click action đơn lần (single invocation guarantee).
- Kiểm tra lifecycle an toàn khi Activity đã bị hủy.
- Kiểm tra chặn rò rỉ session cũ.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PostAuthorizationSyncResultTest --offline --console=plain
  ```
- **Kết quả:** `BUILD SUCCESSFUL` (12 tests completed, 0 failures).
- **Kiểm tra hồi quy toàn diện Auth/Drive/Presenter/Repo:**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --tests com.tscanner.app.DocumentRepoBackupDispatchTest --tests com.tscanner.app.PostAuthorizationSyncResultTest --tests com.tscanner.app.CloudBackupDispatchTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.DriveAuthorizationFlowTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain` → `BUILD SUCCESSFUL` (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- Không còn lỗi đỏ nào trong các gói G00 - G06.
- Gói tiếp theo: G07 — Tổng hợp host và thiết bị (Chạy full unit tests, lint, assembleDebug, rà soát launcher/saved-state/thread và tổng hợp nghiệm thu).

## 6. Runtime chưa chạy
- Runtime click Snackbar và hiển thị dialog trực tiếp trên màn hình thiết bị Android thực tế: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói G06
Hoàn tất G06. Người dùng đã có thao tác thử lại và cấp quyền thực sự trên giao diện khi gặp lỗi hoặc thiếu quyền Drive.
