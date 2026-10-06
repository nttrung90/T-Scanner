# BÁO CÁO GÓI G03 — ĐĂNG XUẤT CÓ QUYỀN SỞ HỮU CLEANUP (T04/P1)

## 1. Baseline & Status trước khi thực hiện
- **Baseline:** Sau gói G02, 13 tests trong `VipLoginRound3RegressionTest` chạy với 2 ca đỏ còn lại (`probeLateSignOutMustPreserveNewLogin`, `probeCopiedDriveAttemptCannotBeConsumedTwice`).
- **Vấn đề T04:** Trong `AppAuthManager.signOut`, khi người dùng A đăng xuất, local state được xóa ngay và một coroutine bất đồng bộ được phóng để dọn dẹp các provider (WorkManager, CredentialManager, GoogleSignInClient). Tuy nhiên, khối `finally` của coroutine này gọi `clearSavedUser(context)` và `_currentUser.value = null` một cách vô điều kiện. Nếu người dùng B đăng nhập trước khi coroutine cleanup của A hoàn tất (ví dụ cleanup bị delay do mạng chậm hoặc dispatcher hàng đợi), cleanup của A sẽ xóa sạch profile trong SharedPreferences và xóa `currentUser` của phiên B. Ngoài ra, việc gọi `cancelAllWork()` vô tội vạ làm ảnh hưởng đến các tác vụ của phiên B.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:
  - Thiết lập giao thức phân định quyền sở hữu (fencing) phiên đăng xuất:
    - Khi bắt đầu `signOut`, bắt `logoutSessionGen = sessionGeneration.get()` và `previousUserId`.
    - Dọn dẹp local state và preferences ngay lập tức (`clearSavedUser`, `_currentUser.value = null`, hủy active attempt lock).
    - Hạn chế phạm vi WorkManager: hủy công việc theo tag `owner_$previousUserId` thay vì gọi `cancelAllWork()` toàn bộ app.
    - Trong coroutine cleanup: kiểm tra hàng rào an toàn (`sessionGeneration.get() == logoutSessionGen && _currentUser.value == null`). Nếu phiên B đã chiếm auth hoặc user mới đã đăng nhập, provider signOut của A được bỏ qua an toàn để không can thiệp phiên B.
    - Quản lý Task hoàn tất: Provider `GoogleSignIn.getClient(context, gso).signOut()` được chờ hoàn tất thông qua Task completion listener có timeout an toàn (3s).
    - Trong `finally`: chỉ gọi `clearSavedUser` và set `_currentUser.value = null` nếu `sessionGeneration.get() == logoutSessionGen`. Ngược lại, bảo tồn nguyên vẹn dữ liệu và đối tượng của phiên B.
    - Cung cấp seam `@VisibleForTesting var googleSignOutAction` hỗ trợ kiểm thử thứ tự gọi và fault injection, tự động reset trong `resetForTesting()`.
- `app/src/test/java/com/tscanner/app/VipLoginRound3RegressionTest.kt`:
  - Cập nhật assertion probe T04: kiểm tra bảo tồn cả `currentUser?.id == "b"` và persistent profile trong SharedPreferences.
  - Bổ sung 3 regression tests cho G03:
    - `regressionLateSignOut_doesNotInvokeProviderSignOutForNewSession`: Kiểm tra bằng test spy rằng provider signOut không được gọi khi phiên mới B đã active.
    - `regressionSignOutCalledTwice_doesNotHoldLockAndClearsState`: Gọi signOut liên tiếp 2 lần hoàn thành trơn tru, không gây deadlock hay kẹt khóa.
    - `regressionSignOutProviderError_stillInvokesCompletionCallback`: Lỗi/ngoại lệ từ provider SDK vẫn gọi `onComplete()` và dọn dẹp local state, không bao giờ giữ khóa vĩnh viễn.

## 3. Production paths được test
- `AppAuthManager.signOut` với coroutine trễ khi phiên B đã đăng nhập trước.
- `AppAuthManager.signOut` kép liên tiếp.
- `AppAuthManager.signOut` khi provider gặp lỗi kỹ thuật / exception.
- Kiểm tra tính nhất quán tức thì của local state và preferences ngay khi gọi `signOut`.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
  ```
- **Trước G03:** `probeLateSignOutMustPreserveNewLogin FAILED` (`AssertionError: Old logout completion must not erase the new session expected:<b> but was:<null>`).
- **Sau G03:**
  - `probeLateSignOutMustPreserveNewLogin`: **PASSED** (T04 giải quyết triệt để).
  - `regressionLateSignOut_doesNotInvokeProviderSignOutForNewSession`: **PASSED**.
  - `regressionSignOutCalledTwice_doesNotHoldLockAndClearsState`: **PASSED**.
  - `regressionSignOutProviderError_stillInvokesCompletionCallback`: **PASSED**.
  - Toàn bộ các test của G00, G01, G02: **PASSED**.
- **Kiểm tra không hồi quy:**
  - `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.GoogleLoginFlowTest --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.GoogleSignInResultRouterTest --offline --console=plain` → **BUILD SUCCESSFUL** (0 failures).

## 5. Regression còn đỏ cho các gói tiếp theo
- G04: `probeCopiedDriveAttemptCannotBeConsumedTwice` (T03/P2) — Drive token qua serialization consume hai lần.

## 6. Runtime chưa chạy
- Thử nghiệm trên thiết bị vật lý với Google Play Services background account syncing: **NOT RUN** (thiếu thiết bị ADB).

## 7. Điểm dừng gói G03
Hoàn tất G03. T04 đã được khắc phục hoàn toàn. Phiên đăng nhập mới không bao giờ bị xóa bởi tiến trình đăng xuất cũ.
