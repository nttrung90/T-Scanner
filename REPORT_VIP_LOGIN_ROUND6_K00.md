# BÁO CÁO GÓI K00 — ĐƯA PROBE VÒNG 6 THÀNH REGRESSION TEST SUITE

Ngày thực hiện: 26/09/2026.  
Phạm vi: Gói K00 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)

1. **Tuyệt đối không sửa production code:** Gói K00 không chỉnh sửa bất kỳ tập tin nào trong `app/src/main/`.
2. **Bảo toàn probe & test cũ:** Giữ nguyên các tệp kiểm toán trong `build/vip-login-reaudit6/`. Không sửa hay xóa test cũ để làm xanh giả tạo.
3. **Bảo tồn working tree:** Không git reset, không clean, không thay đổi cấu hình SDK/OAuth/keystore/Billing.
4. **Trung thực về môi trường thử nghiệm:** Ghi nhận ranh giới rõ ràng: kiểm thử trên JVM Host, thiết bị thật và Google Play Console là **NOT RUN**.

---

## 2. Danh mục tập tin can thiệp (Trước / Sau)

### 2.1. Thêm mới: `app/src/test/java/com/tscanner/app/VipLoginRound6RegressionTest.kt`
- **Trước:** Chưa tồn tại trong source tree `app/src/test`.
- **Sau:** Chuyển 3 ca kiểm thử từ `build/vip-login-reaudit6/VipLoginRound6ProbeTest.kt` thành bộ hồi quy chính thức:
  1. `finishingNewLogoutMustNotFinishOlderRunningCoroutine`: Probe lỗi — Thao tác logout mới B hoàn tất không được tự ý coi coroutine dọn dẹp cũ A đã xong để nhả cổng đăng nhập.
  2. `allActualCoroutinesFinishedAllowsLogin`: Control 1 — Khi cả hai coroutine A và B thật sự hoàn tất, cổng đăng nhập được mở bình thường.
  3. `pendingTaskStillBlocksAfterCoroutinesFinish`: Control 2 — Provider Task pending tiếp tục chặn cổng đăng nhập sau khi coroutine kết thúc, cho đến khi Task callback hoàn tất.

### 2.2. Production Code:
- **Không có thay đổi nào** trong `app/src/main/`.

---

## 3. Lệnh kiểm thử, Exit Code và Test Count

- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound6RegressionTest --offline --console=plain
  ```
- **Exit Code:** `1` (Đúng theo kỳ vọng baseline: 1 bài probe thất bại, không có lỗi biên dịch).
- **Kết quả biên dịch:**
  - `compileDebugUnitTestKotlin`: SUCCESS.
- **Chi tiết kết quả (`VipLoginRound6RegressionTest`):**
  - **Tổng số tests:** **3**
  - **Passed (Green):** **2**
    - `allActualCoroutinesFinishedAllowsLogin`: PASSED
    - `pendingTaskStillBlocksAfterCoroutinesFinish`: PASSED
  - **Failed (Red):** **1**
    - `finishingNewLogoutMustNotFinishOlderRunningCoroutine`: FAILED
      `java.lang.AssertionError: A never finished or cancelled; B completion cannot grant login`
  - **Errors:** 0
  - **Skipped:** 0

---

## 4. Xác định test cũ giả định "B bắt đầu tự kết thúc A" (để xử lý ở K01)

Qua rà soát bộ test hiện hữu `app/src/test/java/com/tscanner/app/LogoutCoordinatorTest.kt`, phát hiện bài test sau đang chứa giả định này:

- **Tên test:** `logoutAToBWhileWaiterAIsWaitingDoesNotUnlockPrematurely` (dòng 71–88)
- **Đoạn mã hiện tại:**
  ```kotlin
  val opA = LogoutCoordinator.startLogout()
  val waiter = async(start = CoroutineStart.UNDISPATCHED) {
      LogoutCoordinator.awaitProviderCleanup(50)
  }
  val opB = LogoutCoordinator.startLogout()
  assertFalse(waiter.await())

  // Đoạn giả định: chỉ complete opB, opA không được complete/cancel nhưng vẫn mong awaitProviderCleanup = true
  LogoutCoordinator.onCleanupCompleted(opB)
  assertTrue("After op B completes, subsequent login is allowed", LogoutCoordinator.awaitProviderCleanup(10))
  ```
- **Kế hoạch xử lý tại K01:** Cập nhật bài test trên để `opA` phải được thông báo kết thúc/hủy rõ ràng (`LogoutCoordinator.onCleanupCompleted(opA)` hoặc `onCleanupCancelled(opA)`) trước khi mong đợi mở cổng đăng nhập, đảm bảo phản ánh chính xác vòng đời đa thao tác thực tế mà không nới lỏng tiêu chí an toàn.

---

## 5. Giới hạn và Runtime chưa chạy

- **Host JVM:** Các bài kiểm tra chạy trên JVM, kiểm chứng logic trạng thái của `LogoutCoordinator`.
- **Thiết bị thật / Google Play Console:** **NOT RUN**. Chưa thử nghiệm việc hai luồng `signOut` thực tế chạy đè lên nhau trên thiết bị Android với Google Play Services và Credential Manager thật.

---

## 6. Trạng thái và bước tiếp theo

- Gói **K00 đã hoàn tất** đúng nghiệm thu: 1 đỏ (probe tái hiện lỗi) / 2 xanh (controls).
- Dừng lại theo đúng quy tắc, không tự ý chuyển sang K01.
