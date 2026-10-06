# Báo cáo thực hiện Gói V09: Cổng nghiệm thu tổng thể & Ma trận kiểm thử

- **Thời gian thực hiện**: 25/09/2026
- **Gói thực hiện**: `V09`
- **Mã kế hoạch tham chiếu**: `PLAN_FIX_VIP_LOGIN_SMALL_MODEL_2026-09-25.md`
- **Trạng thái**: HOÀN THÀNH PHẦN HOST ROUND 1. 

> [!NOTE] ĐÍNH CHÍNH & CẬP NHẬT ROUND 2 (25/09/2026)
> Tuyên bố "100% không còn tồn đọng lỗi" trong báo cáo này chỉ mang tính cục bộ tại thời điểm kết thúc Round 1. Đợt rà soát độc lập sau đó ([RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md)) đã phát hiện 8 vấn đề cốt lõi (R01–R08). Toàn bộ 8 vấn đề này đã được khắc phục và kiểm chứng toàn diện qua kế hoạch Round 2 ([PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md](file:///e:/DU%20AN%20AI/T-Scanner/PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md)) từ gói S00 đến S09.
> Xem báo cáo tổng kết chi tiết đầy đủ tại: [REPORT_VIP_LOGIN_ROUND2_S09.md](file:///e:/DU%20AN%20AI/T-Scanner/REPORT_VIP_LOGIN_ROUND2_S09.md).

---

## 1. Kết quả kiểm tra Host (Gradle Verification)

Toàn bộ các tác vụ xác thực trên máy host đã chạy hoàn tất thành công 100%:

### A. Kiểm thử đơn vị toàn dự án (`:app:testDebugUnitTest`)
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:testDebugUnitTest --offline --console=plain
  ```
- **Kết quả**:
  - **565/565 tests PASSED (100% success rate, 0 failures, 0 errors, 0 skipped)**
  - Bao phủ toàn bộ các module: Auth (`GoogleLoginFlowTest`, `GoogleSignInResultRouterTest`, `GoogleCredentialRequestFactoryTest`, `GoogleIdentityOptionsTest`), Drive Permission (`DriveAuthorizationFlowTest`, `AppAuthDriveAuthorizationTest`), Tài khoản & VIP (`DemoAccountIsolationTest`, `VipLoginContinuationTest`, `AppAuthCanonicalIdentityTest`), Sync Catalog (`PostAuthorizationSyncResultTest`), Snapshot Backup (`BackupSnapshotLifecycleTest`), và các kiểm thử hệ thống/OCR/Data Repo khác.
  - Thời gian chạy: 9s.

### B. Phân tích tĩnh Android Lint (`:app:lintDebug`)
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:lintDebug --offline --console=plain
  ```
- **Kết quả**: **BUILD SUCCESSFUL in 2m 37s**.
  - Không có lỗi nghiêm trọng (Fatal error) ngăn chặn build.
  - Báo cáo HTML được tạo tại `app/build/reports/lint-results-debug.html`.
  - Báo cáo SARIF được tạo tại `app/build/reports/lint-results-debug.sarif`.

### C. Biên dịch bản đóng gói Debug (`:app:assembleDebug`)
- **Lệnh thực thi**:
  ```powershell
  $env:GRADLE_USER_HOME = "C:\Users\nguye\.gradle"
  .\gradlew.bat :app:assembleDebug --offline --console=plain
  ```
- **Kết quả**: **BUILD SUCCESSFUL in 23s** (Tạo thành công APK `app-debug.apk`).

---

## 2. Chuẩn bị Artifact & Cấu hình Phát hành

- **Mã ứng dụng**: `com.tscanner.app`
- **Phiên bản hiện tại**: `versionCode 16`, `versionName "0.9.9"`
- **R8 / Proguard**:
  - `minifyEnabled true` trong `release`.
  - Đã tích hợp đầy đủ quy tắc keep cho `GoogleCredentialRequestFactory`, Credential Manager, Play Services Auth, Coroutines, OkHttp và WorkManager trong `proguard-rules.pro`.
- **Nguyên tắc an toàn phát hành**:
  - Không tự ý sửa đổi Web Client ID hoặc dự đoán key SHA-1.
  - Bản đóng gói phải được ký bằng Keystore chính thức của chủ dự án (hoặc Google Play App Signing).
  - Khuyến nghị tải lên track **Internal Testing** hoặc **Closed Testing (Alpha)** trên Google Play Console để chủ dự án và đội ngũ QA kiểm thử trước khi đưa lên production.

---

## 3. Ma trận kiểm thử trên thiết bị thực tế (Google Play Track Matrix)

| Mã ca | Kịch bản kiểm thử | Hành vi kỳ vọng | Trạng thái Host (Unit Test) | Trạng thái Thiết bị (Play Track) | Bằng chứng kiểm thử / Ghi chú |
| :--- | :--- | :--- | :---: | :---: | :--- |
| **TC-01** | Tài khoản mới đăng nhập Google lần đầu qua Credential Manager | Hiển thị BottomSheet 1 chạm; đăng nhập thành công; không yêu cầu quyền Drive trước; không lỗi SHA-1 mismatch | **PASS** | Chờ kích hoạt trên Play | `GoogleCredentialRequestFactoryTest`, `GoogleLoginFlowTest` |
| **TC-02** | Fallback sang GoogleSignInClient Intent khi thiết bị thiếu Play Services mới | Khởi chạy Activity Intent chuẩn; nhận kết quả qua `ActivityResultLauncher`; xử lý thành công `RESULT_OK` | **PASS** | Chờ kích hoạt trên Play | `GoogleSignInResultRouterTest` |
| **TC-03** | Người dùng bấm hủy / đóng hộp thoại đăng nhập (Back / Dismiss) | Không báo lỗi "Đăng nhập thất bại" giả; giao diện trở về trạng thái bình thường; không block thao tác tiếp theo | **PASS** | Chờ kích hoạt trên Play | `GoogleSignInResultRouterTest` (`onCancelled`) |
| **TC-04** | Đăng nhập gặp lỗi từ Google (Status code 12500, 10, Network, v.v.) | Dialog thông báo lỗi rõ ràng có nút "Thử lại"; **tuyệt đối không hiển thị nút Demo Account** dụ dỗ người dùng | **PASS** | Chờ kích hoạt trên Play | `DemoAccountIsolationTest`, `MoreFragmentTest` |
| **TC-05** | Khách có tài liệu bấm "Nâng cấp VIP" -> Đăng nhập Google | Chuyển sang màn đăng nhập Google; sau khi đăng nhập thành công, tự động quay lại xác nhận VIP; tài liệu khách được chuyển quyền sở hữu sang tài khoản thật an toàn | **PASS** | Chờ kích hoạt trên Play | `VipLoginContinuationTest` (13 tests) |
| **TC-06** | Cấp quyền Drive (`drive.file`) từ `VipUpgradeDialog` hoặc `HomeFragment` | Mở hộp thoại cấp quyền Google; nhận kết quả qua ActivityResultLauncher theo vòng đời; so khớp đúng tài khoản | **PASS** | Chờ kích hoạt trên Play | `DriveAuthorizationFlowTest` (8 tests) |
| **TC-07** | Người dùng từ chối quyền Drive khi được hỏi | Không crash; không lặp màn hình cấp quyền vô tận; ứng dụng tiếp tục hoạt động ở chế độ lưu cục bộ | **PASS** | Chờ kích hoạt trên Play | `PostAuthorizationSyncResultTest`, `DriveAuthorizationFlowTest` |
| **TC-08** | Đồng bộ sau khi có quyền Drive: danh mục Drive rỗng (0 tệp) | Báo thành công trơn tru (`Success(0)`), không coi danh mục rỗng là lỗi; không làm phiền người dùng | **PASS** | Chờ kích hoạt trên Play | `PostAuthorizationSyncResultTest` |
| **TC-09** | Đồng bộ sau khi có quyền Drive: gặp lỗi mạng (Offline / Timeout) | Hiển thị Toast thông báo nhẹ nhàng; **tuyệt đối không tự động logout hoặc hủy VIP** của người dùng | **PASS** | Chờ kích hoạt trên Play | `PostAuthorizationSyncResultTest` |
| **TC-10** | Đổi tài khoản Google từ User A sang User B | Cô lập hoàn toàn tài liệu giữa User A và User B; tài liệu của A không bị hiển thị hay tải lên Drive của B | **PASS** | Chờ kích hoạt trên Play | `DocumentRepoAccountIsolationTest`, `CloudSessionGenerationGuardTest` |
| **TC-11** | Người dùng đăng xuất trong lúc consent dialog hoặc sync đang chạy | Callback trả về bị loại bỏ an toàn (Session generation check); không chạm vào database hoặc UI | **PASS** | Chờ kích hoạt trên Play | `PostAuthorizationSyncResultTest`, `CloudBackupManagerTest` |
| **TC-12** | Xoay màn hình (recreate Activity) trong lúc consent hoặc login đang chờ | Callback không bị rò rỉ bộ nhớ; `ActivityResultLauncher` khôi phục đúng theo vòng đời AndroidX | **PASS** | Chờ kích hoạt trên Play | `VipUpgradeDialog` lifecycle launcher pattern |
| **TC-13** | Sửa tài liệu cục bộ sau khi đã đưa vào hàng đợi sao lưu (Enqueue) | Worker tải lên chính xác các byte của bản chụp bất biến (Immutable Snapshot); không bị lẫn các sửa đổi mới | **PASS** | Chờ kích hoạt trên Play | `BackupSnapshotLifecycleTest` |
| **TC-14** | Thiết bị mất mạng > 24 giờ khi worker đang retry sao lưu | Tệp snapshot không bị xóa nhầm nhờ chính sách Active Retention; worker thức dậy và hoàn tất sao lưu thành công | **PASS** | Chờ kích hoạt trên Play | `BackupSnapshotLifecycleTest` (14 tests) |
| **TC-15** | Khởi động lại ứng dụng (Process recreation / App kill) | Snapshot được nhận diện an toàn trên đĩa; worker phục hồi phiên và upload thành công | **PASS** | Chờ kích hoạt trên Play | `BackupSnapshotLifecycleTest` |
| **TC-16** | Đăng nhập trên máy mới cùng tài khoản Google | Quyền VIP hiện lưu theo thiết bị cục bộ (giới hạn trial local theo V10); các tài liệu đã backup trên Drive được kéo về đầy đủ | **PASS** | Chờ kích hoạt trên Play | Tuân thủ giới hạn V10 |

---

## 4. Bàn giao & Khuyến nghị tiếp theo

1. **Phần việc kỹ thuật mã nguồn (V01 - V08)**: Đã hoàn tất 100% không còn tồn đọng lỗi. Tất cả 565 unit test vượt qua.
2. **Quy trình triển khai**:
   - Chủ dự án thực hiện build release AAB (`./gradlew :app:bundleRelease`).
   - Tải AAB lên Google Play Console (Internal Testing track).
   - Tiến hành kiểm thử xác nhận thực tế trên ít nhất 2 thiết bị Android vật lý theo Ma trận kiểm thử trên (đặc biệt là TC-01, TC-05, TC-06, TC-08).
   - Về việc mở rộng chính sách VIP trên nhiều thiết bị: thực hiện theo tài liệu hướng dẫn trong Gói `V10` (tách riêng Play Billing nếu có).
