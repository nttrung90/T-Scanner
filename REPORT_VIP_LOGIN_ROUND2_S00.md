# Báo cáo Nghiệm thu S00 — Harness Probe Chính thức (VIP/Login Vòng 2)

**Ngày thực hiện:** 2026-09-25  
**Gói thực hiện:** S00 — Chuyển 4 probe recheck vào test suite chính thức của repository và thiết lập baseline  
**Trạng thái kết thúc:** Hoàn tất S00, dừng trước S01.  

---

## 1. Mục tiêu và phạm vi gói S00

Theo kế hoạch `PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md`:
- Chuyển 4 probe từ `build/vip-login-reaudit/VipLoginReauditProbeTest.kt` vào suite chính thức tại `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt`.
- Bổ sung 2 ca kiểm soát dương tính (positive controls) để chứng minh test harness và API production hoạt động đúng khi tham số hợp lệ, loại trừ khả năng test harness luôn fail.
- **Quy tắc bất biến:** Tuyệt đối không chỉnh sửa mã nguồn production trong gói S00.

---

## 2. Bảng đối chiếu kế hoạch vs File thực tế

| Yêu cầu kế hoạch S00 | File thực tế | Hành động | Trạng thái |
|---|---|---|---|
| Chuyển 4 probe R01–R04 vào suite unit test | `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt` | Tạo mới | Hoàn thành |
| Thêm positive controls cho Login và Drive | `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt` | Tạo mới trong cùng class | Hoàn thành |
| Không sửa production code | `app/src/main/...` | Giữ nguyên | Hoàn thành (0 file production bị sửa) |
| Lập báo cáo baseline S00 | `REPORT_VIP_LOGIN_ROUND2_S00.md` | Tạo mới | Hoàn thành |

---

## 3. Kết quả chi tiết 6 ca kiểm thử trong `VipLoginRound2RegressionTest`

| Tên test | Mục đích / Bug ID | Kết quả | Chi tiết Assertion / Nguyên nhân |
|---|---|---|---|
| `probeCancelledScopeMustNotLockFutureSignIn` | R02 (P2): Scope bị huỷ trước khi coroutine chạy làm khoá đăng nhập vĩnh viễn | **FAILED** (Đỏ) | `java.lang.AssertionError: Cancelled scope must not leave global login locked at VipLoginRound2RegressionTest.kt:181` |
| `probeSessionInvalidationMustRejectPendingCredential` | R01 (P1): Vô hiệu hoá phiên/đăng xuất trong lúc đang chờ credential nhưng credential cũ vẫn phục hồi user | **FAILED** (Đỏ) | `java.lang.AssertionError: Invalidated credential must not restore a logged-in account expected null, but was:<UserProfile...> at VipLoginRound2RegressionTest.kt:190` |
| `probeSuccessCallbackFailureMustNotRestartLogin` | R03 (P2): Callback UI hậu đăng nhập ném ngoại lệ biến thành lỗi provider và kích hoạt fallback login | **FAILED** (Đỏ) | `java.lang.AssertionError: Successful authentication must not fallback because its UI callback failed expected:<0> but was:<1> at VipLoginRound2RegressionTest.kt:224` |
| `probeDriveResultWithoutPendingRequestMustNotSucceed` | R04 (P2): Kết quả Drive không có request tương ứng (hoặc replay) vẫn được cấp quyền đồng bộ | **FAILED** (Đỏ) | `java.lang.AssertionError: Uncorrelated or duplicate Drive result must not authorize a sync expected:<0> but was:<1> at VipLoginRound2RegressionTest.kt:252` |
| `controlValidSignIn_succeedsAndUpdatesUser` | Kiểm soát dương tính: Đăng nhập credential hợp lệ cập nhật user thành công | **PASSED** (Xanh) | Chạy thành công, user được lưu, UI callback thành công |
| `controlValidDriveAuthorization_withPendingRequest_succeeds` | Kiểm soát dương tính: Drive result có pending session hợp lệ được cấp quyền | **PASSED** (Xanh) | Chạy thành công, `onSuccess` được kích hoạt với snapshot khớp |

---

## 4. Lệnh kiểm chứng và kết quả đầu ra

```powershell
$env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
```

- **Exit code:** 1 (kết quả mong đợi do 4 probe regression đang tái hiện lỗi thực tế)
- **Tổng số test:** 6 completed
- **Số test Pass:** 2 (các ca control)
- **Số test Fail:** 4 (đúng 4 probe R01, R02, R03, R04)
- **Thời gian chạy:** 21s

---

## 5. Đánh giá rủi ro và các bước tiếp theo

1. **R01–R04 được tái hiện chuẩn xác** trên cây mã chính thức và gọi trực tiếp các phương thức production của `AppAuthManager` (`signInWithGoogle`, `handleDrivePermissionResult`).
2. **Kế hoạch giải quyết tuần tự:**
   - **S01:** Xử lý R01 (Token yêu cầu đăng nhập và vô hiệu hóa phiên).
   - **S02:** Xử lý R02 (Cleanup khóa kể cả coroutine chưa chạy).
   - **S03:** Xử lý R03 (Không biến lỗi hậu đăng nhập thành lỗi provider).
   - **S04:** Xử lý R04 (Drive authorization có request ID và consume một lần).
3. **Phạm vi thiết bị:** Chưa chạy trên thiết bị vật lý / Google Play Services thực tế.

---

## 6. Khối bàn giao bắt buộc

```text
Gói / baseline: S00 — Thiết lập baseline harness chính thức cho VIP/Login Vòng 2 (R01–R04)
File có thay đổi sẵn và file vừa sửa:
  - File tạo mới: app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt, REPORT_VIP_LOGIN_ROUND2_S00.md
  - File production: Giữ nguyên 100%, không chỉnh sửa mã nguồn production.
Production path thực sự được test: AppAuthManager.signInWithGoogle, AppAuthManager.handleDrivePermissionResult
Trước sửa: 4 probe tái hiện lỗi R01, R02, R03, R04 thất bại tại build/vip-login-reaudit
Sau sửa:
  - Command: $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"; .\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
  - Exit code: 1 (mong đợi khi giữ nguyên regression chưa sửa)
  - Counts: 6 completed, 4 failed (R01, R02, R03, R04), 2 passed (positive controls)
  - Report: REPORT_VIP_LOGIN_ROUND2_S00.md
Regression dự kiến còn đỏ thuộc gói sau:
  - R01 (sẽ sửa ở S01)
  - R02 (sẽ sửa ở S02)
  - R03 (sẽ sửa ở S03)
  - R04 (sẽ sửa ở S04)
Chưa chạy thiết bị/Play/Drive: Chưa chạy trên thiết bị thật và runtime Google Play Services/Drive API; kiểm thử đang chạy mock parser/fake prefs trên JVM unit test.
Phụ thuộc và điểm dừng: Đã hoàn tất S00. Dừng trước S01, chờ lệnh tiếp tục.
```
