# Sửa VIP/login vòng 2 — gói tuần tự cho mô hình nhỏ

## Phạm vi và bằng chứng

Repository `E:\DU AN AI\T-Scanner`, module `:app`. Đọc trước `RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md`. Kế hoạch này tiếp nối, không làm lại toàn bộ V00–V09.

- R01–R04: đã tái hiện bằng 4 probe gọi production, cả 4 thất bại.
- R05–R06: xác nhận qua callsite, chưa chạy UI thiết bị.
- R07–R08: rủi ro từ mã, chưa đo ANR/chạy fault injection cache; phải kiểm chứng trước khi tuyên bố đã tái hiện.
- Baseline lượt audit: 565 test đạt; lint 0 errors/748 warnings; build debug qua. Đây là kết quả trước sửa, không phải kết quả nghiệm thu mới.
- Người dùng cài từ Google Play. Chưa có status lỗi thực tế hoặc kiểm chứng Console/chứng thư bản Play. Không tự đổi client ID, khóa ký, cấu hình OAuth hay publish.

Chỉ làm **một gói mỗi lượt được giao**, báo cáo rồi dừng. Không chạy song song các gói vì chung AppAuthManager/callsite. Lập kế hoạch không có nghĩa được phép thực hiện production ngay.

## Quy tắc chung cho mọi gói

1. Chụp status/diff của file được giao, giữ thay đổi có sẵn; không reset/clean, format toàn dự án hoặc đổi dependency để tiện test.
2. Không thêm billing, không đổi chính sách trial, không refactor OCR/scan/PDF không liên quan.
3. Regression phải gọi production được ứng dụng sử dụng. Mock ở biên Google/Android/WorkManager; không mô phỏng lại thuật toán trong helper test.
4. Giữ 4 probe gốc và XML trong `build/vip-login-reaudit`. Đưa các ca liên quan thành test lâu dài dưới app/src/test; không chép toàn bộ fixture kèm các test không cần thiết. Khi đổi API có thể cập nhật adapter test, nhưng không đổi điều kiện mong đợi để cho xanh.
5. Test rủi ro bất đồng bộ phải điều khiển được thứ tự bằng deferred/latch/test scheduler có sẵn, tránh sleep mong may mắn.
6. Mỗi gói xuất `REPORT_VIP_LOGIN_ROUND2_Sxx.md`: file sửa, baseline, regression trước/sau, lệnh/exit code/số test, phần chưa chạy, điểm bàn giao. Thiếu dependency cần thiết thì báo rõ, không lấn gói khác.
7. Chạy focused tests mới + regression liên quan bằng tên lớp đầy đủ; không dùng wildcard Windows. Chỉ tổng hợp toàn bộ build/lint ở S09 hoặc khi thay đổi đòi hỏi.

## Thứ tự

| Gói | Phạm vi chính | Đóng phát hiện |
|---|---|---|
| S00 | Bảo toàn bằng chứng, chuyển 4 probe thành regression | Baseline |
| S01 | Token phiên và kết quả đăng nhập cũ | R01 |
| S02 | Khóa đăng nhập và coroutine không khởi chạy | R02 |
| S03 | Tách lỗi provider, commit và callback | R03 |
| S04 | Gắn kết quả Drive với đúng yêu cầu | R04 |
| S05a | Đăng nhập VIP từ Viewer/Create PDF | R05 một phần |
| S05b | Đăng nhập VIP từ ID card | R05 còn lại |
| S06a | Free không bị báo lỗi đồng bộ giả | R06 một phần |
| S06b | Kết quả sync tới mọi UI VIP | R06 còn lại |
| S07 | Đưa snapshot/WorkManager I/O khỏi UI | R07 |
| S08 | Lưu snapshot theo vòng đời work bền vững | R08 |
| S09 | Regression tổng hợp và nghiệm thu Play | Toàn bộ |

Chạy theo thứ tự trên; S05b tái dùng hợp đồng S05a, S06b dùng kết quả S06a, S08 giữ các bảo vệ session của S07. Không giao cùng lúc S01–S09.

## S00 — Bảo toàn baseline và regression

**Chỉ được sửa/thêm:** `app/src/test/java/com/tscanner/app/VipLoginRound2RegressionTest.kt`, fixture test nhỏ nếu cần; `REPORT_VIP_LOGIN_ROUND2_S00.md`. Không sửa production.

Đọc `build/vip-login-reaudit/VipLoginReauditProbeTest.kt` và `probe-results.xml`. Chuyển bốn ca `probeCancelledScopeMustNotLockFutureSignIn`, `probeSessionInvalidationMustRejectPendingCredential`, `probeSuccessCallbackFailureMustNotRestartLogin`, `probeDriveResultWithoutPendingRequestMustNotSucceed` thành test thường gọi AppAuthManager. Giữ nguyên kỳ vọng: không lock; không đăng nhập phiên cũ; không fallback sau success; không nhận Drive result thiếu request.

**Nghiệm thu:** ghi chính xác số ca đỏ hiện tại. Bốn ca đỏ là baseline mong đợi, không sửa test cho qua. Bổ sung kiểm soát login hợp lệ và Drive request hợp lệ để loại fixture luôn thất bại. Nếu fixture thay đổi khiến không chạy được, xử lý harness trước, không tính lỗi compile là tái hiện.

**Dừng:** bàn giao S01, ghi các test dự kiến còn đỏ cho đến S04. Không yêu cầu suite toàn bộ xanh khi đang cố ý giữ regression chưa sửa.

## S01 — Token yêu cầu đăng nhập và vô hiệu hóa phiên

**Bằng chứng R01/P1:** AppAuthManager.cancelSignInProgress chỉ reset boolean; credential chỉ so request generation, không so session generation. Classic result không có token yêu cầu. Probe invalidation rồi trả credential cũ vẫn lưu user.

**File cho phép:** AppAuthManager.kt; MoreFragment.kt chỉ phần launch/result login; helper mới `utils/GoogleLoginAttempt.kt` nếu cần; GoogleLoginFlowTest.kt, GoogleSignInResultRouterTest.kt, VipLoginRound2RegressionTest.kt; báo cáo S01. Tất cả production trong `app/src/main/java/com/tscanner/app/`, tests trong `app/src/test/java/com/tscanner/app/`.

**Thực hiện:**

1. Dùng token bất biến: request ID, session generation lúc bắt đầu; classic fallback cùng attempt, không tự tạo phiên mới.
2. Hủy/logout/đổi tài khoản vô hiệu hóa attempt. Kiểm tra token ngay trước lưu user, claim guest, cập nhật currentUser, dispatch success và mở fallback; serialize check + mutation trên cùng cơ chế điều phối.
3. Phân biệt session đổi do chính commit hợp lệ với session đổi từ bên ngoài. Không để attempt hợp lệ tự bị loại sau khi processSignedInAccount tăng generation.
4. Result launcher nhận đúng token host đã khởi tạo; không lấy token hiện hành để gán cho result cũ. Chọn chính sách recreation rõ ràng: giữ request token qua saved state khi có thể, hoặc bỏ kết quả cũ và cho thử lại. Không giữ Activity/lambda trong singleton qua recreation.
5. Chỉ attempt đang sở hữu trạng thái mới được kết thúc/mở khóa; stale callback không reset trạng thái của attempt mới. Giữ các API được test cần thiết qua adapter an toàn, không để overload bypass check.

**Regression:** credential cũ sau session invalidation; classic cũ sau cancel; A cũ trả trong lúc B đang chờ; stale exception không mở fallback; valid login vẫn claim guest đúng một lần; kiểm tra owner/prefs không đổi trong ca stale.

**Nghiệm thu:** R01 xanh; các regression identity hiện hữu qua. R02–R04 có thể vẫn đỏ, phải ghi rõ. Chưa làm cleanup Job của S02 trừ hook tối thiểu dùng chung token. Dừng.

## S02 — Cleanup khóa kể cả coroutine chưa chạy

**Bằng chứng R02/P2:** compareAndSet trước launch, reset trong coroutine; scope đã cancel khiến body không chạy và khóa giữ mãi.

**File:** AppAuthManager.kt và GoogleLoginAttempt.kt nếu S01 tạo; GoogleLoginFlowTest.kt, VipLoginRound2RegressionTest.kt; báo cáo S02.

**Thực hiện:** completion handler có quyền sở hữu token để cleanup Job kể cả không vào body; scope đã cancel không giữ attempt. Phân biệt coroutine kết thúc do chuyển sang classic fallback với attempt đăng nhập hoàn tất: không mở khóa trong lúc Intent còn đang chờ. Không dùng reset boolean vô điều kiện trong finally của request cũ.

**Regression:** canceled-before-launch; cancel-during-await; fallback chờ Intent vẫn chặn double tap; A bị hủy rồi B bắt đầu, completion A không mở khóa B; hủy thì lần thử kế tiếp chạy được.

**Nghiệm thu:** R02 xanh, R01 không tái phát; không giữ lock vĩnh viễn và không tạo hai login cùng lúc. Dừng.

## S03 — Không biến lỗi hậu đăng nhập thành lỗi provider

**Bằng chứng R03/P2:** try bao cả processSignedInAccount/onSuccess; callback throw làm gọi fallback dù user đã lưu. Classic catch cũng có thể gọi onError sau onSuccess.

**File:** AppAuthManager.kt; GoogleLoginFlowTest.kt, GoogleSignInResultRouterTest.kt, VipLoginRound2RegressionTest.kt; báo cáo S03. Không thay code sync ở gói này.

**Thực hiện:** giới hạn catch provider quanh lời gọi SDK; lỗi commit có đường báo riêng và không tự fallback để thử login lần nữa; callback hậu success ngoài catch xác thực. Định nghĩa rõ lỗi callback được xử lý/log bởi boundary nào, không nuốt CancellationException. Terminal success/cancel/failure chỉ một lần cho mỗi attempt.

**Regression:** onSuccess throw trên credential và classic → không fallback/onError xác thực lần hai; provider lỗi hợp lệ → fallback tối đa một lần; lỗi lưu/commit không bị gọi là provider error; user cancel không fallback. Nghiệm thu R03 xanh, account đã commit không bị login lại vì lỗi UI. Dừng.

## S04 — Drive authorization có request ID và consume một lần

**Bằng chứng R04/P2:** pendingSnapshot null lại lấy current email/generation; result không có request vẫn success.

**File:** AppAuthManager.kt; DriveAuthorizationResultRouter.kt; MoreFragment.kt, HomeFragment.kt, PdfViewerActivity.kt, IdCardComposeActivity.kt chỉ các launcher Drive; DriveAuthorizationFlowTest.kt, AppAuthDriveAuthorizationTest.kt, VipLoginRound2RegressionTest.kt; helper `DriveAuthorizationAttempt.kt` nếu cần; báo cáo S04.

**Thực hiện:** mỗi launcher giữ request ID + user ID/email + generation đã bắt đầu. Không dùng snapshot singleton bị request khác ghi đè mà không phát hiện. Không có request → bỏ result an toàn; consume chỉ một lần; kiểm tra tài khoản hiện tại và scope. Hoàn tất/hủy/launch lỗi đều cleanup request đúng chủ. Không suy ra request cũ từ current user sau process recreation; saved state hoặc bắt đầu lại rõ ràng.

**Regression:** thiếu request; duplicate cùng request; A/B host chồng lấn; logout/switch account; đúng request đúng account; sai scope; result sau recreation. Nghiệm thu R04 xanh, 4 probe đỏ ban đầu đều đã xanh; các test cũ từng giả định success không cần request phải sửa setup tạo request thật, không bỏ kiểm tra an toàn.

Nếu cần giảm kích thước: S04a contract + manager/router/tests; S04b cập nhật đồng bộ các launcher. Chỉ công bố S04 hoàn tất sau S04b, không để API production bị bypass trong bản tích hợp. Dừng.

## S05a — VIP login tại Viewer và Create PDF

**Bằng chứng R05/P2:** PdfViewerActivity.kt:240 và CreatePdfDialog.kt:58 không truyền onRequestSignIn.

**File:** PdfViewerActivity.kt, ui/dialogs/CreatePdfDialog.kt, VipUpgradeDialog.kt, VipLoginContinuationHandler.kt; tuyến login dùng chung nhỏ nếu cần; `VipViewerLoginContinuationTest.kt`; báo cáo S05a. Chỉ sửa MainActivity/MoreFragment nếu cần giao tiếp kết quả, không đổi điều hướng tab không liên quan.

**Thiết kế bắt buộc trước sửa:** mô tả cách host đang xuất file nhận kết quả đăng nhập mà không mất trang, watermark preference và tên file đang gõ. Không đơn thuần mở MainActivity mới rồi bỏ phiên Viewer. Hợp đồng login được tái dùng bởi S05b; bảo toàn token S01 và lifecycle S02.

**Thực hiện:** truyền callback đăng nhập ở tất cả đường Viewer/Create PDF → login thành công mở lại xác nhận VIP đúng một lần → người dùng tự xác nhận; không tự cấp VIP. Cancel/failure giữ nội dung và state, không lặp dialog. Saved-state continuation không giữ Activity reference.

**Regression:** callback thật được nối; guest success/cancel/error; tài khoản đổi khi chờ; tên file và trang giữ nguyên; recreate. Test resolver đơn lẻ không chứng minh callsite đã nối: kiểm tra tích hợp host hoặc instrumentation khi có thiết bị.

**Nghiệm thu:** cả nút watermark Viewer và hộp tạo PDF mở được login; runtime chưa chạy phải ghi NOT RUN. Dừng trước S05b.

## S05b — VIP login tại ID card

**File:** IdCardComposeActivity.kt, VipLoginContinuationHandler.kt chỉ nếu cần adapter; `VipIdCardLoginContinuationTest.kt`; báo cáo S05b. Tái dùng hợp đồng S05a, không tạo flow auth riêng.

**Bằng chứng:** IdCardComposeActivity.kt:445 không truyền onRequestSignIn.

**Regression/nghiệm thu:** khách → VIP → login → xác nhận, cancel và recreate; giữ mặt trước/mặt sau, layout/currentConfig; không tự bỏ watermark trước khi VIP active. Rà tất cả VipUpgradeDialog callsite để bảo đảm không còn guest fallback chỉ Toast. Nếu phát hiện điểm khác, liệt kê rõ để giao riêng, không tự mở rộng. Dừng.

## S06a — Free login không hiển thị sync failure giả

**File:** AppAuthManager.kt; CloudBackupManager.kt chỉ sealed result nếu chọn thêm Skipped; MoreFragment.kt/HomeFragment.kt và mọi when bắt buộc compile; PostAuthorizationSyncResultTest.kt; báo cáo S06a.

**Bằng chứng R06:** Free trả Failure rồi More hiện toast sync failed sau login thành công.

**Thực hiện:** chọn một hợp đồng duy nhất: Skipped(NotVip) hoặc gate invocation rõ ràng. Phân biệt Free không cần sync, Success(0), AuthRequired, Partial và Failure thật. Không gọi success giả để im thông báo. Không enqueue cloud của Free.

**Regression:** guest/Free/VIP hết hạn/VIP có quyền/VIP thiếu quyền; Free login không lỗi sync, VIP lỗi mạng vẫn nhận Failure và giữ entitlement. Test production và UI mapping được caller dùng. Dừng.

## S06b — Truyền kết quả sync đến mọi điểm VIP

**File:** VipUpgradeDialog.kt, PdfViewerActivity.kt, IdCardComposeActivity.kt, HomeFragment.kt/MoreFragment.kt tại callback; helper trình bày kết quả chung nếu cần; PostAuthorizationSyncResultTest.kt và test host tương ứng; strings của locale hiện hỗ trợ; báo cáo S06b.

**Bằng chứng:** VipUpgradeDialog gọi runPostAuthorizationSync không callback khi đã có quyền; Viewer/ID card bỏ result sau consent.

**Thực hiện:** callback typed hoặc coordinator kết quả về host có lifecycle; AuthRequired có thao tác cấp quyền, Failure có thử lại; không tự mở consent lặp. Không giữ dialog đã dismiss hoặc Activity cũ trong công việc nền. Kết quả phiên trước không cập nhật UI phiên mới.

**Regression:** success 0/partial/auth/network cho từng route; dialog đã đóng; logout khi sync; duplicate callbacks không lặp UI. Nghiệm thu: không còn callsite fire-and-forget ở luồng VIP này; nếu nền chủ ý im lặng thì có trạng thái đọc được và ghi rõ. Dừng.

## S07 — Không block UI vì snapshot/WorkManager

**Bằng chứng R07/rủi ro:** enqueueBackup đồng bộ từ callback UI, cleanup gọi Future.get mỗi snapshot và copy PDF; batch quét lặp. Chưa đo ANR.

**File:** CloudBackupManager.kt, BackupSnapshotStore.kt, AppAuthManager.kt phần hậu login; các caller enqueue bị đổi hợp đồng nếu thực sự cần và liệt kê trước; BackupSnapshotLifecycleTest.kt, `CloudBackupDispatchTest.kt`; báo cáo S07.

**Thực hiện:** trước sửa dựng test production với lookup/copy bị giữ bằng latch, chứng minh lời gọi UI không được phải chờ. Chạy I/O trên dispatcher IO có lifecycle công việc rõ; cleanup một lần mỗi batch. Future chờ có giới hạn/cancellation; timeout/lookup error giữ file. Mang owner/session/revision snapshot từ lúc yêu cầu, recheck trước enqueue; chuyển async không được làm tài liệu A chuyển vào Drive B.

**Regression:** lookup chậm không chặn caller UI; cancel; account đổi trong lúc copy; lookup timeout không delete; batch N file chỉ cleanup một lượt; callback về Main. Nghiệm thu: không còn block/copy đồng bộ trên UI ở các đường đã sửa; không tuyên bố hết ANR toàn app. Dừng.

## S08 — Snapshot không phụ thuộc cache dễ bị thu hồi

**Bằng chứng R08/rủi ro:** BackupSnapshotStore.getSnapshotDir dùng cacheDir; worker fail khi cache file mất. Không đổi lại sang upload file sống.

**File:** BackupSnapshotStore.kt, CloudBackupManager.kt, GoogleDriveBackupWorker.kt; BackupSnapshotLifecycleTest.kt, `BackupSnapshotStorageRecoveryTest.kt`; báo cáo S08. Không sửa quyền sở hữu catalog hoặc metadata Drive.

**Thực hiện:** tạo snapshot mới trong app-private persistent/no-backup storage phù hợp job, publish atomic sau copy/flush hoàn tất; tận dụng writer có sẵn nếu phù hợp. Gắn vòng đời work, cleanup terminal/orphan an toàn. Chỉ thay directory không đủ: xử lý work cũ còn giữ absolute cache path; không move/delete file đang được worker sử dụng. Work cũ thiếu snapshot phải báo fail có thể retry bằng snapshot mới sau kiểm tra revision, tuyệt đối không gán bytes hiện tại cho revision cũ.

**Regression:** cache bị xóa → work mới vẫn upload đúng bytes; work cũ path cache còn tồn tại vẫn đọc; work cũ mất file không upload live; source đổi sau enqueue; hết dung lượng/copy/rename lỗi không enqueue partial; recreate giữ snapshot; terminal cleanup; lookup error giữ file. Test phải qua enqueue/worker production.

**Nghiệm thu:** chứng minh phạm vi durability thực tế, không tuyên bố chịu mọi power loss chỉ từ JVM tests; giữ no-live-fallback và CAS hiện có. Dừng.

## S09 — Kiểm tra tổng hợp và báo cáo đúng mức bằng chứng

**File:** báo cáo S09, cập nhật REPORT_VIP_LOGIN_V09.md hoặc đính chính bằng liên kết rõ; tests phục vụ nghiệm thu. Không sửa production trong gói kiểm tra; nếu có lỗi mới thì lập gói riêng.

1. Chạy toàn bộ test/lint/assembleDebug. Nếu test UP-TO-DATE thì chạy riêng test với --rerun để có kết quả thực thi mới. Ghi số ca thực tế, không giữ cứng mốc 565.
2. Bốn regression gốc phải xanh và có kiểm soát thành công; kiểm tra bổ sung Classic callback, request A/B, scope cancellation, các callsite thực tế.
3. Đối chiếu từng R01–R08: PROBE PASS / CODE VERIFIED / DEVICE PASS / NOT RUN. Không dùng “100% không còn lỗi” từ host checks.
4. Kiểm tra bản release phù hợp, R8 và phiên bản artifact; không tự upload. Google Play cần bản test cài qua track, package + Play App Signing chứng thư đúng OAuth project. Không đổi cấu hình theo phỏng đoán.
5. Trên thiết bị: login thành công/hủy/status error; Home/More/Viewer/Create PDF/ID card; xoay màn hình; kill/recreate; đổi account/logout khi chờ; Drive deny/grant; offline retry; Free/VIP export; thiếu dung lượng/thu hồi cache. Ghi kết quả và artifact/version được thử.

Thiếu thiết bị/Console → kết thúc phần host kèm gate còn mở, không đóng lỗi Google Play ban đầu. Chính sách trial local vẫn giữ, không đánh dấu restore VIP máy mới là PASS.

## Lệnh và mẫu bàn giao

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound2RegressionTest --offline --console=plain
# S09:
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
```

Nếu cache lock bị chặn, xử lý quyền/cache phù hợp rồi chạy lại; không coi đó là lỗi app. Không chạy `clean` vì bằng chứng probe nằm trong build.

```text
Gói / baseline:
File có thay đổi sẵn và file vừa sửa:
Production path thực sự được test:
Trước sửa: assertions hoặc hiện tượng lỗi:
Sau sửa: command, exit code, counts, report:
Regression dự kiến còn đỏ thuộc gói sau:
Chưa chạy thiết bị/Play/Drive:
Phụ thuộc và điểm dừng:
```

## Prompt giao gói đầu tiên

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md và RECHECK_VIP_LOGIN_ROUND2_2026-09-25.md. Thực hiện DUY NHẤT S00 khi tôi giao triển khai gói này.

Bảo toàn working tree, không sửa production, không xóa build vì có bằng chứng. Chuyển 4 probe từ build/vip-login-reaudit thành regression lâu dài gọi AppAuthManager, giữ kỳ vọng gốc và bổ sung control thành công. Chạy bằng tên test đầy đủ, ghi số ca đỏ thực tế; không sửa test để che lỗi. Xuất REPORT_VIP_LOGIN_ROUND2_S00.md theo mẫu và dừng, không làm S01.
```

Prompt tái dùng cho gói tiếp theo:

```text
Đọc PLAN_FIX_VIP_LOGIN_ROUND2_SMALL_MODEL_2026-09-25.md, thực hiện DUY NHẤT [Sxx] và đọc báo cáo gói trước. Kiểm tra phụ thuộc đã đạt, giữ thay đổi sẵn, chỉ sửa file trong phạm vi. Viết regression gọi production, xác minh trước/sau; không đổi SDK/client ID, không thêm billing, không publish. Báo cáo test còn đỏ thuộc gói sau và gate thiết bị chưa chạy. Xuất REPORT_VIP_LOGIN_ROUND2_[Sxx].md rồi dừng.
```
