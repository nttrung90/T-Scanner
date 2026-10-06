# BÁO CÁO GÓI H05 — TỔNG HỢP VÒNG 4 (HOST, REGRESSION & THIẾT BỊ)

## 1. Kết quả thực thi lệnh kiểm thử & tiêu chuẩn chất lượng (Host Verification)

- **Lệnh thực thi toàn bộ:**
  ```powershell
  $env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
  ```
- **Kết quả Unit Tests:**
  - **Tổng số tests thực tế:** **691 tests** (Baseline sau vòng 3: 668 tests; vòng 4 đã bổ sung **23 tests mới**).
  - **Thất bại (Failures):** 0
  - **Lỗi (Errors):** 0
  - **Bỏ qua (Skipped):** 0
  - **Trạng thái:** **100% BUILD SUCCESSFUL** (9s).
- **Kết quả Lint (`:app:lintDebug`):**
  - **Errors:** 0
  - **Warnings:** 728 (Đúng tuyệt đối theo baseline của dự án).
  - **Trạng thái:** **BUILD SUCCESSFUL** (2m 44s).
- **Kết quả Build (`:app:assembleDebug`):**
  - **Trạng thái:** **BUILD SUCCESSFUL** (16s).

---

## 2. Tổng kết nghiệm thu 4 phạm vi vòng 4 (U01 – U04)

| Mã lỗi | Mức độ | Mô tả lỗi | Gói sửa | Kết quả Probe / Host | Phân loại |
|---|---|---|---|---|---|
| **U01** | P1 | Cleanup provider đã bắt đầu vẫn chồng lấn với login mới | H01 | Probe U01 + 4 regression tests xanh (`LogoutCoordinator`) | **PROBE PASS / CODE VERIFIED** |
| **U02** | P2 | Drive callback thiếu token vẫn mượn pending request từ singleton | H02 | Probe U02 + 2 regression tests xanh (Explicit Token Contract) | **PROBE PASS / CODE VERIFIED** |
| **U03** | P2 | Counter reset làm token cũ trùng request mới giữa các process | H03 | Probe U03 + 3 regression tests xanh (`processEpoch` UUID) | **PROBE PASS / CODE VERIFIED** |
| **U04** | P2 | Snackbar của phiên trước còn thực thi khi người dùng bấm | H04a, H04b | Probe U04 + 7 regression tests xanh (Tap-time validation & caller origin) | **PROBE PASS / CODE VERIFIED** |

---

## 3. Rà soát chi tiết kiến trúc & các điểm cải thiện vòng 4

### 3.1. Tuần tự hóa Đăng nhập & Dọn dẹp Provider (`LogoutCoordinator` - U01)
- **Vấn đề trước sửa:** Khi người dùng A đăng xuất, coroutine provider cleanup chạy bất đồng bộ. Nếu người dùng B đăng nhập trong lúc cleanup của A bị tạm dừng/treo, tài khoản B commit trước; sau đó cleanup của A tiếp tục chạy các phương thức hủy diệt (`clearCredentialState`, `GoogleSignIn.signOut`), đe dọa phiên làm việc mới của B.
- **Đã khắc phục:**
  - Xây dựng `LogoutCoordinator` quản lý bảng trạng thái (`IDLE` ↔ `CLEANING_PROVIDER`).
  - Trong `signInWithGoogle`: Bổ sung **Step 0** gọi `LogoutCoordinator.awaitProviderCleanup()`. Yêu cầu đăng nhập mới tự động tạm dừng (suspend) chờ provider cleanup của phiên cũ hoàn tất trước khi gọi SDK lấy credential hay commit tài khoản.
  - Xử lý timeout an toàn: Nếu provider Task vẫn pending quá thời gian timeout, không mở khóa ngầm (`silently unlock`) mà từ chối đăng nhập kèm thông báo UI rõ ràng ("Đang hoàn tất đăng xuất tài khoản trước, vui lòng thử lại sau.").
  - Xử lý ngoại lệ chuẩn: Rethrow `CancellationException` đúng quy tắc structured concurrency; `invokeOnCompletion` bảo đảm giải phóng coordinator ngay cả khi coroutine scope bị hủy trước khi chạy.

### 3.2. Bắt buộc Explicit Token cho Google Drive Result (U02)
- **Vấn đề trước sửa:** `handleDrivePermissionResult` fallback `attempt ?: pendingDriveAuthAttempt.getAndSet(null)` cho phép callback thiếu token hoặc duplicate callback mượn và tiêu thụ request đang active của host khác.
- **Đã khắc phục:**
  - Xóa bỏ hoàn toàn fallback singleton. Callback có `attempt == null` lập tức bị discard trước khi chạm vào registry hay parser.
  - Các overload tương thích cũ được đánh dấu `@Deprecated` và truyền `attempt = null`.
  - Toàn bộ các host UI (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`) capture explicit attempt token khi khởi chạy và truyền vào callback.

### 3.3. Định danh Request bất biến qua chu kỳ Process (`processEpoch` - U03)
- **Vấn đề trước sửa:** Các bộ đếm `requestId` (0) và `sessionGeneration` (1) khởi tạo lại từ đầu sau process death. Token cũ từ process trước (trong `savedInstanceState`) có thể vô tình trùng với bộ giá trị của request mới sinh ra trong process mới.
- **Đã khắc phục:**
  - Bổ sung `processEpoch: String` (UUID ngẫu nhiên duy nhất cho mỗi process) vào `DriveAuthorizationAttempt`, `GoogleLoginAttempt` và registry của `AppAuthManager`.
  - Trong `handleDrivePermissionResult`: Bắt buộc `attempt.processEpoch == processEpoch`. Token từ process cũ bị từ chối an toàn, không thể tiêu thụ hay hủy request của process mới.
  - Chính sách tương thích ngược: Token cũ thiếu `processEpoch` (chuỗi rỗng) được discard an toàn, không gây crash và cho phép người dùng retry tạo request mới.

### 3.4. Kiểm tra hợp lệ tại thời điểm Click & Nối đầy đủ Origin (U04)
- **Vấn đề trước sửa:** `SyncResultPresenter` chỉ kiểm tra session generation lúc render; khi người dùng tap action trên Snackbar sau khi đã đổi phiên hoặc sau khi Fragment view bị hủy, action callback vẫn thực thi sai trái.
- **Đã khắc phục:**
  - Bổ sung kiểm tra đa lớp tại thời điểm click (**Tap-Time Verification**): Đối chiếu lại session generation hiện tại, user ID hiện tại, và predicate `isHostValid()` của host view. Action từ phiên cũ hoặc trên view đã mất bị hủy bỏ ngay khi tap.
  - Đảm bảo click action chỉ gọi callback đúng 1 lần (`AtomicBoolean(false).compareAndSet(false, true)`).
  - Xóa `lastPresentedKey = null` khi tap action để kết quả của lượt retry hiển thị ngay lập tức, không bị debounce chặn.
  - Kết nối đầy đủ tại toàn bộ 5 caller UI (`HomeFragment`, `MoreFragment`, `PdfViewerActivity`, `IdCardComposeActivity`, `VipUpgradeDialog`): Capture `startUser` và `startGen` khi bắt đầu đồng bộ, truyền vào `SyncResultPresenter.present` cùng Fragment view lifecycle guard.

---

## 4. Nghiệm thu trên Thiết bị và Google Play Track

| Kịch bản kiểm thử | Hành vi kỳ vọng | Phân loại | Ghi chú |
|---|---|---|---|
| Đăng xuất A và bấm đăng nhập B ngay lập tức | Provider cleanup A hoàn tất trước khi B commit; không chồng lấn | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe U01; thiết bị thực tế: **NOT RUN** |
| Phân quyền Google Drive với callback không token | Bị loại bỏ an toàn, không chiếm đoạt request của host khác | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe U02; thiết bị thực tế: **NOT RUN** |
| Khôi phục Drive attempt sau khi process restart | Token từ process cũ bị từ chối; cho phép retry tạo token mới | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe U03; thiết bị thực tế: **NOT RUN** |
| Bấm nút "Cấp quyền" / "Thử lại" sau khi đổi tài khoản | Action bị loại bỏ tại thời điểm bấm, không kích hoạt nhầm | **PROBE PASS / CODE VERIFIED** | Đã nghiệm thu probe U04; thiết bị thực tế: **NOT RUN** |
| Bấm nút "Thử lại" sau khi Fragment view bị hủy/chuyển tab | Action bị loại bỏ an toàn, không rò rỉ view hoặc gọi callback lỗi | **CODE VERIFIED** | Đã nghiệm thu integration test; thiết bị thực tế: **NOT RUN** |
| Cài đặt bản release APK/AAB qua Play Internal App Sharing | Đăng nhập thành công không văng lỗi 10 (DEVELOPER_ERROR) | **NOT RUN** | **Yêu cầu SHA-1 Play App Signing và thiết bị thực tế** |

### Trạng thái Google Play Console & Thiết bị:
> [!NOTE]
> **ĐÍNH CHÍNH (Audit vòng 5 - 25/09/2026):**
> Kết luận trước đây cho rằng mã nguồn "an toàn tuyệt đối trên Host" và khẳng định nguyên nhân lỗi đăng nhập Play "100% do thiếu SHA-1" đã bị thay thế bởi kết quả rà soát vòng 5 (chi tiết xem [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md)).
> - Kiểm tra thực tế trên Google Play Console và thiết bị thật: **NOT RUN**.
> - Không có bằng chứng runtime/trace từ Console hay thiết bị để xác nhận nguyên nhân lỗi đăng nhập trên Play, và không bổ sung kết luận nguyên nhân khác theo suy đoán.
> - Khi phát sinh lỗi thực tế trên kênh phân phối/thiết bị, cần thu thập chính xác status code, artifact/version cài đặt, package name, OAuth client ID và chứng thư ký tương ứng để đối chiếu.

---

## 5. Danh mục báo cáo đối chiếu các gói vòng 4
- [H00 — Giữ bốn regression mới vòng 4](REPORT_VIP_LOGIN_ROUND4_H00.md)
- [H01 — Serialize login với provider cleanup (U01/P1)](REPORT_VIP_LOGIN_ROUND4_H01.md)
- [H02 — Drive result bắt buộc token host (U02/P2)](REPORT_VIP_LOGIN_ROUND4_H02.md)
- [H03 — Identity không lặp giữa process (U03/P2)](REPORT_VIP_LOGIN_ROUND4_H03.md)
- [H04a — Guard action tại thời điểm bấm (U04/P2, presenter)](REPORT_VIP_LOGIN_ROUND4_H04a.md)
- [H04b — Nối origin và lifecycle từ tất cả caller UI](REPORT_VIP_LOGIN_ROUND4_H04b.md)
- [H05 — Nghiệm thu tổng hợp và điểm dừng](REPORT_VIP_LOGIN_ROUND4_H05.md)

---

## 6. Điểm dừng
Toàn bộ kế hoạch vòng 4 (từ H00 đến H05) tại thời điểm thực hiện đã hoàn tất các hạng mục U01–U04 với 691 unit tests xanh, lint 0 errors / 728 warnings, assembleDebug thành công. Các phát hiện mới F01, F02 và đính chính F03 được tiếp tục xử lý trong Vòng 5 qua J00–J04 (xem [RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md)).
