# Báo cáo L03: Chẩn đoán an toàn và trạng thái kết thúc login rõ ràng
*Thời gian thực hiện: 03/10/2026 09:27 ICT*

## 1. Phạm vi và Mục tiêu
- Triển khai ghi log chẩn đoán có cấu trúc (`AuthLifecycle`) cho toàn bộ các giai đoạn của quy trình đăng nhập Google.
- Đảm bảo log đọc được trên bản Release (mức `Log.i`, `Log.w`, `Log.e`), không phụ thuộc vào `Log.d`.
- Chuẩn hóa việc khử dữ liệu nhạy cảm (sanitization): tuyệt đối không để lộ token, email, bearer header trong logcat.
- Phân tách rõ ràng giữa:
  - Huỷ hợp lệ / Không xác định (`GetCredentialCancellationException`): reset trạng thái bận, cho phép người dùng retry thủ công ngay lập tức, **tuyệt đối không tự ý auto-fallback / auto-retry làm hiện lại chooser lần 2**.
  - Lỗi kỹ thuật (`NoCredentialException`, `GetCredentialException`, `Exception`): kích hoạt fallback sang Intent legacy tối đa 1 lần, bảo toàn nguyên nhân gốc ban đầu.
- Giữ nguyên tắc an toàn: Không thay đổi identity/VIP ownership, không nới lỏng session/owner guards.

## 2. Các file đã thay đổi

| File | Nội dung thay đổi |
|---|---|
| `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` | - Bổ sung hàm khử nhạy cảm `sanitizeForLog` (redact email, JWT token, Bearer auth) và `sanitizeIdForLog` (masking ID).<br>- Ghi log có cấu trúc qua các stage: `REQUEST`, `PROVIDER`, `COMMIT`, `FALLBACK_LAUNCH`, `STALE_REJECTION`, `UI`.<br>- Khi gặp `GetCredentialCancellationException`: ghi log `Log.i` kèm `type` và `reason` đã sanitize, reset busy state, gọi `onCancelled()`, không auto-fallback.<br>- Bảo toàn nguyên nhân gốc (`providerExceptionCause`) khi kích hoạt Intent fallback. |
| `app/src/main/java/com/tscanner/app/utils/GoogleCredentialRequestFactory.kt` | - Thêm try-catch an toàn trong `DefaultGoogleCredentialClient` khi parse `GoogleIdTokenCredential.createFrom`.<br>- Ghi log có cấu trúc cho giai đoạn `PARSE`: `SUCCESS`, `FAILURE`, `UNSUPPORTED_TYPE`. |
| `app/src/main/java/com/tscanner/app/utils/GoogleSignInResultRouter.kt` | - Bổ sung log cấu trúc `[AuthLifecycle] stage=FALLBACK_RESULT` và `stage=UI` khi parse intent legacy.<br>- Sử dụng `sanitizeForLog` khi ghi nhận thông điệp ngoại lệ `ApiException`. |
| `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` | - Ghi log phát sự kiện `UI status=MORE_FRAGMENT_ON_CANCELLED` và `MORE_FRAGMENT_ON_ERROR`.<br>- Đảm bảo `pendingSignInAttempt = null` được dọn dẹp sạch sẽ trong mọi nhánh kết thúc để UI không bị kẹt trạng thái bận. |
| `app/src/test/java/com/tscanner/app/AppAuthLifecycleDiagnosticsTest.kt` | Suite kiểm thử mới (11 tests) bao phủ toàn diện: khử nhạy cảm, không auto-fallback khi cancel, retry thủ công ngay lập tức, fallback 1 lần khi lỗi kỹ thuật, lifecycle cancel, stale rejection, debouncing, và routing lỗi OAuth 10 / 12500 / 12501. |

## 3. Cấu trúc định dạng Log vòng đời xác thực (`AuthLifecycle`)

Tất cả log chẩn đoán mới đều mang tiền tố `[AuthLifecycle]` với các cặp key-value rõ ràng:
- `stage=REQUEST status=START attempt=<requestId> generation=<sessionGen>`
- `stage=PROVIDER status=SUCCESS attempt=<requestId>`
- `stage=PROVIDER status=CANCELLED attempt=<requestId> type=<type> reason=<reason>`
- `stage=PROVIDER status=NO_CREDENTIAL attempt=<requestId> type=<type> reason=<reason>`
- `stage=PROVIDER status=TECHNICAL_ERROR attempt=<requestId> type=<type> reason=<reason>`
- `stage=PARSE status=SUCCESS|FAILURE|UNSUPPORTED_TYPE type=<credentialType>`
- `stage=COMMIT status=START|SUCCESS|FAILURE attempt=<requestId> accountId=<maskedId>`
- `stage=FALLBACK_LAUNCH status=START|FAILED attempt=<requestId> initialCause=<cause>`
- `stage=FALLBACK_RESULT status=SUCCESS|NO_ACCOUNT|API_EXCEPTION|USER_CANCELLED_NULL_INTENT`
- `stage=STALE_REJECTION stage_context=<context> attempt=<requestId>`
- `stage=UI status=DISPATCH_SUCCESS|DISPATCH_CANCELLED|DISPATCH_ERROR attempt=<requestId>`

## 4. Kết quả kiểm thử thực tế trên Host

Chạy lệnh kiểm thử bộ test Auth cốt lõi kết hợp Suite chẩn đoán mới:
```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
$env:JAVA_HOME='C:/Users/nguye/.jdks/openjdk-21.0.1'
./gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.AppAuthLifecycleDiagnosticsTest" --tests "com.tscanner.app.GoogleLoginFlowTest" --tests "com.tscanner.app.GoogleSignInResultRouterTest" --tests "com.tscanner.app.GoogleCredentialRequestFactoryTest" --tests "com.tscanner.app.AppAuthCanonicalIdentityTest" --console=plain
```

### Bảng tổng hợp kết quả:
| Test Suite | Số Test | Pass | Fail | Error | Skipped |
|---|---|---|---|---|---|
| `AppAuthLifecycleDiagnosticsTest` | 11 | **11** | 0 | 0 | 0 |
| `GoogleLoginFlowTest` | 7 | **7** | 0 | 0 | 0 |
| `GoogleSignInResultRouterTest` | 13 | **13** | 0 | 0 | 0 |
| `GoogleCredentialRequestFactoryTest` | 4 | **4** | 0 | 0 | 0 |
| `AppAuthCanonicalIdentityTest` | 7 | **7** | 0 | 0 | 0 |
| **Tổng cộng** | **42** | **42** | **0** | **0** | **0** |

## 5. Kết luận nghiệm thu L03
- Trạng thái kết thúc của từng lần đăng nhập đã được chuẩn hóa và có thể truy vết chính xác qua Logcat ở cả bản Release.
- Khi người dùng hủy hoặc framework trả về `GetCredentialCancellationException`:
  - Ứng dụng không tự ý mở lại chooser lần 2 (không auto-fallback).
  - Trạng thái bận được giải phóng ngay lập tức, người dùng có thể bấm nút đăng nhập lại bất kỳ lúc nào mà không bị kẹt hay treo UI.
- Logcat đã được bảo vệ hoàn toàn khỏi rò rỉ thông tin xác thực nhạy cảm.
- Nghiệm thu hoàn tất gói L03; tự động chuyển sang gói L04.
