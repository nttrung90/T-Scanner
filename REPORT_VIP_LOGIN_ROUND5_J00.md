# BÁO CÁO GÓI J00 — ĐƯA PROBE THÀNH REGRESSION VÀ ĐÍNH CHÍNH BÁO CÁO H05

Ngày thực hiện: 25/09/2026.  
Phạm vi: Gói J00 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)
1. **Không can thiệp production:** Tuyệt đối không thay đổi mã nguồn trong `app/src/main/` trong gói J00.
2. **Bảo toàn probe & assertions:** Chuyển nguyên vẹn 4 ca probe lỗi (F01a, F01b, F02-1, F02-2) và 1 ca control từ `build/vip-login-reaudit5/VipLoginRound5ProbeTest.kt` sang bộ test chính thức `app/src/test/java/com/tscanner/app/VipLoginRound5RegressionTest.kt`. Không làm mềm hay sửa đổi assertions để làm xanh giả tạo.
3. **Mô tả môi trường test trung thực:** Ghi rõ các test gọi `resetForTesting()` chỉ mô phỏng việc tái khởi tạo identity và singleton/counter trong JVM memory, không phải Android instrumentation process recreation thực tế.
4. **Đính chính kết luận H05 đúng mực:** Loại bỏ/thay thế các kết luận "an toàn tuyệt đối" và "100% do SHA-1", ghi nhận trạng thái Console/Play thực tế là **NOT RUN**, không bổ sung suy đoán nguyên nhân lỗi khác khi chưa có bằng chứng thực nghiệm.
5. **Bảo toàn working tree & build artifacts:** Không thực hiện git reset/clean, giữ nguyên thư mục `build/vip-login-reaudit5/`.

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. Thêm mới: `app/src/test/java/com/tscanner/app/VipLoginRound5RegressionTest.kt`
- **Trước:** Tập tin chưa tồn tại trong source tree `app/src/test`.
- **Sau:** Tạo mới bộ test hồi quy chứa 5 bài test (4 probe lỗi + 1 control):
  - `pendingProviderTaskSurvivesCoroutineCompletion()`: F01a — Timeout hoặc coroutine cleanup hoàn tất khi provider Task còn pending không được mở khóa login.
  - `replacedLogoutMustNotReleaseOldWaiter()`: F01b — Logout operation mới không được nhả waiter của logout cũ khi cleanup mới chưa kết thúc.
  - `legacyRestoredLoginTokenMustNotMatchNewProcess()`: F02 — Token phục hồi không có `processEpoch` phải bị từ chối trước attempt mới của process mới.
  - `oldEpochCancellationMustNotClearNewAttempt()`: F02 — Lệnh hủy mang token epoch cũ không được xóa active attempt của process mới.
  - `completedProviderAllowsLoginControl()`: Control test — Provider Task hoàn tất thật sự kết hợp coroutine hoàn tất cho phép đăng nhập bình thường.

### 2.2. Đính chính: `REPORT_VIP_LOGIN_ROUND4_H05.md`
- **Trước (Mục 4 & 6):**
  - Tuyên bố: *"Toàn bộ mã nguồn, trạng thái vòng đời, luồng xử lý và dữ liệu bộ nhớ đã được chứng minh an toàn tuyệt đối trên Host."*
  - Khẳng định: *"Nếu ứng dụng cài từ Google Play vẫn gặp lỗi đăng nhập (Status Code 10 / DEVELOPER_ERROR), nguyên nhân 100% nằm ở việc cấu hình thiếu chứng thư SHA-1 certificate fingerprint của Play App Signing..."*
  - Mục 6 kết luận hoàn tất 100% không ghi nhận giới hạn.
- **Sau (Mục 4 & 6):**
  - Đính chính rõ: Kết luận "an toàn tuyệt đối" và nguyên nhân "100% do SHA-1" đã bị thay thế bởi kết quả rà soát vòng 5 (xem `RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md`).
  - Ghi nhận trạng thái Google Play Console & Thiết bị: **NOT RUN**. Không có bằng chứng runtime/trace từ Console hay thiết bị để xác nhận nguyên nhân lỗi đăng nhập Play, và không thêm kết luận nguyên nhân khác theo suy đoán.
  - Lưu ý phạm vi hoàn tất tại H05 chỉ áp dụng cho phạm vi U01–U04 tại thời điểm thực hiện, các phát hiện mới F01, F02 được quản lý và xử lý tiếp trong Vòng 5 qua J00–J04.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound5RegressionTest --offline --console=plain
  ```
- **Exit Code:** `1` (Đúng theo kỳ vọng: có test thất bại, không có lỗi biên dịch `BUILD SUCCESSFUL` ở pha compile).
- **Kết quả biên dịch:**
  - `compileDebugUnitTestKotlin`: SUCCESS (không lỗi compile, không cảnh báo biên dịch).
- **Kết quả Test Suite (`com.tscanner.app.VipLoginRound5RegressionTest`):**
  - **Tổng số tests:** **5**
  - **Passed (Green):** **1** (`completedProviderAllowsLoginControl`)
  - **Failed (Red):** **4**
    1. `legacyRestoredLoginTokenMustNotMatchNewProcess`: `java.lang.AssertionError: Epoch-less restored token must not match a new process attempt`
    2. `oldEpochCancellationMustNotClearNewAttempt`: `java.lang.AssertionError: Cancel must compare epoch as well as counters expected:<GoogleLoginAttempt(...)> but was:<null>`
    3. `pendingProviderTaskSurvivesCoroutineCompletion`: `java.lang.AssertionError: Coroutine completion is not SDK Task completion`
    4. `replacedLogoutMustNotReleaseOldWaiter`: `java.lang.AssertionError: New logout still active; old deferred completion cannot grant login`
  - **Errors:** 0
  - **Skipped:** 0

---

## 4. Bảng theo dõi trạng thái Regression cho các gói tiếp theo

| Test Case | Nhóm lỗi | Gói sửa dự kiến | Trạng thái hiện tại (J00) | Ghi chú kỹ thuật |
|---|---|---|---|---|
| `pendingProviderTaskSurvivesCoroutineCompletion` | **F01a** | **J01** / **J02** | 🔴 **RED** (Fail) | `LogoutCoordinator` hiện tại hoàn tất deferred và xóa pending cờ dù Task chưa xong |
| `replacedLogoutMustNotReleaseOldWaiter` | **F01b** | **J01** / **J02** | 🔴 **RED** (Fail) | `startLogout` mới hoàn tất deferred cũ làm waiter A trả true sớm |
| `legacyRestoredLoginTokenMustNotMatchNewProcess` | **F02** | **J03** | 🔴 **RED** (Fail) | `matchesActiveAttemptLocked` cho qua khi token epoch rỗng |
| `oldEpochCancellationMustNotClearNewAttempt` | **F02** | **J03** | 🔴 **RED** (Fail) | `clearActiveAttemptIfMatchingLocked` chưa kiểm tra `processEpoch` |
| `completedProviderAllowsLoginControl` | **Control** | N/A | 🟢 **GREEN** (Pass) | Harness và luồng hoàn tất chuẩn hoạt động chính xác |

---

## 5. Các khía cạnh Runtime chưa chạy (NOT RUN)
- **Android Instrumentation / Process Death:** Các bài test sử dụng `AppAuthManager.resetForTesting()` để mô phỏng sự kiện khởi động lại của bộ nhớ/process trong cùng máy ảo JVM, chưa kiểm thử việc khôi phục Bundle qua cơ chế system process-kill thật của Android OS.
- **Google Play Services SDK Task thật:** Tương tác với Task listener của GoogleSignInClient khi timeout/cancel diễn ra trên thiết bị thực tế chưa chạy.
- **Google Play Console / Kênh phân phối / Chứng thư:** Xác thực luồng đăng nhập trên file cài đặt phân phối thực tế qua Google Play (AAB/Internal App Sharing) cùng chứng thư Play App Signing vẫn ở trạng thái **NOT RUN**.

---

## 6. Kết luận nghiệm thu gói J00
Gói J00 đã hoàn tất đầy đủ và chuẩn xác các tiêu chí:
- 4 regression tests đỏ tái hiện đúng lỗi F01 và F02.
- 1 control test xanh xác nhận harness hợp lệ.
- 0 lỗi compile.
- Báo cáo H05 đã được đính chính loại bỏ các kết luận vượt quá bằng chứng thực nghiệm.
- Không sửa mã production.

Dừng lại theo quy định, sẵn sàng chuyển sang gói **J01**.
