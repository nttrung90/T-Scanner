# Chẩn đoán đăng nhập Google trên bản Play — 03/10/2026

## Kết luận hiện tại

Người dùng xác nhận: APK cài trực tiếp đăng nhập được; bản đã phát hành công khai qua Play Store hiện màn hình chọn Gmail, chọn xong không đăng nhập và không hiện lỗi.

**Ưu tiên điều tra số 1: OAuth Android client chưa khớp package + chứng chỉ App Signing của bản Google Play phân phối.** Đây là giả thuyết phù hợp nhất với thông tin APK/Play khác nhau, **chưa phải nguyên nhân đã xác nhận**. Cần đối chiếu đúng phiên bản, chứng chỉ APK cài từ Play, cấu hình Cloud và mã lỗi SDK. Không có thiết bị kết nối ADB trong lần kiểm tra này; chưa truy cập Play/Cloud Console.

Google phân biệt upload certificate với app signing certificate của APK phân phối. OAuth của ứng dụng phải đăng ký fingerprint phù hợp với bản thực sự cài trên thiết bị. [Google client authentication](https://developers.google.com/android/guides/client-auth), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

Nếu thiếu Android OAuth client cho chứng chỉ Play, còn Web client ID trong binary đã đúng, việc sửa cấu hình Cloud có thể giải quyết trên binary hiện hành; chỉ xác nhận sau khi thử lại chính bản Play. Không mặc định cần sửa Kotlin, đổi khóa ký hoặc phát hành bản mới.

## Bằng chứng trong checkout và artifact

| Hạng mục | Đã kiểm tra | Giới hạn |
|---|---|---|
| Package | `com.tscanner.app` | Khớp source và manifest AAB local; chưa đọc package/version từ thiết bị gặp lỗi |
| Source | versionCode **19**, versionName **1.1.0**, release `minifyEnabled true` | Không mặc định source hiện tại bằng commit đã phát hành |
| `app/release/app-release.aab` | Manifest protobuf: **19 / 1.1.0**, có embedded R8 mapping | Chưa xác nhận đây chính là bundle public |
| Client ID trong DEX AAB | Khớp `AppAuthManager.webClientId` bên dưới | Không chứng minh ID còn hợp lệ hoặc cùng Cloud project với Android client |
| `app/release/app-release.apk` | Manifest thực: **8 / 0.2.6** | Đây là APK local cũ; chưa biết có phải APK người dùng từng thử hay không |
| `app/release/output-metadata.json` | Ghi **1 / 0.1.0** | Metadata không khớp APK local; không dùng làm chứng cứ phiên bản phát hành |
| SDK | credentials + credentials-play-services-auth **1.3.0**; googleid **1.1.1**; play-services-auth **21.2.0** | Có provider dependency; không suy luận cần nâng SDK từ phiên bản đơn thuần |

Web client ID trong source và AAB local:

```text
284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com
```

Đây là client identifier công khai, không phải client secret. Cần xác minh loại **Web application** và project trong Google Auth Platform; không thay nó bằng Android client ID. Cấu hình serverClientId dùng Web client ID theo [hướng dẫn Google](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

AAB local SHA-256:

```text
A97D1A9FEAD4593CEC5D423430F8EA1E4D91DEF492140965BF438C50248D9941
```

Chứng chỉ ký **AAB local** có SHA-1 `FF:CA:87:B4:37:E9:9E:DF:24:F9:13:85:BA:8E:39:F3:F0:AA:9C:E7`. **Không coi fingerprint này là SHA-1 App Signing của Play.** Nó chỉ định danh chữ ký file local đã đọc; phải lấy chứng chỉ bản phân phối từ Play Console/installed APK.

## Đường code giải thích việc không hiện lỗi

1. [AppAuthManager.kt:79](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt:79>) chứa Web client ID dùng chung cho Credential Manager và legacy fallback.
2. [GoogleCredentialRequestFactory.kt:64](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/GoogleCredentialRequestFactory.kt:64>) tạo một `GetSignInWithGoogleOption`; không trộn Drive scopes trong đăng nhập identity. Request shape phù hợp explicit-button flow.
3. [AppAuthManager.kt:895](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt:895>) xử lý mọi `GetCredentialCancellationException` bằng log cố định “User cancelled”, xóa attempt rồi gọi onCancelled. Nhánh này không ghi exception type/reason và không có onError/fallback.
4. [MoreFragment.kt:344](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt:344>) chỉ xóa pending state/continuation khi canceled; UI không báo gì. Đây là behavior xác nhận từ source và có test cancellation tương ứng, **chưa chứng minh ca thực tế trên máy người dùng đi vào nhánh này**.
5. Google lưu ý cancellation có thể liên quan technical authorization/configuration, không luôn là người dùng bấm hủy. Không tự retry/fallback khi thiếu consent; cần theo dõi và kiểm tra cấu hình. [Credential Manager troubleshooting](https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide).
6. Technical provider errors khác có fallback; legacy result router vẫn đọc ApiException kể cả RESULT_CANCELED. Vì vậy “mọi lỗi đều bị nuốt” là kết luận không đúng. Stale/lifecycle paths cũng có thể bỏ completion; cần log stage/attempt để phân biệt.
7. [AppAuthManager.kt:759](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt:759>) commit profile và dữ liệu local sau provider success; lỗi commit có onError. Billing sync chạy sau commit, có catch riêng tại [AppAuthManager.kt:1299](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt:1299>). Chưa có chứng cứ backend Billing/Drive là nguyên nhân không chọn được tài khoản.

## Thứ tự các giả thuyết cần xác minh

| Ưu tiên | Giả thuyết | Chứng cứ để kết luận |
|---|---|---|
| 1 | Android OAuth client thiếu/sai package + Play signing SHA-1, hoặc nằm sai project | Installed APK/Play certificate + Android client tuple + Web client project; retest cùng binary sau sửa |
| 2 | Web client ID sai/đã bị thay/xóa, hoặc cấu hình audience/account restriction | ID từ binary, Web client metadata, mã SDK, tài khoản mới và đã consent |
| 3 | SDK trả cancellation kỹ thuật nhưng ứng dụng chỉ xử lý im lặng | Log exception type/reason đã lọc dữ liệu, stage/attempt, thao tác thực tế |
| 4 | Release/R8 hoặc code khác với APK từng hoạt động | So sánh cùng source/version/config qua local release và Play install; mapping + exception parsing/class-loading cụ thể |
| 5 | Lifecycle/stale callback hoặc local commit lỗi | Provider success đã có, attempt/generation, activity/view lifecycle, commit/UI stage rõ ràng |

Public trên Play và trạng thái phát hành OAuth là hai cấu hình độc lập; kiểm tra Audience/Test users/External/Internal nếu mã lỗi hoặc tài khoản bị hạn chế dẫn tới hướng đó. Không coi Drive verification, thiếu google-services.json, hoặc chưa bật Firebase là nguyên nhân mặc định của đăng nhập identity hiện tại.

## Kiểm chứng và giới hạn

Lệnh host: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain`. Kết quả cuối lưu ở `build/login-play-audit-20261003/host-summary.json` cùng `host-checks.log`/`host-checks.exit.txt`. Lần đầu gặp quyền ghi cache Gradle, đã chạy lại với quyền truy cập cache; lỗi môi trường đó không phải lỗi app.

**Kết quả hiện tại:** Gradle exit 0, BUILD SUCCESSFUL; **983 tests / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 753 warnings**, assembleDebug đạt (một số task UP-TO-DATE). Bốn suite cốt lõi: GoogleLoginFlowTest 7, GoogleCredentialRequestFactoryTest 4, GoogleSignInResultRouterTest 13, AppAuthCanonicalIdentityTest 7 — tổng **31/31 PASS**. Git status trước/sau chỉ thêm hai tài liệu nghiên cứu/kế hoạch ở root; audit helpers và logs nằm dưới build.

Tests hiện có dùng fake Google provider/parser, nên dù đạt cũng không chứng minh chữ ký OAuth của bản Play đúng. Chưa chạy login Google thật, release R8 runtime hoặc Play-installed acceptance. ADB hiện không có thiết bị. Không sửa production, OAuth clients, signing keys hay rollout trong lần nghiên cứu này.

Kế hoạch: [PLAN_FIX_LOGIN_PLAY_GEMINI_2026-10-03.md](</E:/DU AN AI/T-Scanner/PLAN_FIX_LOGIN_PLAY_GEMINI_2026-10-03.md>).
