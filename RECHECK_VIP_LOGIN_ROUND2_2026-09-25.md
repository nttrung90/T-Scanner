# Rà soát độc lập sau V00–V09 — 25/09/2026

## Kết luận

Chưa đạt nghiệm thu. Bộ test hiện hữu chạy mới đạt 565/565 nhưng 4 probe bổ sung gọi production đều thất bại. Có thêm thiếu sót xác nhận qua callsite. Không sửa production hoặc test trong app/src/test; chỉ tạo probe/init script dưới build/vip-login-reaudit và báo cáo này. Giữ nguyên thay đổi có sẵn.

Không có thiết bị trong adb devices. Chưa xác minh đăng nhập bản Google Play, Play App Signing/OAuth Console, process death hay Google consent thật. Không kết luận SHA-1 sai.

## Lỗi tái hiện bằng probe production

### R01 — P1: Vô hiệu hóa phiên không vô hiệu hóa kết quả Credential Manager

- AppAuthManager.kt:61–63 chỉ reset boolean trong cancelSignInProgress. notifyUserSessionChanged tăng sessionGeneration, nhưng signInWithGoogle.kt:458–480 chỉ so signInRequestGeneration.
- Probe: bắt đầu credential đang chờ → tăng session generation và hủy progress → trả credential cũ. Kỳ vọng currentUser null; thực tế user `stale` được đăng nhập và lưu.
- Đây là mô phỏng trực tiếp invalidation/session boundary; không tuyên bố đã chạy UI logout trên Android. Classic handleGoogleSignInResult cũng không mang request ID để loại kết quả cũ.
- Hướng sửa: token yêu cầu chứa request ID + session generation; invalidate khi hủy/logout/đổi tài khoản; check trước mọi mutation và fallback. Chỉ request sở hữu lock mới được giải phóng lock, tránh kết quả cũ mở khóa request mới.
- Nghiệm thu: credential và classic result đến sau invalidation không lưu user, không claim tài liệu, không mở callback thành công.

### R02 — P2: Scope đã hủy làm khóa đăng nhập toàn cục

- AppAuthManager.kt:453–460 đặt isSignInInProgress trước coroutineScope.launch; reset nằm bên trong coroutine.
- Probe truyền CoroutineScope với Job đã cancel. Coroutine không chạy body nên lock vẫn true. Các lần đăng nhập tiếp theo bị từ chối bởi compareAndSet.
- Hướng sửa: quản lý cleanup bằng completion của Job và quyền sở hữu request; xử lý cả job không bắt đầu. Test scope đã hủy trước launch, hủy sau launch và request mới đang chạy.

### R03 — P2: Lỗi UI/sync sau đăng nhập thành công kích hoạt đăng nhập dự phòng

- AppAuthManager.kt:477–485 gọi processSignedInAccount và onSuccess trong try bao phủ toàn bộ; catch Exception ở cuối signInWithGoogle gọi onFallbackToIntent.
- Probe credential hợp lệ, onSuccess ném lỗi hậu đăng nhập: tài khoản đã được lưu nhưng fallback vẫn gọi 1 lần (kỳ vọng 0).
- Hướng sửa: tách lỗi nhà cung cấp credential khỏi commit và callback hậu đăng nhập. Khi xác thực đã xong, lỗi sync/UI không được mở Google login lại. Classic handler cũng bao onSuccess trong catch và có thể gọi onError sau onSuccess; cần regression tương ứng.

### R04 — P2: Drive result không gắn yêu cầu còn sống vẫn được nhận

- AppAuthManager.kt:176–180 dùng pendingSnapshot.getAndSet(null), nhưng nếu null lại lấy email và generation hiện tại.
- Probe có current user nhưng không có pending authorization; trả result cùng email và drive.file qua parser seam. onSuccess được gọi 1 lần, kỳ vọng bỏ kết quả không tương quan.
- Duplicate callback sau khi snapshot đã consume cũng có cùng đường chấp nhận. Một AtomicBoolean cục bộ trong mỗi dispatch không chặn duplicate giữa hai invocation.
- Hướng sửa: request ID và snapshot theo từng launcher/host; không suy ra phiên khởi tạo từ phiên hiện tại khi mất snapshot. Cần chính sách khôi phục pending request qua recreation hoặc chủ động bỏ kết quả. Kiểm tra hiện tại không chứng minh có thể cấp quyền vào tài khoản sai; lỗi đã tái hiện là nhận kết quả không có yêu cầu chờ.

## Thiếu sót xác nhận qua mã, chưa chạy UI thiết bị

### R05 — P2: Khách vẫn không đăng nhập từ Viewer/ID card/Create PDF

- PdfViewerActivity.kt:240, IdCardComposeActivity.kt:445, CreatePdfDialog.kt:58 mở VipUpgradeDialog không truyền onRequestSignIn.
- VipUpgradeActionResolver trả ShowSignInRequiredPrompt khi callback null; dialog vẫn chỉ dismiss/Toast ở các đường này. Home/More đã được nối, nhưng V05 chưa hoàn tất toàn bộ callsite.
- Sửa bằng tuyến đăng nhập có continuation, bảo toàn phiên tài liệu đang sửa. Kiểm thử từng entry point, cancel và recreate; không tự cấp VIP sau login.

### R06 — P2: Báo sync sai ngữ cảnh và tiếp tục bỏ kết quả ở một số nơi

- AppAuthManager.runPostAuthorizationSync trả Failure("Chỉ dành cho tài khoản VIP") với Free. MoreFragment gọi ngay sau login và hiển thị Failure thành lỗi đồng bộ. Vì vậy tài khoản Free đăng nhập hợp lệ vẫn nhận thông báo sync thất bại dù không được yêu cầu sync.
- VipUpgradeDialog.kt:88 gọi runPostAuthorizationSync không callback khi đã có Drive; PdfViewerActivity.kt:69 và IdCardComposeActivity.kt:69 cũng bỏ kết quả sau consent. L08 chưa được xử lý toàn bộ.
- Cần trạng thái Skipped/NotApplicable hoặc gate ở caller cho Free; propagate typed result đến UI cho mọi đường VIP. Không chuyển AuthRequired thành thông báo thành công.

## Rủi ro snapshot còn mở, chưa fault-inject trong lượt này

### R07 — P2: I/O và chờ WorkManager trên luồng gọi UI

- runPostAuthorizationSync gọi enqueueBatchBackup đồng bộ từ callbacks UI. enqueueBackup gọi cleanOrphanSnapshots (CloudBackupManager.kt:84), rồi copy PDF.
- BackupSnapshotStore.kt:133 gọi getWorkInfosByTag(...).get() không timeout cho từng snapshot; batch lặp cleanup cho từng tài liệu. Có thể làm UI giật/ANR khi nhiều file hoặc DB chậm. Chưa đo ANR, không kết luận chắc chắn deadlock.
- Nên chạy toàn bộ cleanup/snapshot/enqueue trên IO với session snapshot, callback UI riêng; tránh quét toàn bộ store N lần cho batch N tài liệu.

### R08 — P2: Snapshot bắt buộc vẫn nằm trong cache có thể bị thu hồi

- BackupSnapshotStore.kt:40 dùng context.cacheDir. Worker.kt:103–113 fail nếu snapshot mất, không tạo lại hay enqueue mới. Do snapshot hiện bắt buộc, hệ thống xóa cache khi thiếu dung lượng có thể làm hỏng work retry.
- Nếu yêu cầu snapshot bền qua retry/process recreation, cần vùng lưu phù hợp vòng đời job, cleanup rõ ràng và test mất cache. Copy/rename không có fsync nên không đủ bằng chứng cho mọi tuyên bố durable sau sự cố nguồn điện.
- Điểm đã sửa đúng: bỏ xóa theo tuổi đối với work ACTIVE, giữ khi QUERY_ERROR; snapshot creation thất bại không enqueue file sống; worker không fallback file sống. Không phủ nhận các cải thiện này.

## Đối chiếu kế hoạch

| Gói | Kết quả độc lập |
|---|---|
| V00 | Hồ sơ có; thiết bị/Play Console còn pending |
| V01 | Đã bỏ RESULT_OK gate, route status; callback error boundary còn R03 |
| V02 | Factory một option đúng; request lifecycle còn R01/R02/R03 |
| V03 | Identity tách scope Drive; Free không enqueue nhưng báo Failure không phù hợp R06 |
| V04 | UI bỏ demo; demo không claim guest như trước |
| V05 | Home/More có continuation; callsite khác còn R05; rotation chưa nghiệm thu |
| V06 | Có launcher/router; request correlation còn R04 |
| V07 | More/Home nhận typed result; còn callsite bỏ kết quả R06 |
| V08 | Có cải thiện snapshot; R07/R08 và runtime gates còn mở |
| V09 | Host pass không đủ kết luận không còn lỗi; các probe hiện đã chỉ ra lỗi |
| V10 | Trial local vẫn là giới hạn, không phải phần đã triển khai billing |

REPORT_VIP_LOGIN_V09.md khẳng định V01–V08 hoàn tất 100% không còn tồn đọng là quá mức bằng chứng. Ma trận đánh host PASS cho rotation/process recreation/Play login không đồng nghĩa chạy các tình huống đó; một số dẫn chiếu như MoreFragmentTest/CloudBackupManagerTest cần xác minh có test tương ứng thực sự. Cần sửa trạng thái báo cáo theo bằng chứng thực tế.

## Bằng chứng kiểm thử

1. `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain`: BUILD SUCCESSFUL, chủ yếu UP-TO-DATE; lint XML 0 errors / 748 warnings.
2. Chạy mới `:app:testDebugUnitTest --rerun --offline --console=plain`: 565 tests, 0 failures. XML baseline được lưu ở build/vip-login-reaudit/baseline-results.
3. Probe dưới build/vip-login-reaudit/VipLoginReauditProbeTest.kt tái dùng fixture Context/Activity/Prefs của GoogleLoginFlowTest, thêm 4 ca gọi AppAuthManager production. Không copy thuật toán auth để test. Dùng injected credentialClient/parser tại biên SDK nên không phải OAuth/device test.
4. Chạy `-I build/vip-login-reaudit/audit.init.gradle :app:testDebugUnitTest` với --tests tên đầy đủ của 4 method probe: 4 tests, 4 assertion failures. Lần đầu cấu hình java sourceSet chưa đưa Kotlin probe vào compile nên “No tests found”; sau chuyển Kotlin sourceSet chạy được và thất bại đúng assertions, không phải lỗi compile.
5. XML thất bại lưu riêng tại build/vip-login-reaudit/probe-results.xml. Sau đó chạy lại suite bình thường không dùng init script để trả cấu hình test về chuẩn.
6. `adb devices`: không có thiết bị. Chưa test signed Play build/OAuth/Drive thật, UI lifecycle hay fault injection snapshot.

## Ưu tiên tiếp theo

Sửa request lifecycle R01/R02 → tách error boundary R03 → bind Drive request R04 → hoàn tất callsite và UI R05/R06 → xử lý snapshot R07/R08 → kiểm tra lại trên bản Play. Mỗi gói phải có regression production, không đóng lỗi chỉ vì suite cũ xanh. Lượt này chỉ báo cáo, chưa triển khai các sửa đổi.
