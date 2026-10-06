# Sửa mua VIP sau khi phiên xác thực hết hạn — Gemini (Antigravity)

Ngày lập: 04/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

## 1. Mục tiêu và ranh giới

Người dùng đã đăng nhập Gmail, chưa mua VIP, sau một thời gian bấm mua thì bị yêu cầu đăng nhập dù vẫn thấy tài khoản. Phải đăng xuất rồi đăng nhập mới mua được.

Kết quả cần đạt: khi credential hết hạn/thiếu, ứng dụng cho xác thực lại ngay từ luồng VIP, giữ dữ liệu và tài khoản hiện tại, rồi quay về xác nhận gói hoặc thực hiện đúng yêu cầu khôi phục. Không bắt đăng xuất. Không cấp VIP chỉ vì đăng nhập thành công. Không tự mở thanh toán sau xác thực nếu chưa có xác nhận gói từ người dùng.

Đây là kế hoạch triển khai, chưa sửa production code. Khi được giao thực hiện kế hoạch này, Gemini chạy tuần tự E00 → E06; mỗi gói phải kiểm tra và ghi bàn giao trước khi chuyển gói kế tiếp, không cần hỏi lại sau mỗi gói. Không chạy nhiều agent cùng sửa các file auth/UI dùng chung.

Giữ mọi thay đổi staged/unstaged/untracked hiện có; không reset, clean, stash, ghi đè file bằng bản cũ, commit/push hoặc phát hành. Không sửa package, OAuth client, signing, R8, versionCode, dependency hay backend Billing trong phạm vi này. Không gỡ ứng dụng/clear-data để tái hiện. Không ghi token/email/Authorization header vào log hoặc báo cáo.

Lỗi public Play chọn Gmail rồi im lặng trong `PLAN_FIX_LOGIN_PLAY_GEMINI_2026-10-03.md` là luồng điều tra riêng. Giữ các sửa diagnostics, cancellation và fallback đã có; không suy luận lỗi hiện tại là SHA-1/OAuth sai.

## 2. Bằng chứng và mức xác nhận

Các dòng sau được đọc trong checkout ngày 04/10; Gemini phải tìm lại theo tên hàm nếu dòng đã dịch chuyển.

| ID | Bằng chứng code | Kết luận / mức độ |
|---|---|---|
| F01 | `AppAuthManager.kt:159` trả lại `currentUser.idToken`; `init()` khôi phục profile; `isLoggedIn():215` chỉ xét profile khác null; token được lưu/đọc tại `saveUser`/`parseUserProfileFromJson` | Trạng thái tài khoản còn đăng nhập không đồng nghĩa credential còn dùng được cho Billing. Chưa thấy cơ chế lấy credential mới trước mua. CODE VERIFIED |
| F02 | `VipUpgradeDialog.kt:29–38` chuyển token thiếu/hết hạn thành RequestSignIn hoặc ShowSignInRequiredPrompt; `AccountDetailDialog.kt:106,112` chỉ truyền context và Drive callback | P1: hai điểm mua trong Chi tiết tài khoản không có đường xác thực lại. CODE VERIFIED; rất phù hợp triệu chứng, chưa DEVICE PASS |
| F03 | `MoreFragment.kt:275` bấm tài khoản có profile mở AccountDetailDialog; chỉ profile null mới mở sign-in | Giải thích vì sao lời nhắc đi đăng nhập không giúp người đang có profile; đăng xuất là cách vòng người dùng đang phải dùng. CODE VERIFIED |
| F04 | `VipUpgradeDialog.kt:154` tính callback khả dụng chỉ bằng `onRequestSignIn != null`, nhưng listener ưu tiên `onRequestSignInForAction` | P2: cấu hình chỉ truyền callback theo hành động bị coi là không thể đăng nhập. CODE VERIFIED; chưa khẳng định mọi call site hiện tại dùng cấu hình này |
| F05 | `MoreFragment.kt:96` fallback dùng `onSignInSuccessWithAction`; `:340` Credential Manager success dùng `onSignInSuccess { showVipUpgradeDialog() }` | P2: RESTORE có thể bị chuyển thành UPGRADE trong nhánh hiện đại. CODE VERIFIED |
| F06 | `VipPurchaseActionCoordinator.validateCurrentSession` kiểm tra lại token; `BillingManager.kt:681–685` chặn token hết hạn bằng chuỗi lỗi | Phải phủ token hết hạn giữa lúc tải sản phẩm và mở Billing; không chỉ kiểm tra ở lần bấm đầu tiên. Coverage requirement |

Kiểm thử đã chạy ở lượt kiểm tra ngay trước kế hoạch: `VipPurchaseActionCoordinatorTest` 11/11 và `VipRound8RegressionTest` 17/17, tổng 28/28, BUILD SUCCESSFUL. Log: `audit_login_expiry_20261004.log`. Đây là baseline có sẵn, không phải 28 kiểm thử mới tái hiện incident. Chưa chạy lại test/build/lint cho lượt viết tài liệu này. ADB báo không khởi động được daemon; chưa có bằng chứng thiết bị cho ca hết hạn.

## 3. Quyết định thiết kế bắt buộc

1. Phân biệt **guest**, **có tài khoản nhưng cần xác thực lại**, **credential sẵn sàng**. Giữ `isLoggedIn()` phục vụ trạng thái tài khoản; không đổi thành kiểm tra hạn token trên toàn ứng dụng.
2. Bản sửa tối thiểu dùng luồng Google sign-in hiện có để lấy credential mới khi người dùng chủ động mua/khôi phục và credential không sẵn sàng. Hiển thị lý do phù hợp: “Phiên xác thực đã hết hạn. Vui lòng xác thực lại tài khoản Google để tiếp tục.” Không hứa SDK luôn làm mới âm thầm. Không thêm vòng refresh nền, OAuth refresh token hay một hệ thống session backend mới.
3. Reauthentication không gọi `signOut`, `clearCredentialState`, xóa profile/tài liệu/entitlement. Hủy/lỗi phải giữ trạng thái tài khoản hiện có, kết thúc loading và cho retry chủ động; không mở lại provider tự động vô hạn.
4. Ràng buộc yêu cầu xác thực lại với owner ID chuẩn đang dùng, session generation, request/attempt ID và process epoch. Với tài khoản A đang đăng nhập, kết quả B phải bị từ chối **trước khi commit profile hoặc gây side effect**; giữ A và báo chọn đúng tài khoản. Guest vẫn dùng login thông thường. Không dùng email làm khóa ownership mới, không tự di chuyển receipt.
5. Chỉ nhận kết quả đúng attempt còn hiệu lực và credential mới còn hạn; credential mới vẫn thiếu/hết hạn thì kết thúc bằng lỗi có retry, không lặp đăng nhập. Kiểm tra `exp` cục bộ chỉ là điều kiện sẵn sàng, không thay xác thực chữ ký/audience/issuer/owner ở backend.
6. Bảo toàn single in-flight login, logout serialization, cancellation, legacy fallback và lifecycle guards hiện có. Không trộn Drive authorization với identity sign-in; không yêu cầu cấp Drive để mua VIP.
7. Sau thành công đúng tài khoản: UPGRADE trở về xác nhận gói đúng một lần; RESTORE chạy khôi phục đúng một lần. Giữ product/action context nếu có, không tự chọn gói khác. Thất bại/hủy/đổi session phải xóa continuation của đúng operation.
8. Không bỏ guard token hết hạn ở Billing/verifier để chữa UI. Nếu backend từ chối credential dù `exp` còn hạn, giữ lỗi auth có nghĩa; không retry thanh toán/cấp VIP mù quáng, không coi mọi 403 là token hết hạn.

Nguồn SDK: [Google Sign-in với Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg) nêu button flow có thể truy cập tài khoản cần reauthentication và phân biệt identity với quyền truy cập Drive. Trước khi đổi request/provider API, đọc tài liệu chính thức tương ứng phiên bản đang dùng; ưu tiên tái sử dụng adapter hiện có.

## 4. Các gói thực hiện tuần tự

### E00 — Chụp baseline và tạo regression thực sự

- **Phạm vi:** docs và test mới `VipSessionExpiryRegressionTest.kt`; không sửa production.
- Đọc kế hoạch này, các file F01–F06 và hướng dẫn repository nếu có. Lưu git status/diff summary, command, thời điểm và kết quả vào `docs/vip-session-20261004/`. Không sao chép secrets hay toàn bộ dữ liệu người dùng.
- Dùng profile Free của A với JWT tổng hợp chứa `exp` đã qua; đối chứng JWT tương lai. Gọi resolver/coordinator thực, fake chỉ provider/Billing/clock/persistence boundary. Token tổng hợp chỉ ở test; không gửi lên Google/backend.
- Tạo regression đỏ cho: click mua từ hai nút AccountDetailDialog với profile A hết hạn phải có đường reauth; callback chỉ có action được nhận diện; RESTORE sau Credential Manager success giữ RESTORE. Ưu tiên Robolectric/boundary gọi production; kiểm tra chuỗi source không thay được test hành vi.
- **Acceptance:** lưu tên test và lỗi mong đợi red; phân biệt test không compile với behavioral failure. 28 test baseline vẫn phải chạy hoặc ghi rõ blocker môi trường.
- **Checkpoint:** bàn giao reproduction + test fixtures. E01 chỉ bắt đầu sau khi đã khóa test đỏ hoặc nêu rõ seam UI còn thiếu phải bổ sung trong gói phụ trách.

### E01 — Xác thực lại an toàn cho tài khoản đang đăng nhập

- **Depends:** E00.
- **Files sở hữu:** `AppAuthManager.kt`, `GoogleLoginAttempt.kt`, tests auth mới; `GoogleCredentialRequestFactory.kt`/`GoogleSignInResultRouter.kt` chỉ khi cần truyền cùng context qua hai provider paths. Không sửa UI/Billing/backend.
- Thêm API hoặc mode reauth rõ ràng, tái sử dụng sign-in pipeline. Context expected owner phải gắn với attempt cụ thể; không dùng biến singleton rời có thể bị callback cũ mượn.
- Đặt kiểm tra owner/attempt/token mới trước `processSignedInAccountInternal` vì hàm này có migrate/claim/save/onLoginCommitted. Áp dụng cho Credential Manager lẫn Intent fallback. Không nhận B rồi mới hoàn tác về A.
- Kiểm tra kết quả lưu credential: không báo thành công bền vững nếu persistence thất bại; giữ trạng thái nhất quán và lỗi rõ ràng. Không âm thầm thay nghĩa persistence của các luồng ngoài phạm vi.
- Same-owner success giữ tài liệu/VIP đúng owner; các callback/sync nền chỉ chạy theo policy hiện có sau commit hợp lệ. Không sửa toàn bộ auth state machine hoặc thêm SDK.
- **Regression:** A→A token mới; A→B bị chặn trước side effect; logout/account switch trong lúc chờ; callback cũ/khác process; cancel/offline/technical fallback; token mới malformed/expired; save failure; double tap. Kiểm tra login guest và logout guards không hồi quy.
- **Acceptance:** credential mới sẵn sàng và được lưu cho A; không sign-out/clear state; thất bại không mất profile/VIP/tài liệu; cùng contract ở cả hai provider paths.
- **Checkpoint:** bàn giao API, state/result types, tests và quy tắc generation cho E02. Không để E02 tự suy đoán cách gọi reauth.

### E02 — Giữ đúng continuation qua xác thực lại

- **Depends:** E01.
- **Files sở hữu:** `VipLoginContinuationHandler.kt`, `MoreFragment.kt`, `MainActivity.kt` nếu cần truyền context; tests continuation. Chưa sửa AccountDetailDialog/VipUpgradeDialog.
- Phân biệt guest login với reauth dựa vào profile và trạng thái credential ở thời điểm thao tác. Truyền expected owner/context vào API E01.
- Thống nhất nhánh success Credential Manager và Intent fallback bằng handler theo action; sửa F05. Tránh hai nơi consume continuation hoặc callback mở dialog trùng.
- Giữ UPGRADE/RESTORE, owner, operation identity và product nếu đang có. Không tùy tiện bỏ guard generation để login guest thành công; định nghĩa chuyển generation hợp lệ theo originating attempt, còn session ngoài attempt phải vô hiệu hóa continuation.
- Rotation chỉ phục hồi context nếu attempt còn hợp lệ. Sau process death không tự phát lại Billing/reauth từ pending flag cũ; cho người dùng bấm lại an toàn. Hủy/lỗi clear đúng continuation và giữ profile.
- **Regression:** guest upgrade; A expired upgrade; RESTORE qua cả hai provider; cancel rồi retry; duplicate callback; rotation; process death; logout/A→B giữa chừng. Mỗi hành động sau auth chỉ xảy ra một lần.
- **Acceptance:** RESTORE không mở UPGRADE; reauth không tự mua; không replay continuation cũ; guest flow vẫn hoạt động.
- **Checkpoint:** bàn giao action callback/context contract cho E03.

### E03 — Nối Chi tiết tài khoản và phân biệt lời nhắc

- **Depends:** E02.
- **Files sở hữu:** `AccountDetailDialog.kt`, `VipUpgradeDialog.kt`, `MoreFragment.kt` phần tạo dialog, strings của các locale ứng dụng đang hỗ trợ; test UI. Call site AccountDetailDialog khác chỉ sửa khi cần tương thích constructor.
- Truyền callback theo action từ MoreFragment qua AccountDetailDialog vào **cả hai** vị trí mở VipUpgradeDialog. Đóng/mở dialog đúng lifecycle; không giữ Activity cũ.
- Sửa F04: khả năng xử lý auth tính từ cả callback thường và callback theo action; callback theo action ưu tiên, chỉ gọi một callback. Giữ backward compatibility cho các host chưa dùng action callback.
- Guest nhận lời nhắc đăng nhập; profile tồn tại nhưng credential thiếu/hết hạn nhận lời nhắc xác thực lại. Có đường gọi reauth trực tiếp, không chỉ toast dẫn người dùng quay về tab đang hiện profile.
- Đảm bảo restore trong dialog dùng cùng đường auth-aware và giữ RESTORE. Không thêm cấp Drive hoặc VIP trial vào failure path.
- **Regression:** bấm thật hai nút AccountDetailDialog bằng Robolectric/instrumentation; callbacks thường/action-only/cả hai/không có; profile valid/expired/missing token/guest; dismiss và host destroyed; locale resource formatting.
- **Acceptance:** hai nút tài khoản không còn ngõ cụt; action-only hoạt động; tài khoản hợp lệ không bị hỏi lại; UI không mất dữ liệu người dùng.
- **Checkpoint:** bàn giao danh sách call sites đã nối và test green cho F02/F04.

### E04 — Khép các khoảng hở khi đang tải/mua/khôi phục

- **Depends:** E03.
- **Files sở hữu:** `VipPurchaseActionCoordinator.kt`, `BillingManager.kt`, `VipUpgradeDialog.kt` phần listener; `PlayPurchaseVerifier.kt` chỉ khi cần đưa lỗi auth sẵn có về typed outcome, không đổi verification policy; tests tương ứng.
- Kiểm tra lại credential và operation owner khi connection/product load hoàn tất và ngay trước launch. Nếu hết hạn trong thời gian chờ, đưa về reauth có context, giải phóng busy đúng operation; không dừng ở generic toast cần đăng nhập.
- Ưu tiên typed auth-required result/callback cho đường Billing cuối cùng; không parse nội dung chuỗi tiếng Việt để điều khiển logic. Giữ tương thích caller khác và tách auth error khỏi network/config/product error.
- RESTORE nhận AuthRequired sau backend response cũng đi qua reauth có giới hạn. Một thao tác chỉ được tự nối lại tối đa một lần sau reauth; nếu tiếp tục lỗi thì hiển thị retry chủ động.
- Nếu Google Play đã trả purchase/receipt rồi xác minh mới lỗi auth: giữ recovery/reconciliation hiện có và receipt, không launch lại màn mua; tuyệt đối không cấp entitlement chỉ vì reauth thành công. Không mở rộng sửa backend.
- **Regression:** expired trong connect/queryProducts/prelaunch; backend auth failure với token còn hạn; cancel sau auth-required; logout/A→B khi callback trễ; double tap; duplicate callback; verifier chưa cấu hình; receipt đã có nhưng verify AuthRequired. Không gọi launch khi chưa auth-ready.
- **Acceptance:** không vòng lặp auth/mua, không launch trùng, không owner leak, không nới guard backend; kết quả lỗi đi đúng UI và có retry hữu hạn.
- **Checkpoint:** báo cáo những nhánh receipt/reconciliation đã test, nhánh chưa đủ seam ghi rõ, không gắn nhãn production ready.

### E05 — Kiểm thử tổng hợp và rà soát độc lập

- **Depends:** E04. **Phạm vi:** tests, kiểm tra diff, evidence/report; production chỉ sửa lỗi trực tiếp của E01–E04 rồi chạy lại test liên quan.
- Chạy các test mới trước. Sau đó chạy toàn bộ `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` offline nếu cache đủ. Đọc XML để báo tests/failures/errors/skips; không dựa vào dòng BUILD SUCCESSFUL đơn lẻ.
- Kiểm tra hồi quy các suites auth/login/logout, GoogleCredentialRequestFactory, VipLoginContinuation, VipPurchaseActionCoordinator, Billing lifecycle/owner, PlayPurchaseVerifierHttp, VipRound7/8. Không bỏ test cũ hoặc thay expectation để hợp thức hóa hành vi sai.
- Rà diff độc lập: không sign-out ẩn, không lưu token giả, không debug bypass, không log credential, không nhận tài khoản B trước guard, không claim/cấp VIP chỉ vì login, không sửa config phát hành ngoài phạm vi.
- **Acceptance:** F02/F04/F05 có behavioral regression từng đỏ và nay xanh; tests race/cancellation đạt; kết quả host có command/log. Nếu build fail do môi trường ghi ENV_BLOCKED, không gọi đó là app regression hay PASS.
- **Checkpoint:** tạo `REPORT_VIP_SESSION_FINAL.md` với host gates và device gates riêng. Tự chuyển E06 phần có thể làm.

### E06 — Nghiệm thu trên thiết bị/bản Play

- **Depends:** E05. **Phạm vi:** device evidence/checklist; không tự mua thật hoặc phát hành production.
- Dùng tài khoản test/license tester được phép và Play test track phù hợp nếu có. Ghi version/package/nguồn cài; local debug không chứng minh bản public đang chạy đã hết lỗi. Không tạo giao dịch tính tiền thật trong kiểm thử.
- Test chính: đăng nhập A Free → để credential hết hạn thực → giữ app mở hoặc mở lại → Tài khoản → hai nút mua VIP → xác thực lại A → về xác nhận gói → Google Play flow bằng tài khoản test. Không cần logout và không mất dữ liệu. Không đưa token vào evidence; ghi trạng thái expiry hoặc thời điểm đã lọc.
- Kiểm tra Trang chủ/Mở rộng/PDF/ID-card và RESTORE; hủy provider, mất mạng, A/B, back/rotation, background/foreground và kill/relaunch. Giữ tên PDF/draft/watermark intention khi quay lại flow có draft.
- Host tests dùng fake clock/JWT để kiểm tra biên thời gian; device acceptance phải có ca credential thật hết hạn. Không sửa đồng hồ toàn thiết bị hoặc sửa token production để giả làm DEVICE PASS.
- **Acceptance:** chứng cứ hết hạn → reauth → đúng action mà không logout, cùng account ownership, không mua lặp/cấp VIP sớm. Nếu thiếu thiết bị/Play access/test account, ghi NOT RUN/BLOCKED_EXTERNAL, hoàn thiện checklist và tổng hợp một lần thứ còn thiếu; không tuyên bố sửa đã được xác nhận trên public Play.
- **Điểm dừng cuối:** giao code + tests + báo cáo; chờ quy trình review/release riêng, không tự upload AAB, commit/push hay rollout.

## 5. Lệnh kiểm tra và cách báo cáo

Chạy từ workspace bằng PowerShell; xác nhận đường JDK/cache còn tồn tại trước khi dùng:

```powershell
$env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline --tests 'com.tscanner.app.VipPurchaseActionCoordinatorTest' --tests 'com.tscanner.app.VipRound8RegressionTest' --console=plain
# Chạy riêng các lớp mới của E00–E04 trước full checks.
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
```

Access denied tại cache/wrapper lock là blocker môi trường trước tiên; không xóa cache/kill mọi Java process. Dùng quyền chạy được cấp trong môi trường. Nếu offline thiếu dependency, báo đúng thiếu gì; không đổi SDK/dependency để vượt lỗi build.

Mỗi gói ghi `docs/vip-session-20261004/REPORT_Exx.md` và cập nhật `PROGRESS.md`:

```text
Gói / trạng thái: PASS | FAIL | ENV_BLOCKED | BLOCKED_EXTERNAL | NOT_RUN
Code evidence và reproduction:
Files thay đổi trong gói (tách thay đổi có sẵn):
Test đỏ trước / xanh sau, command và log:
Acceptance đạt / chưa đạt:
Rủi ro hoặc gates chưa chạy:
Contract bàn giao và gói tiếp theo:
```

Lỗi code trong gói phải xử lý trước khi sang gói phụ thuộc. Thiếu device/Console không chặn công việc host độc lập. Không tự đánh dấu external gate là PASS. Không cần chạy lại full suite nhiều lần nếu không có thay đổi mới; mỗi gói chạy focused tests, E05 chạy toàn bộ.

## 6. Prompt giao Gemini (Antigravity)

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_SESSION_GEMINI_ANTIGRAVITY_2026-10-04.md và thực hiện lần lượt E00 → E06 để sửa lỗi đang đăng nhập Gmail nhưng sau một thời gian mua VIP lại bị yêu cầu đăng nhập, phải logout mới mua được.

Tuân thủ mục 3 và scope từng gói. Giữ toàn bộ thay đổi có sẵn. Không làm song song trên các file dùng chung. Viết regression tái hiện hành vi cũ trước khi sửa; dùng production seams, không sao chép logic vào test. Mỗi gói chạy focused tests, ghi REPORT_Exx.md và PROGRESS.md rồi tự chuyển gói kế tiếp khi acceptance đạt, không hỏi lại từng gói.

Ưu tiên reauthentication bằng pipeline Google hiện có, không bắt logout, không bỏ token expiry/backend verification, không cấp VIP khi login thành công. Kiểm tra đúng owner trước mọi commit/claim/migrate; bảo toàn cancellation và stale callback guards. Nối cả hai nút AccountDetailDialog, callback action-only và đúng RESTORE/UPGRADE ở cả Credential Manager lẫn fallback.

Thiếu thiết bị/Play access thì ghi rõ NOT RUN hoặc BLOCKED_EXTERNAL, hoàn tất phần host độc lập và tổng hợp nhu cầu một lần. Không tự mua thật, đổi OAuth/signing/R8/version/dependencies/backend, commit/push hay phát hành. Kết thúc bằng REPORT_VIP_SESSION_FINAL.md nêu files, tests thực chạy, defects đã sửa và các device/release gates còn thiếu.
```

Prompt tiếp tục một gói riêng: `Đọc PLAN_FIX_VIP_SESSION_GEMINI_ANTIGRAVITY_2026-10-04.md, PROGRESS.md và báo cáo gói trước; chỉ thực hiện E{xx}, giữ đúng phạm vi, regression và acceptance; ghi REPORT_E{xx}.md rồi dừng ở checkpoint của gói.` Thay `{xx}` bằng 00–06.
