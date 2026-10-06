# BÁO CÁO GÓI H00 — GIỮ BỐN REGRESSION MỚI VÒNG 4

## 1. Baseline & Status trước khi thực hiện
- **Branch/Status:** Trên nhánh `master`, bảo toàn toàn bộ working tree và các kết quả từ các vòng trước.
- **Thư mục bằng chứng:** Bảo toàn nguyên vẹn `build/vip-login-reaudit4` (`VipLoginRound4ProbeTest.kt`, `probe-results.xml`, `baseline-results`).
- **Production:** Tuyệt đối không thay đổi mã nguồn production trong gói H00 này.

## 2. File thay đổi
- **File mới tạo:** `app/src/test/java/com/tscanner/app/VipLoginRound4RegressionTest.kt`
- **File khác:** Không chỉnh sửa bất kỳ file mã nguồn nào khác trong `app/src/main` hoặc `app/src/test`.

## 3. Invariant & Production paths được test
- **U01 (P1):** Hàng rào bất biến giữa cleanup provider (destructive SDK actions) và phiên đăng nhập mới. Cleanup của phiên cũ không được phép chạy xen kẽ hoặc hoàn tất sau khi phiên đăng nhập mới đã commit.
- **U02 (P2):** Callback phân quyền Google Drive bắt buộc phải có host token xác thực. Callback thiếu token (`attempt == null`) không được phép mượn và tiêu thụ request đang active của host khác.
- **U03 (P2):** Tính bất biến của request identity qua các chu kỳ process lifecycle. Token từ process cũ (sau reset) không được phép trùng khớp hoặc tiêu thụ request mới tạo trong process mới dù có cùng counter / session generation.
- **U04 (P2):** Tính an toàn tại thời điểm tương tác (click-time validation) của presenter. Thao tác hiển thị cho phiên làm việc cũ không được phép kích hoạt callback cấp quyền hoặc retry khi người dùng bấm sau khi phiên đã thay đổi.
- **Các controls:**
  - `controlValidDriveRequest_succeeds`: Yêu cầu cấp quyền Drive hợp lệ với token khớp thành công.
  - `controlSyncActionInSameSession_succeeds`: Thao tác trên prompt trong cùng phiên thực thi thành công đúng 1 lần khi bấm.
  - `controlNormalLogoutAndLogin_inSequentialOrder_succeeds`: Đăng xuất và đăng nhập theo trình tự thông thường hoạt động chính xác.

## 4. Lỗi trước/sau và lệnh chạy
- **Lệnh thực thi:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
  ```
- **Kết quả:** `7 tests completed, 4 failed` (BUILD FAILED có chủ ý cho baseline H00).
- **Chi tiết 4 ca regression đỏ (U01 – U04):**
  1. `probeProviderCleanupMustNotOverlapNewLogin` (U01):
     - *Assertion:* `java.lang.AssertionError: Destructive provider cleanup must finish before new login commits`
     - *Nguyên nhân:* `AppAuthManager.signOut` phóng provider cleanup chạy độc lập; khi provider cleanup bị giữ/treo, phiên đăng nhập mới B vẫn tiến hành commit, dẫn đến provider cleanup chạy sau khi user B đã active.
  2. `probeDriveNullHostTokenDoesNotBorrowOtherRequest` (U02):
     - *Assertion:* `java.lang.AssertionError: Missing host token must not consume current request expected:<0> but was:<1>`
     - *Nguyên nhân:* `AppAuthManager.handleDrivePermissionResult` vẫn duy trì fallback `attempt ?: pendingDriveAuthAttempt.getAndSet(null)`, cho phép callback không có token mượn và tiêu thụ request active của host khác.
  3. `probeOldDriveTokenDoesNotMatchNewProcessCounters` (U03):
     - *Assertion:* `java.lang.AssertionError: Old process token must not match a newly registered request expected:<0> but was:<1>`
     - *Nguyên nhân:* Các bộ đếm `requestId` và `sessionGeneration` khởi tạo lại từ đầu (0 và 1) sau khi process khởi động lại mà không có epoch nonce phân biệt giữa các process.
  4. `probeSyncActionMustRecheckSessionAtClick` (U04):
     - *Assertion:* `java.lang.AssertionError: Action shown for previous session must be rejected on tap expected:<0> but was:<1>`
     - *Nguyên nhân:* `SyncResultPresenter` chỉ kiểm tra `expectedSessionGeneration` tại thời điểm render, lambda `safeAction` không kiểm tra lại tính hợp lệ của session tại thời điểm người dùng click.
- **Chi tiết 3 ca controls xanh:**
  - `controlValidDriveRequest_succeeds`: PASSED
  - `controlSyncActionInSameSession_succeeds`: PASSED
  - `controlNormalLogoutAndLogin_inSequentialOrder_succeeds`: PASSED

## 5. Regression còn đỏ cho các gói tiếp theo
- **H01:** Giải quyết U01 (`probeProviderCleanupMustNotOverlapNewLogin` - P1).
- **H02:** Giải quyết U02 (`probeDriveNullHostTokenDoesNotBorrowOtherRequest` - P2).
- **H03:** Giải quyết U03 (`probeOldDriveTokenDoesNotMatchNewProcessCounters` - P2).
- **H04a & H04b:** Giải quyết U04 (`probeSyncActionMustRecheckSessionAtClick` - P2).

## 6. Runtime chưa chạy & Giới hạn
- Không có thiết bị ADB được kết nối; Google Play app signing, Google Play Services OAuth dialogs thật, và Android OS process kill thực tế: **NOT RUN**.
- Không coi fake provider hoặc hàm test harness là bài kiểm tra Google SDK thật.

## 7. Điểm dừng gói H00
Hoàn tất gói H00 theo đúng chỉ dẫn: Đã thiết lập bộ regression test lâu dài `VipLoginRound4RegressionTest.kt` với 4 ca đỏ tái hiện chính xác 4 lỗi U01–U04 và 3 controls xanh. Không sửa production. Dừng, không làm H01.
