# Kế hoạch sửa đăng nhập Google Play, VIP và Drive cho mô hình nhỏ

Ngày: 25/09/2026. Repository: `E:\DU AN AI\T-Scanner`; module `:app`.
Nguồn: `RECHECK_VIP_LOGIN_2026-09-25.md`, đối chiếu lại các nhánh chính khi lập kế hoạch.

## Trạng thái và nguyên tắc

- Chỉ lập kế hoạch, chưa cho phép triển khai production qua tài liệu này. Khi được giao thực hiện, chỉ làm một mã gói rồi báo cáo và dừng; không tự chạy toàn bộ danh sách.
- Người dùng xác nhận bản lỗi được cài từ Google Play. Chưa biết biểu hiện chi tiết/status code, chưa có log thiết bị và quyền đọc Console. Không mặc định SHA-1 sai; không đổi client ID để thử may rủi.
- Checkout có nhiều thay đổi có sẵn. Lưu `git status --short` và diff các file được giao trước khi sửa. Không reset/clean/checkout đè, không format cả file, không cập nhật dependency ngoài phạm vi.
- Thực hiện tuần tự vì nhiều gói chạm AppAuthManager/MoreFragment. Không giao hai mô hình cùng sửa các file này.
- Test phải gọi logic production thực sự. Có thể tách seam nhỏ được production sử dụng; không viết helper trong test sao chép thuật toán rồi coi đó là regression.
- Mỗi gói ghi `REPORT_VIP_LOGIN_<ID>.md`: phạm vi, thay đổi, bằng chứng trước/sau, lệnh và kết quả, giới hạn, điểm cần bàn giao. Không ghi token/email thật vào báo cáo.
- Kết quả 38/38 test ở lượt audit là baseline trước sửa; không thay thế kiểm thử từng gói. Không chạy lại build trong lượt chỉ viết kế hoạch này.

## Trình tự

`V00 → V01 → V02 → V03 → V04 → V05 → V06 → V07 → V08 → V09`.

V00 là thu thập bằng chứng; thiếu thiết bị/Console không chặn sửa các lỗi mã nguồn đã xác nhận V01–V07, nhưng chặn kết luận đăng nhập Google Play đã được khắc phục. V08 là nhánh an toàn sao lưu riêng, không phải điều kiện để xác định nguyên nhân OAuth. V10 là quyết định sản phẩm riêng, không gộp billing vào sửa đăng nhập.

| Gói | Kết quả | Phụ thuộc |
|---|---|---|
| V00 | Baseline và hồ sơ lỗi bản Play | Không |
| V01 | Nhận đủ kết quả đăng nhập, phân biệt lỗi/hủy | V00 ở mức có thể |
| V02 | Request Credential Manager hợp lệ, kết thúc đúng một lần | V01 |
| V03 | Tách xác thực danh tính khỏi quyền Drive | V02 |
| V04 | Loại đường demo nhận tài liệu thật | V03 |
| V05 | Nút VIP của khách mở đăng nhập và tiếp tục đúng luồng | V04 |
| V06 | Mọi điểm mở quyền Drive nhận và kiểm tra kết quả | V05 |
| V07 | UI nhận kết quả đồng bộ và yêu cầu cấp quyền lại | V06 |
| V08 | Snapshot gắn vòng đời work, không upload file sống | V07, phạm vi riêng |
| V09 | Tổng hợp host checks và nghiệm thu bản Play | V01–V08; OAuth cần V00 |
| V10 | Chốt trial hay thương mại, lên kế hoạch entitlement riêng | Quyết định sản phẩm |

## V00 — Chụp baseline và khoanh cấu hình Google Play

Chỉ ghi tài liệu `REPORT_VIP_LOGIN_V00.md`, không sửa production, OAuth hay Play Console.

1. Lưu status/diff phạm vi auth, versionCode/versionName hiện tại. Không suy phiên bản đang cài từ build.gradle.
2. Nếu có thiết bị: lấy phiên bản/package bản cài, thao tác đăng nhập một lần, ghi bước thất bại và mã lỗi. Chỉ thu log liên quan auth, che dữ liệu tài khoản/token.
3. Đối chiếu package `com.tscanner.app`, Web OAuth client hiện được ứng dụng dùng và project tương ứng. Với bản Play, đối chiếu Android OAuth client với SHA-1 ở Play Console → App integrity → App signing certificate. Không thay bằng upload certificate/debug certificate. Không in khóa ký/mật khẩu.
4. Ghi trạng thái consent/test users nếu liên quan đến lỗi thu được; kiểm tra cấu hình Drive khi xác thực đã qua nhưng cấp quyền thất bại.
5. Nếu thiếu quyền truy cập, ghi rõ ô chưa xác minh. Không yêu cầu gửi mật khẩu hay khóa ký. Nếu xác nhận mismatch, mô tả chính xác thay đổi Console cần thực hiện thành một thao tác riêng; không tự xoay khóa ký.

Nghiệm thu: có ma trận package / phiên bản thực tế / client project / chứng thư ký / status code với từng mục ghi confirmed hoặc pending. Không có test JVM nào chứng minh các giá trị Console đúng.

Điểm dừng: bàn giao V01 và danh sách chứng cứ runtime còn thiếu.

## V01 — Gói triển khai đầu tiên: không bỏ qua lỗi đăng nhập

**Bằng chứng:** `MoreFragment.kt:44–58` chỉ xử lý RESULT_OK. `AppAuthManager.handleGoogleSignInResult` có ApiException nhưng lỗi ngoài RESULT_OK không đi tới đó. Đây là lỗi mã xác nhận; chưa tái hiện trên máy người dùng.

**File được sửa:**

- `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
- `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` — chỉ phần chuyển kết quả đăng nhập và thông báo lỗi
- Nếu cần: `app/src/main/java/com/tscanner/app/utils/GoogleSignInResultRouter.kt` làm seam nhỏ production sử dụng
- `app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt`
- Các `app/src/main/res/values*/strings.xml` đang được dự án hỗ trợ, chỉ key lỗi cần bổ sung
- `REPORT_VIP_LOGIN_V01.md`

**Không làm:** đổi request/scope/client ID, đổi cơ chế VIP, xóa demo, migration dữ liệu, cập nhật SDK hay sửa Drive launcher trong gói này.

**Các bước:**

1. Đọc baseline V00; xác định callback thực tế được đăng ký trong onCreate.
2. Đưa kết quả có dữ liệu vào parser Google dù resultCode không phải RESULT_OK. Không xem mọi RESULT_CANCELED là người dùng tự hủy: Intent có thể mang status lỗi.
3. Chuẩn hóa Success / Cancelled / Failure. Khi null Intent + hủy thật, kết thúc yên lặng; khi dữ liệu bất thường, không crash và không báo đăng nhập thành công. Đảm bảo mỗi lần đăng nhập chỉ có một kết quả cuối.
4. Mã cấu hình 10 phải được mô tả trung tính, không khẳng định riêng SHA-1 debug hoặc test users. Log mã/type lỗi, không log ID token hay nội dung credential.
5. Rethrow CancellationException nếu động vào catch coroutine; không mở lại UI sau lifecycle cancellation.

**Regression bắt buộc:** success; RESULT_CANCELED có Intent mang status 10 → Failure được hiển thị; 12501 → Cancelled; Intent null; lỗi parser; callback không bị gọi đôi. Dùng seam ở ranh giới parser/SDK nếu JVM hiện không chạy Google Intent thật; seam phải được callback production sử dụng. Không assert riêng hằng số RESULT_OK.

**Nghiệm thu:** nhánh lỗi đến UI, hủy không biến thành lỗi cấu hình, không đổi tài khoản khi thất bại; test gọi dispatcher/parser adapter production và ghi rõ phần SDK chưa chạy. Nếu cần harness mới lớn, báo phạm vi trước khi thêm framework.

**Validation:** chạy class mới bằng tên đầy đủ và AppAuthCanonicalIdentityTest, AppAuthDriveAuthorizationTest; kiểm tra diff. Cần device test để xác nhận Intent Google thật. Báo cáo rồi dừng, chưa làm V02.

## V02 — Sửa request Credential Manager và vòng đời đăng nhập

**Bằng chứng:** `AppAuthManager.kt:306–307` ghép GetSignInWithGoogleOption và GetGoogleIdOption; catch quay về classic. MoreFragment hiện gọi classic trước.

**Phạm vi:** AppAuthManager, MoreFragment phần performGoogleSignIn, seam mới `GoogleCredentialRequestFactory.kt` nếu cần, `GoogleCredentialRequestFactoryTest.kt` / `GoogleLoginFlowTest.kt`, báo cáo V02. Không đổi dependency hay tự chuyển toàn bộ SDK authorization.

**Thực hiện:** explicit-button request chỉ có một GetSignInWithGoogleOption; giữ một thứ tự gọi rõ ràng, không classic → credential → classic vô hạn. Hủy/lifecycle cancellation không fallback. Chặn nhấn liên tục tạo nhiều phiên; stale completion không ghi đè phiên mới. Không báo lỗi đăng nhập cho lỗi đồng bộ sau khi phiên đã lưu thành công.

**Regression:** kiểm tra credentialOptions của request factory production; success/cancel/error; fallback tối đa một lần; nhấn đúp; hủy scope trong khi chờ. Nghiệm thu: đúng một kết quả cuối, không giữ Activity đã hủy. Chạy tests V01 và mới; dừng sau báo cáo.

Tham chiếu kỹ thuật: https://codelabs.developers.google.com/sign-in-with-google-android — explicit button không trộn option. Đối chiếu API của phiên bản dependency đang dùng trước sửa.

## V03 — Tách đăng nhập và quyền Drive

**Bằng chứng:** `AppAuthManager.kt:213` xin drive.file ngay ở getGoogleSignInIntent; có buildGoogleDriveSignInOptions riêng.

**Phạm vi:** AppAuthManager phần options và hậu đăng nhập; AppAuthDriveAuthorizationTest và `GoogleIdentityOptionsTest.kt`; báo cáo V03. Có thể tách options builder nhỏ để test, không đổi client ID.

**Thực hiện:** options đăng nhập danh tính không yêu cầu drive.file; options Drive vẫn yêu cầu scope và bind tài khoản hiện tại. Sau đăng nhập thiếu quyền Drive vẫn giữ tài khoản, không tự enqueue backup chắc chắn sẽ thiếu quyền. Không cho Free chạy cloud chỉ vì đăng nhập thành công.

**Regression:** inspect options production; user Free, VIP thiếu quyền, VIP có quyền; từ chối Drive không đăng xuất; cấp quyền nhầm tài khoản không sync. Nghiệm thu: tài khoản và quyền Drive độc lập; giữ kiểm tra canonical ID/owner. Dừng sau tests và báo cáo.

## V04 — Chặn demo lấy tài liệu khách

**Bằng chứng:** MoreFragment.showSignInErrorDialog đề nghị demo; AppAuthManager.signInWithDemoAccount gọi claimGuestDocuments bằng ID demo.

**Phạm vi:** MoreFragment error dialog; AppAuthManager demo entry; `DemoAccountIsolationTest.kt`; strings liên quan; báo cáo V04. Chỉ đọc DocumentRepo; không thay quy tắc owner cho tài khoản thật.

**Thực hiện:** bỏ nút demo khỏi luồng khắc phục login production, API demo không được claim tài liệu thật; nếu giữ công cụ demo nội bộ phải cô lập dữ liệu. Không tự chuyển tài liệu đã mang owner demo sang tài khoản Google bất kỳ.

**Regression:** tạo tài liệu guest/A/B qua repository thật, gọi demo entry còn tồn tại; tất cả owner/bytes giữ nguyên. Login thật chỉ claim guest. Nghiệm thu: lỗi auth không cung cấp đường ghi owner demo. Liệt kê cách phục hồi tài liệu demo cũ như công việc riêng cần xác định chủ sở hữu, không xóa chúng. Dừng.

## V05 — Nối nút VIP của khách tới đăng nhập

**Bằng chứng:** VipUpgradeDialog nhánh khách chỉ Toast/dismiss; Home mở dialog trực tiếp.

**Phạm vi:** VipUpgradeDialog; MainActivity/MoreFragment/HomeFragment cho điều hướng đăng nhập; `VipLoginContinuationTest.kt`; các callsite VipUpgradeDialog trong PdfViewerActivity, IdCardComposeActivity, CreatePdfDialog chỉ để truyền callback cùng hợp đồng; báo cáo V05. Không sửa thuật toán xuất file/ảnh.

**Thực hiện:** thêm callback yêu cầu đăng nhập rõ ràng; dùng một tuyến đăng nhập có kết quả. Nếu điều hướng qua More, truyền pending action nhỏ với SavedState/FragmentResult phù hợp, không giữ lambda trỏ Activity qua vòng đời. Đăng nhập thành công trở lại bước xác nhận kích hoạt; không tự gia hạn/cấp VIP chỉ vì đăng nhập. Hủy giữ Free và tài liệu.

**Regression:** guest → login → mở xác nhận đúng một lần; cancel; xoay màn hình; pending action cũ sau đổi tài khoản; bấm lại. Nghiệm thu runtime tối thiểu Home và More; callsite Viewer/ID card/Create PDF không mất phiên chỉnh sửa. Nếu phạm vi callsite quá lớn, chia V05a hợp đồng + Home/More, V05b các callsite còn lại; mỗi lượt chỉ làm một nửa. Dừng.

## V06 — Nhận đầy đủ kết quả cấp quyền Drive

**Bằng chứng:** VipUpgradeDialog fallback context.startActivity không nhận kết quả; Home và dialog gia hạn hết hạn không truyền callback.

**Phạm vi:** VipUpgradeDialog; HomeFragment/MoreFragment; AccountDetailDialog; PdfViewerActivity/IdCardComposeActivity/CreatePdfDialog tại callsite VIP; AppAuthManager.handleDrivePermissionResult; `DriveAuthorizationFlowTest.kt`; báo cáo V06. Chia V06a Home/More, V06b các callsite khác nếu cần.

**Thực hiện:** bỏ fallback startActivity không có kết quả; host đăng ký launcher theo lifecycle. Mọi kết quả được parse/phân loại, kể cả lỗi trong RESULT_CANCELED. So khớp tài khoản và phiên đã khởi tạo yêu cầu; kết quả cũ sau sign-out/đổi tài khoản bị loại. Chỉ sync sau quyền hợp lệ.

**Regression:** grant đúng/sai account; cancel; lỗi có Intent; đổi account khi consent mở; cấp quyền hai lần không sync trùng. Nghiệm thu: mọi điểm mở dùng hợp đồng có callback, không crash lúc recreate. Dừng.

## V07 — Đưa trạng thái đồng bộ ra UI

**Bằng chứng:** AppAuthManager.runPostAuthorizationSync dùng callback rỗng; wrapper syncCatalogFromDrive chuyển AuthRequired/Failure thành 0.

**Phạm vi:** AppAuthManager, CloudBackupManager phần kết quả, MoreFragment/HomeFragment tại UI trạng thái, strings; `PostAuthorizationSyncResultTest.kt`; báo cáo V07. Không viết lại transport/download.

**Thực hiện:** dùng SyncCatalogResult hiện có; tách đăng nhập thành công với sync thất bại/partial/cần cấp quyền. Không tự lặp màn consent. Ràng buộc callback session/user và view lifecycle. Lỗi mạng không làm mất VIP/tài khoản.

**Regression:** Success(0), Partial, AuthRequired, Failure, duplicate request, stale result sau logout. Nghiệm thu: UI không gọi sync failure là thành công và có thao tác thử lại/cấp quyền thích hợp. Dừng.

## V08 — Snapshot an toàn cho sao lưu

**Bằng chứng:** CloudBackupManager.kt:84–85 xóa theo tuổi 24h; copy thất bại vẫn enqueue file sống. Worker đã giữ snapshot khi retry nhưng không chống cleanup này.

**Phạm vi:** CloudBackupManager.enqueueBackup, GoogleDriveBackupWorker, helper mới `BackupSnapshotStore.kt`, `BackupSnapshotLifecycleTest.kt`; báo cáo V08. Không sửa đồng bộ metadata/tombstone hay mở rộng schema DocumentRepo trừ khi báo phụ thuộc trước.

**Thực hiện:** snapshot bắt buộc, gắn work identity; chỉ enqueue khi snapshot bền vững, không fallback file sống. Không xóa snapshot work đang pending/running/retry dù quá 24h. Terminal/cancel/replaced work có cleanup an toàn; orphan cleanup xác minh không còn work dùng. Không coi lỗi truy vấn WorkManager là bằng chứng work đã xong.

**Regression production + fault injection:** copy lỗi → không enqueue; retry >24h + enqueue khác → snapshot còn; nguồn thay đổi sau enqueue → bytes upload giữ nguyên; cancel/replace và retry; process recreation; lookup work lỗi → không xóa. Kiểm thử phải chạm đường enqueue/worker hoặc adapter production, không chỉ helper độc lập. Nghiệm thu: giữ CAS owner/revision hiện có, có chính sách dọn orphan không mất job. Dừng.

## V09 — Cổng nghiệm thu

**Phạm vi:** báo cáo V09, tests bổ sung chỉ cho lỗi đã sửa; không chỉnh production tiện tay. Nếu tìm lỗi mới, lập gói hẹp riêng.

1. Chạy toàn bộ `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain` với `GRADLE_USER_HOME=C:\Users\nguye\.gradle`; ghi số tests/failures và lint thực tế. Dùng tên lớp đầy đủ cho --tests, tránh wildcard bị Windows mở rộng.
2. Không ghi “Google Play đã sửa” chỉ vì debug pass. Chuẩn bị artifact đúng versionCode, chữ ký/config release và quy trình test trên track Play do chủ dự án quản lý; không tự publish.
3. Ma trận thiết bị bản Play: tài khoản mới/cũ; đăng nhập thành công/hủy/lỗi; không quyền Drive và từ chối quyền; grant lại Home/More; khách có tài liệu; đổi A→B; logout lúc consent/sync; xoay màn hình; hết hạn VIP; export Free/VIP; offline/retry snapshot; restart process.
4. Máy mới đăng nhập cùng Google: ghi đúng giới hạn VIP local nếu chưa có V10; không coi restore VIP là đạt.

Nghiệm thu: từng ô PASS/FAIL/NOT RUN kèm bằng chứng. Chỉ đóng lỗi login khi login thành công trên bản Play phù hợp và tái kiểm tra tình huống lỗi gốc. Thiếu thiết bị/Console → phần host hoàn thành, runtime còn mở.

## V10 — Quyết định entitlement riêng, không triển khai cùng login

L07 là giới hạn trial lưu cục bộ, chưa xác nhận là sai yêu cầu sản phẩm. Gói này chỉ viết tài liệu lựa chọn, không thêm billing/server.

- Nếu giữ trial: mô tả trung thực phạm vi một thiết bị và chính sách gia hạn; giữ dữ liệu VIP hiện có.
- Nếu bán VIP: cần kế hoạch riêng về sản phẩm Play Billing, xác minh mua, restore, refund/revoke, offline cache và migration entitlement cũ. Không coi email/idToken hoặc SharedPreferences là chứng từ mua.
- Chưa có quyết định → dừng V10 ở tài liệu; không chặn sửa login V01–V09.

## Mẫu bàn giao bắt buộc

```text
Gói: Vxx
Baseline: status/diff trước sửa; file có thay đổi sẵn
Đã sửa: file và hành vi, không liệt kê thay đổi ngoài phạm vi
Regression: test gọi production nào; trước sửa thất bại ra sao; sau sửa kết quả
Validation: lệnh, exit code, số test; đường dẫn log/report
Chưa kiểm tra: thiết bị/Play/Drive/lifecycle nào
Rủi ro hoặc phụ thuộc còn lại:
Điểm dừng: không thực hiện gói tiếp theo
```

## Prompt giao V01 cho mô hình nhỏ

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_SMALL_MODEL_2026-09-25.md, thực hiện DUY NHẤT V01 khi tôi giao triển khai gói này. Đọc báo cáo RECHECK_VIP_LOGIN_2026-09-25.md và REPORT_VIP_LOGIN_V00.md nếu có. Đây là bản người dùng cài từ Google Play; chưa biết status lỗi, không tự kết luận SHA-1 sai.

Chụp status/diff và bảo toàn thay đổi có sẵn. Chỉ sửa các file V01 cho phép. Sửa đường nhận kết quả đăng nhập để lỗi mang trong Intent không bị RESULT_OK gate bỏ qua; phân biệt hủy/lỗi/success và không gọi kết quả hai lần. Test phải chạy dispatcher/parser adapter production được UI sử dụng. Không đổi scope/client ID/SDK, không sửa VIP/Drive/demo trong gói này. Không log token hay dữ liệu tài khoản.

Chạy tests V01 và các auth regression bằng tên lớp đầy đủ. Ghi REPORT_VIP_LOGIN_V01.md với bằng chứng trước/sau và phần runtime chưa kiểm tra. Nếu cần mở rộng phạm vi, giải thích phụ thuộc và dừng phần đó. Hoàn thành V01 thì dừng, không tự làm V02, không publish.
```

Prompt cho gói sau: “Đọc kế hoạch trên và báo cáo gói trước; thực hiện DUY NHẤT Vxx theo phạm vi, regression, nghiệm thu và mẫu bàn giao đã định. Kiểm tra phụ thuộc đã đạt. Bảo toàn thay đổi có sẵn, báo cáo và dừng.” Mỗi lần chỉ thay Vxx bằng một gói; không giao một lượt V01–V09.
