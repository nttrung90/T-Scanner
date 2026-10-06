# BÁO CÁO GÓI J04 — NGHIỆM THU TOÀN DIỆN VÀ BÀN GIAO (FINAL ACCEPTANCE & HANDOVER)

Ngày thực hiện: 25/09/2026.  
Phạm vi: Gói J04 theo kế hoạch [PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md) và kết quả rà soát [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md).

---

## 1. Invariants (Bất biến duy trì)

1. **Tuân thủ quy trình tuần tự:** Thực hiện nghiêm ngặt thứ tự `J00 → J01 → J02 → J03 → J04`. Mỗi gói kiểm tra và nghiệm thu độc lập trước khi sang gói tiếp theo.
2. **Không sửa đổi cấu hình hạ tầng:** Giữ nguyên SDK versions, Gradle dependencies, OAuth credentials, keystores, và Google Play Billing.
3. **Bảo toàn probe và working tree:** Không git reset/clean, giữ nguyên thư mục `build/vip-login-reaudit5/` chứa baseline probe và kết quả kiểm toán độc lập.
4. **Trung thực về môi trường thử nghiệm:** Báo cáo rõ ràng ranh giới giữa kiểm thử máy chủ lưu trữ (Host JVM) và thiết bị thật; không khẳng định "an toàn tuyệt đối" hay suy đoán nguyên nhân khi chưa có dữ liệu runtime từ Play Console / thiết bị vật lý.
5. **Không làm mềm assertion:** Tất cả assertions gọi trực tiếp mã production, kiểm tra đúng điều kiện biên kỹ thuật.

---

## 2. Tổng kết tiến trình khắc phục 5 gói (J00 — J04)

| Gói | Nội dung thực hiện | Trạng thái kỹ thuật | Kết quả kiểm thử |
| :--- | :--- | :--- | :--- |
| **J00** | Đưa 4 probe F01/F02 thành regression suite chính thức; đính chính kết luận H05. | Baseline ghi nhận 4 lỗi: F01a, F01b, F02-1, F02-2. | 1 Pass (Control), 4 Fail (Đỏ đúng baseline). |
| **J01** | Tái cấu trúc `LogoutCoordinator` thành máy trạng thái theo phiên (`OperationRecord`), theo dõi độc lập Task SDK và Coroutine Scope. | Khắc phục triệt để F01a, F01b tại tầng coordinator. Khóa đa luồng multi-waiter re-eval loop. | 3 Pass, 2 Fail (F02 vẫn đỏ theo kế hoạch). Unit test `LogoutCoordinatorTest` 7/7 Pass. |
| **J02** | Kết nối `GoogleSignInClient.signOut()` Task vào `LogoutCoordinator` trong `AppAuthManager.kt`. Bao bọc `NonCancellable` cho UI callback. Bổ sung post-wait check. | Khắc phục ranh giới bất đồng bộ Play Services Task và Coroutine lifecycle. | Suite tích hợp `AppAuthGoogleLogoutIntegrationTest` 5/5 Pass. Các suite login liên quan đều xanh. |
| **J03** | Đồng nhất tiêu chuẩn định danh attempt token (F02); loại bỏ whitelist epoch rỗng; lưu và phục hồi `processEpoch` trên cả 3 UI Hosts (`MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`). | Khắc phục triệt để F02-1 và F02-2. Token tái tạo khác epoch hoặc rỗng bị từ chối 100%. | `VipLoginRound5RegressionTest` **5/5 Pass (Xanh toàn bộ)**. `GoogleLoginAttemptSerializationTest` 10/10 Pass. |
| **J04** | Nghiệm thu toàn diện: Chạy lại toàn bộ test suite (`--rerun`), phân tích tĩnh Lint (`lintDebug`), và đóng gói (`assembleDebug`). | Hệ thống đạt trạng thái sản xuất hoàn chỉnh, không lỗi biên dịch, không lỗi test, không lint error. | **718 / 718 tests Pass (0 Fail, 0 Skip)**. Lint 0 error. Build SUCCESSFUL. |

---

## 3. Lệnh kiểm thử nghiệm thu toàn diện, Exit Code và Test Count

### 3.1. Lệnh thực thi nghiệm thu
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

### 3.2. Kết quả thực thi
- **Thời gian chạy:** 2 phút 46 giây.
- **Exit Code:** `0` (BUILD SUCCESSFUL).
- **Số tác vụ Gradle:** 54 actionable tasks (12 executed, 42 up-to-date).

### 3.3. Thống kê Unit Test toàn dự án (`:app:testDebugUnitTest --rerun`)
- **Tổng số tập tin test:** **87** test suites.
- **Tổng số test cases:** **718** tests.
- **Passed (Thành công):** **718** (100%).
- **Failures (Thất bại):** **0**.
- **Errors (Lỗi runtime):** **0**.
- **Skipped (Bỏ qua):** **0**.

### 3.4. Kết quả phân tích tĩnh Android Lint (`:app:lintDebug`)
- **Báo cáo HTML:** `app/build/reports/lint-results-debug.html`
- **Báo cáo SARIF:** `app/build/reports/lint-results-debug.sarif`
- **Lint Errors:** **0 errors**.
- **Lint Warnings:** 22 warnings (không ảnh hưởng đến runtime, liên quan đến phiên bản thư viện/resource gợi ý).

### 3.5. Kết quả đóng gói (`:app:assembleDebug`)
- **Tập tin đầu ra:** `app/build/outputs/apk/debug/app-debug.apk`
- **Dung lượng APK:** **32,124,522 bytes** (~30.6 MB).
- **Thời điểm tạo:** 25/09/2026 23:47:46.

---

## 4. Phân loại 4 nhóm nghiệm thu kỹ thuật (Classification)

Để bảo đảm tính trung thực tuyệt đối giữa môi trường kiểm thử giả lập và thiết bị thực tế, toàn bộ hệ thống được phân định rõ ràng theo 4 danh mục chuẩn:

### 4.1. PROBE PASS (Đã kiểm chứng bằng probe thực tế trên Host)
- **F01a (Provider Task Timeout/Cancellation):** Được kiểm chứng qua probe `pendingProviderTaskSurvivesCoroutineCompletion`. Khi coroutine cleanup hoàn tất hoặc bị hủy mà provider Task của Play Services vẫn chưa trả lời, coordinator giữ trạng thái chờ đăng xuất và từ chối mở đăng nhập cho đến khi SDK Task thật sự hoàn tất.
- **F01b (Replaced Logout Release):** Được kiểm chứng qua probe `replacedLogoutMustNotReleaseOldWaiter`. Khi một thao tác logout mới thay thế thao tác logout cũ đang chạy, completion trễ của thao tác cũ không thể cấp quyền đăng nhập cho waiter.
- **F02-1 (Epoch-less Restored Token):** Được kiểm chứng qua probe `legacyRestoredLoginTokenMustNotMatchNewProcess`. Token phục hồi không có epoch hoặc epoch rỗng bị từ chối tuyệt đối trước attempt mới của tiến trình mới.
- **F02-2 (Old Epoch Cancellation Protection):** Được kiểm chứng qua probe `oldEpochCancellationMustNotClearNewAttempt`. Lệnh hủy mang token từ epoch tiến trình cũ không thể xóa active attempt hợp lệ của tiến trình mới.
- **Bộ probe lịch sử:** T01–T04 (Vòng 3), U01–U04 (Vòng 4) đều duy trì 100% tỷ lệ pass.

### 4.2. CODE VERIFIED (Đã xác minh qua phân tích mã nguồn và cấu trúc)
- **Máy trạng thái `LogoutCoordinator`:** Sử dụng bản ghi phiên `OperationRecord`, khóa đồng bộ `coordinatorLock`, bộ đếm tác vụ provider `providerPendingCount`, và vòng lặp tái đánh giá điều kiện cho multi-waiters.
- **Bộ tương thích `GoogleSignOutProvider`:** Tách biệt rõ ràng giữa ranh giới Coroutine Scope của ứng dụng và Google Play Services `Task<Void>`. Đăng ký `directExecutor` để bảo đảm callback phản hồi tức thời trên cả JVM và Android.
- **An toàn giao diện người dùng:** Bao bọc lệnh gọi `onComplete()` trong `withContext(NonCancellable)` trong khối `finally` của job cleanup, bảo đảm giao diện không bị treo hoặc nuốt sự kiện khi Scope bị hủy.
- **Bảo toàn trạng thái qua vòng đời Host:** Đồng nhất `GoogleLoginAttempt.writeToBundle()` và `GoogleLoginAttempt.fromBundle()` trên cả `MoreFragment`, `PdfViewerActivity`, và `IdCardComposeActivity`.

### 4.3. DEVICE PASS (Kiểm chứng trên thiết bị vật lý)
- **N/A (Chưa thực hiện trong phiên hiện tại):** Phiên làm việc diễn ra trên môi trường máy chủ phát triển (Windows Host / JVM Unit Test Runner), không kết nối thiết bị vật lý Android qua ADB.

### 4.4. NOT RUN (Chưa chạy / Ngoài phạm vi Host)
- **Google Play Console Release:** Chưa đưa bản dựng lên Play Console nội bộ / closed testing.
- **Play App Signing Fingerprint:** Xác minh SHA-1 certificate fingerprint trên Google Cloud Console đối chiếu với Play App Signing key của Google Play chưa được kiểm tra runtime trên môi trường sản xuất.
- **Physical Low-Memory Process Death:** Quá trình hệ điều hành Android thực sự giải phóng toàn bộ tiến trình do thiếu RAM (`ActivityManagerService.killApplicationProcess`) và phục hồi lại từ `savedInstanceState` cấp hệ thống chưa được chạy trên thiết bị phần cứng thực tế (mới được mô phỏng qua JVM identity và counter reset).

---

## 5. Kết luận bàn giao

1. **Hai nhóm lỗi F01 và F02 đã được đóng hoàn toàn:**
   - Mã nguồn production tại `AppAuthManager`, `LogoutCoordinator`, `GoogleLoginAttempt`, và 3 UI Hosts đã được chỉnh sửa chuẩn xác, tối thiểu và an toàn.
   - Toàn bộ 4 probe regression Round 5 từ trạng thái Thất bại (Đỏ) ở J00 đã chuyển sang Thành công (Xanh) ở J03 và tiếp tục được khẳng định vững chắc ở J04.
2. **Không có bất kỳ hồi quy nào:** Toàn bộ 718 bài test của toàn bộ dự án `:app` vượt qua thành công 100%. Phân tích tĩnh Lint không có lỗi. Bản dựng APK debug đóng gói thành công.
3. **Quy trình kết thúc:** Hoàn tất toàn bộ chuỗi gói `J00 → J01 → J02 → J03 → J04` theo đúng cam kết kế hoạch.
