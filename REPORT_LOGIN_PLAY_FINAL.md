# Báo cáo tổng kết: Xử lý sự cố Đăng nhập Google trên bản Play Store
*Thời gian hoàn tất: 03/10/2026 09:42 ICT*

---

## 1. Kết luận nguyên nhân gốc rễ (Root Cause Analysis)

### 1.1. Nguyên nhân bên ngoài (External Root Cause)
- **Bản chất:** **Lệch chữ ký OAuth giữa Upload Key và Google Play App Signing Key** (`CONFIG_MISMATCH_PLAY_SIGNING`).
- **Trạng thái:** `UNCONFIRMED_PENDING_CONSOLE` (Cần quản trị viên kiểm tra Google Cloud Console để khóa 100%).
- **Cơ chế:**
  - Bản local AAB mang chữ ký của Upload Key (`SHA-1: FF:CA:87:B4:37:E9:9E:DF:24:F9:13:85:BA:8E:39:F3:F0:AA:9C:E7`). Khi cài trực tiếp APK tạo từ khóa này, Android OAuth Client trong Google Cloud khớp chữ ký nên đăng nhập thành công.
  - Khi tải từ Google Play Store, Google Play tự động ký lại ứng dụng bằng **Google Play App Signing Key** (có SHA-1 khác).
  - Khi ứng dụng gọi `CredentialManager.getCredential()`, Google Play Services kiểm tra tuple `com.tscanner.app` + `Play App Signing SHA-1` trong Google Cloud Project `284912111014`. Nếu chưa được đăng ký, Google Play Services từ chối cấp token và đóng giao diện chọn tài khoản.

### 1.2. Nguyên nhân nội tại trong code (Internal UX / Diagnostics Vulnerability)
- **Bản chất:** **Nuốt ngoại lệ Cancellation và thiếu log chẩn đoán trên bản Release** (`PROVIDER_CANCELLATION_SWALLOWING`).
- **Trạng thái:** `CONFIRMED_CODE_DEFECT` (Đã sửa và kiểm chứng qua 42/42 tests Auth).
- **Cơ chế gây ra triệu chứng "chọn Gmail xong im lặng":**
  - Khi Google Play Services từ chối cấp quyền và đóng bottom sheet, SDK AndroidX ném ra `GetCredentialCancellationException`.
  - Trong `AppAuthManager.kt`, khối `catch (e: GetCredentialCancellationException)` gán nhãn cứng `Log.d("User cancelled...")`, không ghi nhận `type`/`message` ngoại lệ và không kích hoạt fallback.
  - Trong `MoreFragment.kt`, callback `onCancelled` chỉ xóa cờ pending mà không có bất kỳ phản hồi nào lên UI.
  - Kết quả: Người dùng thấy bottom sheet biến mất và không có bất kỳ thông báo hay lỗi nào hiển thị.

---

## 2. Giải pháp: Config-Only vs Code-Fix

| Loại giải pháp | Phạm vi | Tác động | Yêu cầu phát hành bản mới? |
|---|---|---|---|
| **Cấu hình (Config-Only)** | Google Cloud Console + Google Play Console | **Khôi phục ngay lập tức** tính năng đăng nhập trên chính bản cài đặt hiện hành từ Play Store mà không cần cập nhật app. | **KHÔNG** |
| **Mã nguồn (Code-Fix)** | `AppAuthManager`, `GoogleCredentialRequestFactory`, `GoogleSignInResultRouter`, `MoreFragment` | Ghi log chẩn đoán `[AuthLifecycle]` trên Release; khử nhạy cảm PII; bắt lỗi parse bundle an toàn; giải phóng trạng thái bận cho phép retry thủ công ngay lập tức; không auto-fallback khi cancel. | **CÓ** (Bản phát hành sau) |

---

## 3. Các thay đổi mã nguồn đã thực hiện

1. **`app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`:**
   - Thêm `sanitizeForLog` (redact email, token JWT, Bearer header) và `sanitizeIdForLog`.
   - Chuẩn hóa ghi log có cấu trúc qua các stage: `REQUEST`, `PROVIDER`, `COMMIT`, `FALLBACK_LAUNCH`, `STALE_REJECTION`, `UI`.
   - Xử lý `GetCredentialCancellationException`: Ghi log `Log.i` (đọc được trên release), xóa sạch attempt bận, gọi `onCancelled()`, tuyệt đối không tự fallback/retry.
   - Bảo toàn nguyên nhân gốc ban đầu (`providerExceptionCause`) khi kích hoạt Intent fallback cho lỗi kỹ thuật.

2. **`app/src/main/java/com/tscanner/app/utils/GoogleCredentialRequestFactory.kt`:**
   - Bổ sung khối try-catch an toàn trong `DefaultGoogleCredentialClient` quanh `GoogleIdTokenCredential.createFrom(credential.data)`.
   - Ghi log có cấu trúc cho giai đoạn `PARSE`: `SUCCESS`, `FAILURE`, `UNSUPPORTED_TYPE`.

3. **`app/src/main/java/com/tscanner/app/utils/GoogleSignInResultRouter.kt`:**
   - Chuẩn hóa log `[AuthLifecycle] stage=FALLBACK_RESULT` và `stage=UI`.
   - Áp dụng `sanitizeForLog` cho các lỗi `ApiException` (bao gồm lỗi OAuth Developer Error 10 và 12500).

4. **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:**
   - Ghi log phát sự kiện `UI status=MORE_FRAGMENT_ON_CANCELLED` và `MORE_FRAGMENT_ON_ERROR`.
   - Đảm bảo `pendingSignInAttempt = null` được dọn dẹp sạch sẽ trong mọi nhánh kết thúc để UI không bao giờ bị kẹt trạng thái bận.

5. **`app/src/test/java/com/tscanner/app/AppAuthLifecycleDiagnosticsTest.kt`:**
   - Tạo bộ test mới gồm 11 bài test kiểm chứng: Khử nhạy cảm, không auto-fallback khi cancel, retry thủ công ngay lập tức, fallback 1 lần khi lỗi kỹ thuật, lifecycle cancel, stale rejection, debouncing, và routing lỗi OAuth 10 / 12500 / 12501.

---

## 4. Số liệu kiểm chứng thực tế trên Host

Lệnh thực thi chuẩn:
```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
$env:JAVA_HOME='C:/Users/nguye/.jdks/openjdk-21.0.1'
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
```

### 4.1. Bảng số liệu Unit Tests
- **Tổng số tests:** **994** (0 Failures, 0 Errors, 0 Skipped).
- **Core Auth & VIP suites:** **156/156 PASS 100%**:
  - `AppAuthLifecycleDiagnosticsTest`: 11 tests
  - `GoogleLoginFlowTest`: 7 tests
  - `GoogleCredentialRequestFactoryTest`: 4 tests
  - `GoogleSignInResultRouterTest`: 13 tests
  - `AppAuthCanonicalIdentityTest`: 7 tests
  - `AppAuthGoogleLogoutIntegrationTest`: 8 tests
  - `VipLoginContinuationTest`: 15 tests
  - `VipIdCardLoginContinuationTest`: 10 tests
  - `VipViewerLoginContinuationTest`: 11 tests
  - `VipLoginRound2RegressionTest` đến `Round6RegressionTest`: 70 tests

### 4.2. Phân tích tĩnh Lint (`lintDebug`)
- **Lint Errors:** **0**
- **Lint Warnings:** **753** (Không có vi phạm blocking).

### 4.3. Kiểm chứng biên dịch Release & R8 (`bundleRelease`)
- `./gradlew.bat :app:bundleRelease --offline --console=plain` -> **BUILD SUCCESSFUL in 4m 39s** (Exit code 0).
- `minifyReleaseWithR8` hoàn tất thành công, không gặp xung đột rules.
- **Candidate Bundle:** `app/build/outputs/bundle/release/app-release.aab` (17,417,947 bytes, SHA-256: `40CFDD46B1F28237A4A8BEB6B386E270BF41075E7A25A60D76CDA9B11133EE44`).

---

## 5. Những phần chưa chạy / Bị chặn bởi điều kiện bên ngoài (`BLOCKED_EXTERNAL`)

- **Kiểm thử trực tiếp trên thiết bị cài bản Google Play thật:** Hiện tại không có thiết bị hoặc máy ảo nào đang kết nối qua ADB (`adb devices -l` trả về danh sách trống).
- **Cấu hình trên Google Cloud Platform & Play Console:** Đòi hỏi quyền quản trị viên trên trình duyệt web.

---

## 6. DANH SÁCH DUY NHẤT CÁC VIỆC CẦN QUẢN TRỊ VIÊN THỰC HIỆN

Chỉ cần thực hiện theo các bước ngắn gọn sau để kích hoạt lại đăng nhập cho bản Play:

1. **Lấy SHA-1 của Play App Signing:**
   - Vào [Google Play Console](https://play.google.com/console) -> Chọn ứng dụng **T-Scanner** -> **App integrity** -> tab **App signing**.
   - Sao chép mã **SHA-1 certificate fingerprint** tại mục **App signing key certificate**. *(Lưu ý: Không lấy Upload key certificate)*.

2. **Thêm Android OAuth Client trên Google Cloud Console:**
   - Mở [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials).
   - Chọn đúng Project có Project Number **284912111014** (project chứa Web client ID `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d...`).
   - Nhấp **Create Credentials** -> **OAuth client ID**.
   - Chọn loại: **Android**.
   - Package name: `com.tscanner.app`.
   - SHA-1: Dán mã vừa sao chép từ Play Console ở Bước 1.
   - Nhấp **Create**. *(Giữ nguyên các client Android/Web cũ, không xóa)*.

3. **Kiểm tra màn hình đồng ý OAuth:**
   - Tại tab **OAuth consent screen**, kiểm tra:
     - User Type: **External**.
     - Publishing status: **In production** (để mọi người dùng Gmail công khai đều đăng nhập được).

4. **Kiểm tra kết quả trên điện thoại:**
   - Đợi khoảng 10–15 phút để Google Cloud cập nhật dữ liệu.
   - Mở ứng dụng T-Scanner hiện có trên máy (bản tải từ Play Store) và bấm Đăng nhập bằng Google.
   - **Xác nhận:** Chọn Gmail -> Màn hình đăng nhập hoàn tất -> Tên/ảnh profile cập nhật thành công.
