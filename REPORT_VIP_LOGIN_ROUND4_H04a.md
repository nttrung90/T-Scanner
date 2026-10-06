# BÁO CÁO GÓI H04a — GUARD ACTION TẠI THỜI ĐIỂM BẤM (U04/P2, PRESENTER)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói H03, probe U04 (`probeSyncActionMustRecheckSessionAtClick`) là ca đỏ cuối cùng trong `VipLoginRound4RegressionTest`.
- **Vấn đề U04:** Trong `SyncResultPresenter`, việc xác thực session (`expectedSessionGeneration`) chỉ được thực hiện tại thời điểm render prompt. Khi người dùng click nút action (trên Snackbar hoặc dialog), lambda `safeAction` chỉ kiểm tra cờ `AtomicBoolean` mà không đối chiếu lại session generation hoặc tính hợp lệ của host tại thời điểm bấm (tap-time). Nếu người dùng đổi tài khoản hoặc đăng xuất trong khi prompt vẫn hiển thị trên Activity, việc bấm nút sau đó sẽ thực thi hành động cấp quyền/thử lại cho tài khoản mới một cách sai trái.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/SyncResultPresenter.kt`:
  - Mở rộng `ActionPrompt` lưu trữ metadata nguồn gốc: `targetSessionGeneration: Long?`, `targetUserId: String?`.
  - Cập nhật `present()` tiếp nhận thêm: `expectedUserId: String? = null` và `isHostValid: (() -> Boolean)? = null`.
  - Thiết lập cơ chế kiểm tra đa lớp tại thời điểm bấm (**Tap-Time Verification** trong `safeAction`):
    1. *Session Generation Check:* Bắt buộc `AppAuthManager.getSessionGeneration() == targetSessionGen`. Nếu session đã đổi (ví dụ nextSessionGeneration/signOut), lập tức bỏ qua action.
    2. *User ID Check:* Bắt buộc `AppAuthManager.getCurrentUser()?.id == targetUserId`. Nếu user đã chuyển sang tài khoản khác, hủy action.
    3. *Host Lifecycle Check:* Kiểm tra Activity (`isFinishing || isDestroyed`) và gọi predicate `isHostValid()` (kiểm tra Fragment view còn tồn tại). Nếu host view đã bị destroy, hủy action.
    4. *Single-Invocation Guarantee:* Duy trì `AtomicBoolean(false).compareAndSet(false, true)` chống double tap.
    5. *Debounce Clearance on Action:* Khi user click action, xóa `lastPresentedKey = null` để kết quả của lượt retry tiếp theo được hiển thị ngay lập tức mà không bị debounce chặn.
- `app/src/test/java/com/tscanner/app/PostAuthorizationSyncResultTest.kt`:
  - Bổ sung 4 regression tests chuyên biệt cho H04a:
    - `testSyncResultPresenter_tapAction_discardedIfSessionChangedAfterRender`: Bấm action sau khi đổi session generation bị loại bỏ hoàn toàn (0 callback calls).
    - `testSyncResultPresenter_tapAction_discardedIfHostViewDestroyedAfterRender`: Bấm action sau khi fragment view bị hủy bị loại bỏ (0 callback calls).
    - `testSyncResultPresenter_tapAction_discardedIfUserSwitchedAfterRender`: Bấm action sau khi chuyển đổi user A -> B bị loại bỏ (0 callback calls).
    - `testSyncResultPresenter_tapAction_singleInvocationAndClearsDebounceForRetry`: Double tap action chỉ gọi callback đúng 1 lần và giải phóng debounce key cho lượt tiếp theo.
- `app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt`:
  - Xác nhận probe U04 (`probeSyncActionMustRecheckSessionAtClick`) chuyển từ đỏ sang xanh.

## 3. Invariant & Production paths được test
- Hàng rào kiểm tra hợp lệ tại thời điểm người dùng click action (Tap-Time Fencing - U04).
- Prompt render không tự động kích hoạt callback.
- Chặn đứng mọi tác động của action từ session cũ lên session mới.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --tests com.tscanner.app.PostAuthorizationSyncResultTest --offline --console=plain
  ```
- **Trước H04a:** `probeSyncActionMustRecheckSessionAtClick FAILED` (`AssertionError: Action shown for previous session must be rejected on tap expected:<0> but was:<1>`).
- **Sau H04a:**
  - `probeSyncActionMustRecheckSessionAtClick`: **PASSED** (U04 giải quyết triệt để).
  - Toàn bộ 16 tests trong `VipLoginRound4RegressionTest`: **PASSED** (0 failures).
  - Toàn bộ 16 tests trong `PostAuthorizationSyncResultTest`: **PASSED** (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- Không còn ca đỏ trong regression suite.
- Gói tiếp theo: **H04b** — Nối origin (`expectedSessionGeneration`, `expectedUserId`) và lifecycle (`isHostValid`) từ tất cả các caller UI (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`, `VipUpgradeDialog`).

## 6. Runtime chưa chạy
- Runtime click Snackbar thực tế trên thiết bị vật lý: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói H04a
Hoàn tất H04a. Presenter đã có đầy đủ hợp đồng bảo vệ tap-time; sẵn sàng nối vào các caller UI trong gói H04b.
