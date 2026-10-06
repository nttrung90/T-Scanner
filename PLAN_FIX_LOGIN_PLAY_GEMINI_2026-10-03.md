# Sửa đăng nhập Google bản Play — Gemini Antigravity, L00–L06

## Mục tiêu

Người dùng cài T-Scanner từ Play Store công khai, chọn Gmail và đăng nhập thành công, profile cập nhật và giữ được sau mở lại app. APK cài trực tiếp được người dùng xác nhận đăng nhập được; bản Play chọn Gmail xong im lặng.

Đọc [chẩn đoán và evidence](</E:/DU AN AI/T-Scanner/DIAGNOSIS_LOGIN_PLAY_2026-10-03.md>) trước. Ưu tiên chứng minh và sửa tuple OAuth **package + Play app-signing SHA-1 + Cloud project/Web client**. Chưa đủ chứng cứ để khẳng định SHA-1 là nguyên nhân duy nhất.

**Tự thực hiện lần lượt L00→L06; không yêu cầu người dùng canh hoặc xác nhận từng gói.** Gói có điều kiện chỉ làm khi có bằng chứng, nếu không thì ghi `NOT_NEEDED` với lý do. Thiếu Console/device ghi `BLOCKED_EXTERNAL`, tiếp tục diagnostics/tests/docs độc lập; tổng hợp một lần các dữ liệu/quyền cần người dùng. Không coi external gate chưa chạy là DONE.

Giữ mọi thay đổi hiện có. Không reset/clean/stash/drop, đổi package/khóa ký, tự commit/push hoặc rollout public. Phạm vi hiện tại là login production incident; không mở lại toàn bộ VIP/Drive/backend. Mỗi gói ghi `REPORT_LOGIN_PLAY_Lxx.md` và `PROGRESS_LOGIN_PLAY_AUTORUN.md`, gồm evidence, files changed, tests thực chạy, acceptance và bước kế tiếp. Các gói sửa AppAuthManager/MoreFragment tuần tự, không ghi đè nhau.

## L00 — Khóa đúng bản lỗi và thu lỗi SDK

- **Phạm vi:** điều tra, docs và evidence; chưa thay logic login.
- Ghi versionCode/versionName/package, nguồn cài và Android/Play Services version của máy lỗi. Xác nhận cài từ production Play; không dùng Internal App Sharing như chứng cứ tương đương vì có thể được ký bằng chứng chỉ khác.
- Đối chiếu bản APK từng đăng nhập được: phiên bản/source/minification/client ID/signing certificate. Local `app/release/app-release.apk` hiện là 8/0.2.6, AAB là 19/1.1.0; output-metadata local cũ không khớp. Không tự khẳng định APK người dùng thử chính là file local đó.
- Trên thiết bị đã cho phép debug: `adb shell dumpsys package com.tscanner.app` để lấy version/installer; `adb shell pm path com.tscanner.app` để lấy đúng đường APK. Pull base APK vào thư mục audit và dùng `apksigner verify --print-certs` đọc fingerprint. Không gỡ app/clear-data làm mất tài liệu để thử login.
- Capture log ngắn đúng lúc tái hiện; ưu tiên các tag `AppAuthManager`, `GoogleSignInRouter`, `CredentialManager`, `CredentialProvider`, `MoreFragment`, `AndroidRuntime` và SDK tag liên quan khi cần. Không chạy `logcat -c` hoặc thu/upload log toàn máy không cần thiết. Raw log giữ local; bản báo cáo loại email, token, authorization header và dữ liệu người dùng.
- Phân biệt stage: REQUEST→provider result/exception→PARSE→COMMIT→UI. Ghi exception class/type, sanitized reason/status code, attempt/session và host lifecycle. Log hiện tại gọi cancellation là “User cancelled” không đủ chứng minh người dùng đã hủy.
- **Acceptance:** có bằng chứng runtime hoặc bảng dữ liệu còn thiếu rõ ràng; không chẩn đoán chắc SHA/R8 từ triệu chứng. Checkpoint rồi L01, phần thiếu device không ngăn L03 độc lập.

## L01 — Đối chiếu và sửa cấu hình OAuth cho bản Play

- **Phạm vi:** Play Console/Google Auth Platform/Google Cloud cấu hình của ứng dụng, chứng cứ config; không sửa Kotlin để bù sai chữ ký.
- Trong Play Console tìm **Play App Signing / App signing key certificate** (vị trí menu có thể đổi, có thể nằm dưới App integrity hoặc Protected with Play). Lấy SHA-1 của app signing certificate dùng cho bản phân phối, **không lấy riêng Upload key certificate**. Nếu có key upgrade/nhiều certificate theo Android version, đối chiếu certificate thực trên máy lỗi và đăng ký các certificate phân phối liên quan.
- Trong Google Auth Platform → Clients hoặc APIs & Services → Credentials, mở đúng Cloud project của Web client đang được app sử dụng. Xác minh Web client `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com` là **Web application**, còn hiệu lực và đúng project.
- Kiểm tra Android OAuth client có chính xác package `com.tscanner.app` + SHA-1 Play tương ứng trong cùng project. Nếu thiếu, tạo Android client đúng tuple; giữ client debug/local upload đang phục vụ bản khác. Không thay SHA trong client đang hoạt động một cách làm hỏng bản APK cũ, không tạo project mới hay reset key. Nếu tuple bị báo đã tồn tại ở project khác, xác định chủ sở hữu/project và xử lý liên kết; không xóa client không rõ vai trò.
- Không thay Web client ID trong `serverClientId`/`requestIdToken` bằng Android client ID. Không thêm OAuth client secret vào app. Nếu chỉ thiếu Android client, giữ binary/client ID hiện tại.
- Kiểm tra Audience (External/Internal), Publishing status, Test users/consent restriction nếu cần. Public Play không tự chứng minh OAuth đã cấu hình cho tất cả tài khoản. Không yêu cầu thêm Drive scopes/Drive verification chỉ để login identity. Không tự thêm Firebase hoặc google-services.json nếu app không dùng Firebase Auth.
- **Regression/acceptance:** bảng package/certificate/project/Web+Android client trước/sau có evidence; thử lại chính binary Play hiện hành sau cấu hình có hiệu lực. Nếu sửa config giải quyết được, ghi `CONFIG_ONLY_RESOLVED`; không tạo release vô ích. Không có quyền Console thì lưu checklist tuple cần người quản trị đối chiếu một lần, tự chuyển phần độc lập.
- Nguồn: [Client authentication](https://developers.google.com/android/guides/client-auth), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [Web client ID trong SDK](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

## L02 — Xác nhận nhánh nguyên nhân trước khi sửa code

- **Depends:** evidence L00/L01. **Files:** báo cáo/chẩn đoán; chỉ sửa cấu hình hoặc logic khi nguyên nhân đã được xác định.
- Nếu cùng binary Play login được sau sửa Android client: khóa kết luận config; L04 không cần thay production logic. L03 chỉ là cải thiện khả năng chẩn đoán nếu triển khai trong bản sau, không điều kiện chặn khôi phục dịch vụ.
- Nếu provider chưa trả credential: xử lý mã SDK/OAuth/account restriction; backend Billing chưa phải điểm khởi đầu.
- Nếu provider đã trả credential nhưng parser/commit thất bại: chuyển L04 với stacktrace đã lọc và regression đúng stage.
- Nếu credential/commit thành công mà UI không đổi: kiểm tra LiveData observer, attempt/lifecycle và continuation thực tế; không đổi OAuth bừa.
- Nếu chỉ minified release cùng source/config lỗi: dùng L04 kiểm tra R8/mapping/runtime. APK debug khác version không đủ chứng minh R8.
- **Acceptance:** ghi một nhánh có evidence hoặc `UNCONFIRMED`, không ép chọn nguyên nhân. Thiếu evidence external thì tiếp tục diagnostics/test độc lập, không bypass auth để làm UI hiện tài khoản.

## L03 — Chẩn đoán an toàn và trạng thái kết thúc login rõ ràng

- **Files:** `AppAuthManager.kt`, `GoogleCredentialRequestFactory.kt`/adapter nếu cần, `GoogleSignInResultRouter.kt`, `MoreFragment.kt`, resources/locales và tests. Không đổi identity/VIP ownership.
- Log structured stage/attempt/status cho provider cancel, unsupported credential, parse failure, fallback launch/result, stale rejection, commit failure/success và UI completion. Sanitized SDK reason khi an toàn; không dump Bundle/account/ID token/email hay toàn exception message chưa lọc. Diagnostics cần đọc được trên bản release, không chỉ debug build.
- Không gắn mọi `GetCredentialCancellationException` nhãn chắc chắn “người dùng hủy”. Giữ terminal cancellation/unknown reason, reset busy state và cho retry thủ công. **Không tự fallback/retry khi SDK trả cancellation**; không tự phân loại technical failure chỉ từ chuỗi message hoặc thời lượng. Có thể cung cấp thông tin trợ giúp không gây hiểu nhầm khi người dùng chủ động yêu cầu, thay vì popup lỗi trên mọi lần bấm Back.
- Technical exceptions có typed outcome/fallback tối đa một lần, không mất nguyên nhân ban đầu khi fallback thất bại. Coroutine cancellation/lifecycle rethrow/cleanup, không biến thành auth retry. Không sửa stale/session guards để ép nhận result cũ.
- **Regression:** provider success; cancellation (reason không xác định); NoCredential/technical exception; unsupported credential; parse error; fallback status10/12500, RESULT_CANCELED+error Intent; lifecycle cancel; double tap; stale callback; commit lỗi; retry thủ công hoạt động sau terminal. Assert không auto-fallback khi cancellation và log không có sensitive data.
- **Acceptance:** mỗi attempt có trạng thái kết thúc có thể truy vết, UI không kẹt, cancel thật không bị tự mở chooser lần hai. Đây là diagnostics/UX, không tự đánh dấu production incident FIXED nếu bản Play vẫn chưa đăng nhập được. [Google troubleshooting](https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide).

## L04 — Sửa code/release chỉ theo lỗi đã tái hiện

- **Depends:** L02 evidence, L03 diagnostics khi cần. **Ownership:** chỉ branch đã xác minh; không đại tu toàn bộ auth.
- **Sai Web client/binary config:** xác định một nguồn config rõ ràng, áp dụng cả Credential Manager và legacy options; không đưa Android client ID vào vị trí Web. Nếu thay Web client thật sự cần thiết, đối chiếu backend token audience cho VIP ở cùng thay đổi có kiểm thử; không nới/bỏ xác thực audience để chữa 401. Thay client ID trong binary cần release mới.
- **Provider parsing/R8:** đối chiếu consumer rules và mapping của artifact lỗi, xác định class/constructor bị ảnh hưởng. Chỉ thêm keep rule nhỏ đúng chứng cứ hoặc cập nhật SDK stable tương thích khi release notes/log chứng minh cần. Không tắt R8 toàn app hay copy blanket keep, không nâng tất cả dependencies theo phỏng đoán. Request hiện có một GetSignInWithGoogleOption đúng hướng, không trộn option để “thử”.
- **Lifecycle/commit/UI:** sửa đúng stage đã có result thật và regression red; giữ attempt/session/process identity, single terminal callback, account isolation. Không bỏ cancellation/destroy guards; không đổi email thành owner ID để lách canonical identity. Login không phụ thuộc mua VIP/Drive permission thành công.
- **Regression:** red→green của cause cụ thể; provider success cập nhật và persist user; restart/upgrade có dữ liệu cũ; stale result không đổi account; actual cancellation không retry; billing failure/Drive chưa cấp quyền không phá login. Release/minified runtime test bắt buộc nếu sửa R8.
- **Acceptance:** có nguyên nhân, diff tối thiểu, regression trước/sau; nếu không có code defect xác nhận, ghi `NOT_NEEDED` hoặc `BLOCKED_EXTERNAL`, không sửa tùy đoán để hoàn tất gói.

## L05 — Kiểm chứng host và chuẩn bị artifact sửa lỗi

- **Depends:** các thay đổi code thực tế ở L03/L04. Nếu chỉ config-only, giữ evidence binary cũ + runtime mới, không bắt tạo AAB mới.
- Chạy auth suites: `GoogleLoginFlowTest`, `GoogleCredentialRequestFactoryTest`, `GoogleSignInResultRouterTest`, `AppAuthCanonicalIdentityTest`, các `VipLoginRound*RegressionTest`, logout serialization và continuation tests. Sau đó full JVM, lintDebug/assembleDebug theo lệnh cuối; đọc counts/exit/XML thật.
- Với code fix: build release theo quy trình ký hiện có, kiểm tra package/version/Web client từ artifact, R8 mapping và SHA-256. Chỉ tăng versionCode khi chuẩn bị update cần upload, phải lớn hơn version đã dùng trong Play Console; không suy từ source19 rằng20 chắc còn trống. Không ghi password/keystore/private key vào plan/log.
- **Acceptance:** host checks không có regression, artifact traceable tới source/config, release runtime được ghi riêng. Unit fake-provider PASS không chứng minh Google OAuth signature đúng. Không tự upload/rollout public; chuẩn bị candidate và evidence để phát hành theo quyền được giao.

## L06 — Nghiệm thu bản Play và bàn giao một lần

- **Depends:** L01 config hoặc candidate L05 và môi trường Play/device thực.
- Test bằng app được **cài/cập nhật qua Google Play**. Nếu cần code update, dùng testing track của cùng Play app và kiểm tra chứng chỉ thực, không lấy sideload/debug/Internal App Sharing làm thay thế final gate.
- Matrix tối thiểu: Gmail đã consent; Gmail mới chưa từng login (ngoài danh sách tester OAuth nếu mục tiêu public); chọn tài khoản thành công; Back/cancel không retry tự động; login lại sau logout; đổi A→B; mất mạng/provider error có terminal state; mở lại app giữ profile; upgrade giữ tài liệu; Free login không đòi Drive/VIP; VIP restore vẫn đúng account sau login.
- Kiểm tra đúng Android/Play Services của máy báo lỗi; thêm một cấu hình Android khác khi chứng chỉ Play khác theo thiết bị/key upgrade. Không thử mua tiền thật trong nhiệm vụ login.
- **Acceptance bắt buộc:** chọn Gmail → profile hiện đúng → mở lại còn đăng nhập trên bản Play. Lưu package/version/install source/cert/điều kiện test và sanitized log. Không có thiết bị/Console thì `BLOCKED_EXTERNAL`, không ghi “đã sửa xong production”.
- `REPORT_LOGIN_PLAY_FINAL.md`: root cause confirmed/unconfirmed, config-only/code fix, files/config changed, actual tests, runtime matrix, phần chưa chạy và một danh sách ngắn các việc cần người dùng. Nếu public rollout chưa được giao thì bàn giao candidate, không tự publish.

## Lệnh host từ workspace

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
$env:JAVA_HOME='C:/Users/nguye/.jdks/openjdk-21.0.1'
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
```

Nếu task UP-TO-DATE và cần chạy lại hành vi, rerun test task rồi lưu XML trước run khác. Lỗi cache quyền truy cập là lỗi môi trường, không sửa app để né. Host evidence nghiên cứu hiện tại ở `build/login-play-audit-20261003/`.

## Prompt giao một lần cho Gemini Antigravity

```text
Tại E:\DU AN AI\T-Scanner, đọc DIAGNOSIS_LOGIN_PLAY_2026-10-03.md và
PLAN_FIX_LOGIN_PLAY_GEMINI_2026-10-03.md. Thực hiện L00–L06 tuần tự,
tự lưu progress/report và chuyển gói, không chờ tôi duyệt từng gói.

Triệu chứng: APK trực tiếp login được, bản public Play chọn Gmail rồi
không có phản hồi/lỗi. Ưu tiên xác minh package + Play app-signing SHA-1
+ Cloud project/Android OAuth client + Web client ID; chưa được khẳng định
SHA là nguyên nhân nếu chưa đối chiếu. Không nhầm upload key với Play key.

Sửa config trước nếu chứng cứ xác nhận; retest cùng binary Play. Chỉ sửa
code/R8 theo nhánh có evidence, không nới auth/session/owner để login giả.
Cancellation không tự fallback/retry; thêm diagnostics an toàn khi cần.
Giữ changes/tài liệu hiện có, không reset/clean, đổi package/khóa ký hoặc
tự publish public. Không đụng secrets; không cài lại/xóa dữ liệu người dùng.

Thiếu device/Console: ghi BLOCKED_EXTERNAL và tiếp tục phần độc lập;
cuối cùng hỏi một lần đúng dữ liệu/quyền còn thiếu. Không giả tạo device
pass từ unit tests. Báo cáo cuối ghi root cause, kết quả bản cài qua Play,
counts thực tế, artifact/config và phần chưa nghiệm thu. Các nhánh không
cần sửa phải ghi NOT_NEEDED với chứng cứ, không sửa thêm cho đủ gói.
```
