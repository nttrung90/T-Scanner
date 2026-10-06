# Kiểm tra VIP và sao lưu Google Drive — 22/09/2026

## 1. Kết luận và phạm vi

**Có lỗi cần sửa trước khi coi luồng VIP/Drive là đáng tin cậy.** Rà soát trên working tree hiện tại, có nhiều thay đổi chưa commit. Chỉ thêm báo cáo/kế hoạch này; không sửa production code. Các kịch bản bên dưới được suy ra từ đường chạy mã, chưa tái hiện trên thiết bị/Drive thật.

Phạm vi: AppAuthManager, UserProfile/VipTier, các dialog VIP/tài khoản, More/Home/Files, watermark PDF/ID card, CloudBackupManager, GoogleDriveBackupWorker, GoogleDriveService, DocumentRepo và khởi động Application. Không kiểm tra được Google Cloud Console, cấu hình OAuth phát hành, thanh toán bên ngoài repository hay tài khoản Drive thực tế.

Ưu tiên P1: sai tài khoản/quyền, sai bản sao lưu, mất khả năng khôi phục hoặc báo an toàn sai. P2: lỗi điều phối, dữ liệu hiển thị/metadata, UX và độ bền. Vấn đề thương mại VIP được tách riêng vì giao diện hiện có ghi rõ Trial/Testing; không coi việc dùng thử miễn phí tự thân là lỗi thanh toán.

## 2. Kiểm chứng đã thực hiện

- Đọc mã hiện tại và truy vết các điểm gọi; không tái sử dụng kết luận cũ như bằng chứng hiện hành.
- Lệnh: `$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`.
- Kết quả: **BUILD SUCCESSFUL**, 54 actionable tasks: 1 executed, 53 up-to-date. Test/build/lint phần lớn là kết quả hợp lệ được Gradle tái sử dụng, không phải chạy mới toàn bộ test.
- XML unit test hiện có: **227 tests, 0 failures, 0 errors, 0 skipped**. `VipManagerTest` chỉ kiểm tra hàm tính hạn do test tự định nghĩa, không gọi AppAuthManager; chưa chứng minh luồng đăng nhập/gia hạn/khôi phục VIP. Chưa thấy test worker/HTTP/catalog Drive chuyên biệt.
- XML lint hiện có: **0 errors, 689 warnings**; không khẳng định toàn bộ warning thuộc phạm vi VIP/Drive.
- Hai lần đầu Gradle bị lỗi cache/lock ở `C:\.gradle`, rồi Access denied tại cache người dùng; chạy với quyền cache phù hợp thành công. Đây không phải lỗi biên dịch ứng dụng.
- ADB không khởi động được daemon trong lần kiểm tra. Không kết luận rằng không có thiết bị; chưa chạy instrumentation, process death, mạng gián đoạn, hết dung lượng, hai tài khoản/hai thiết bị hoặc Drive thật.
- Không thực hiện upload/download/xóa tài liệu trên tài khoản thật.

## 3. Các phát hiện và gói sửa nhỏ

Đường dẫn ngắn dưới đây tương đối với `app/src/main/java/com/tscanner/app/`. Số dòng là snapshot ngày kiểm tra; AI thực hiện phải tìm lại symbol trước khi sửa. Mỗi task chỉ làm sau khi được duyệt triển khai. Test phải gọi logic production hoặc integration seam; không sao chép thuật toán vào test rồi coi là hồi quy.

### V01 — P1: Cùng tài khoản Google nhưng hai khóa định danh khác nhau

**Bằng chứng:** `utils/AppAuthManager.kt:137-149` dùng `account.id`; `:210-225` dùng `googleIdTokenCredential.id` cho cả id/email; VIP được lưu theo `profile.id` ở `:346-377`. `MoreFragment` ưu tiên classic sign-in, Credential Manager là đường fallback khi không khởi chạy intent, nên lỗi không xảy ra trong mọi lần đăng nhập.

Google xác nhận `GoogleIdTokenCredential.getId()` là email, còn `GoogleSignInAccount.getId()` là ID tài khoản:
- https://developers.google.com/identity/android-credential-manager/android/reference/com/google/android/libraries/identity/googleid/GoogleIdTokenCredential
- https://developers.google.com/identity/sign-in/android/people

**Tái hiện dự kiến:** đăng nhập bằng Credential Manager, kích hoạt VIP và tạo tài liệu; sau đó cấp Drive qua classic result hoặc đăng nhập lại bằng classic cùng Google account. Khóa VIP khác, entitlement về FREE; owner cũ cũng không khớp worker.

**Phạm vi sửa:** AppAuthManager và migration khóa VIP/owner trong DocumentRepo; không sửa REST Drive. Chọn canonical Google identity nhất quán; không lấy email làm bằng chứng xác thực backend. Migration phải nhận diện cùng tài khoản đã xác thực, không tự gán mọi owner cũ sang người đang đăng nhập.

**Test/nghiệm thu:** hai provider cho cùng account ra một canonical ID; VIP/owner giữ nguyên sau đổi provider, restart và migration chạy lại; account khác không được kế thừa. Nếu cần chia nhỏ: V01a canonical identity; V01b migration preferences/catalog sau V01a.

### V02 — P1: Chưa cách ly tài liệu theo tài khoản; tài liệu khách chưa được nhận quyền sở hữu bền vững

**Bằng chứng:** DocumentRepo dùng một catalog `:23-30`, query `:370-388`, getUnsynced `:451-452` không lọc owner. Home `:288-291`, Files `:202-205` hiển thị danh sách chung. `CloudBackupManager:45` chỉ gán owner vào input WorkManager; không ghi owner cho doc có owner null. `signOut:268-288` không thay phạm vi catalog. `downloadDocument:166-169` trả file local ngay, không kiểm tra owner.

**Tái hiện:** A có PDF tải local, đăng xuất rồi B đăng nhập: B vẫn thấy/mở PDF A. Tạo PDF khi chưa đăng nhập, backup dưới A, sửa khi đang ở B: owner null cho phép enqueue theo B; worker có thể lấy Drive ID A, PATCH thất bại rồi POST sang B (V07).

**Phạm vi:** DocumentRepo, Home/Files và các điểm đọc/mở/share/managed-file có liên quan, enqueue/download. Tách tài liệu khách và tài liệu có owner; thao tác nhận tài liệu khách phải có quy tắc rõ, lưu owner trước enqueue. Không xóa dữ liệu A khi đổi tài khoản. Nếu sản phẩm chủ ý có thư viện local dùng chung thì phải tách rõ dữ liệu chung khỏi vùng cloud/account, vẫn cấm tự upload sang B.

**Test/nghiệm thu:** A→logout→B không đọc/chia sẻ/backup vùng riêng A qua UI hay API repository; guest được claim một lần, restart không mất owner. Chia **V02a** repository/claim; **V02b** mọi UI và điểm truy cập theo account sau V02a. Không chỉ lọc danh sách mà bỏ qua mở trực tiếp bằng ID/path.

### V03 — P1: Worker cũ đánh dấu revision mới đã sao lưu

**Bằng chứng:** DocumentRepo `:414-430` tăng `contentRevision`; input worker chỉ có ID/path/title/owner (`CloudBackupManager:51-58`); worker `:98` cập nhật SYNCED chỉ theo docId; DocumentRepo `:392-410` không CAS revision. WorkPolicy.REPLACE không thay thế được kiểm tra revision và snapshot bytes.

**Tái hiện:** giữ upload revision R ở điểm chờ, sửa PDF thành R+1, cho kết quả upload R về. Revision mới có thể được gắn SYNCED dù cloud chỉ có R; đọc cùng path trong lúc file bị sửa cũng không đảm bảo upload một snapshot nhất quán.

**Phạm vi:** chia **V03a** API commit sync có expectedOwner/revision, cập nhật nguyên tử và test repository; **V03b** input/snapshot upload/worker gọi API đó. Có snapshot bất biến hoặc cơ chế khóa đọc tương đương; dọn snapshot theo vòng đời work. Khi doc bị xóa, không tiếp tục upload chỉ vì path còn tồn tại.

**Test/nghiệm thu:** điều khiển upload bằng barrier để R hoàn tất sau R+1; R không đánh dấu R+1 SYNCED/FAILED, không làm mất work mới. Xóa doc/chuyển owner giữa chừng phải vô hiệu kết quả cũ. Kill/retry không dùng snapshot đã xóa. Ưu tiên này vẫn còn trên mã hiện tại dù contentRevision đã được thêm.

### V04 — P1: Tác vụ cloud sống qua thay đổi phiên tài khoản

**Bằng chứng:** catalog/download tạo CoroutineScope IO riêng (`CloudBackupManager:104,178`); giữ user/token rồi commit sau mạng, không kiểm tra session còn hiệu lực. signOut chỉ cancel WorkManager (`AppAuthManager:269`), không cancel hai scope này; worker chỉ kiểm tra owner trước HTTP. Cấp Drive qua More dùng chung handler sign-in có thể thay `_currentUser` mà không đi qua signOut.

**Tái hiện:** A bắt đầu list/download, giữ HTTP, chuyển B, trả HTTP A. Catalog/cache/callback của A vẫn được cập nhật và hiện ở phiên B; kiểm tra owner ở đầu hàm không đủ.

**Phạm vi:** session generation/scope theo account; invalidate ngay khi logout/đổi account, hủy đúng work cloud theo tag và chặn commit/callback muộn. Coroutine cancellation phải truyền tới HTTP hoặc tối thiểu kết quả không được commit. Không tuyên bố hủy có thể thu hồi request đã đến server.

**Test/nghiệm thu:** barrier ở token/list/download/upload; chuyển A→B ở từng điểm, kết quả A không chạm trạng thái B. Test callback UI sau destroy/rotation. Phụ thuộc V01 và API owner/commit của V02a/V03a.

### V05 — P1: Thiết bị khác không nhận cập nhật file đã biết

**Bằng chứng:** `CloudBackupManager:125-148` chỉ thêm khi Drive ID chưa tồn tại; không dùng modifiedTime để cập nhật tài liệu hiện có. `downloadDocument:166-169` luôn trả cache nếu file tồn tại.

**Tái hiện:** A và B cùng có PDF X; A sửa và upload cùng Drive ID; B sync/reopen vẫn dùng PDF cũ vô thời hạn.

**Phạm vi:** remote version/modifiedTime/checksum trong model và catalog, so sánh phiên bản, cache invalidation/refresh. Không ghi đè local đang dirty; cần trạng thái conflict rõ. Không dùng đồng hồ local làm nguồn duy nhất cho thứ tự remote.

**Test/nghiệm thu:** remote mới + local clean → tải bản mới; local dirty + remote mới → giữ cả dữ liệu và báo conflict; remote không đổi → không tải lại; remote lỗi → không xóa bản tốt. Chia V05a metadata/reconcile; V05b cache refresh/conflict UI. Phụ thuộc V03, V08.

### V06 — P2: Import catalog có thể tạo trùng và báo thành công dù lưu thất bại

**Bằng chứng:** `CloudBackupManager:125-147` chụp `repo.documents.value`, tạo UUID mới cho mỗi import; `existingDriveIds` không cập nhật/khóa cùng add. `repo.addDocument(newDoc)` trả Boolean nhưng bị bỏ qua, addedCount vẫn tăng. Application startup, đăng nhập và VIP dialog đều gọi sync, có thể overlap.

**Tái hiện:** hai sync cùng nhận file X khi local chưa có X → hai UUID cùng driveFileId; inject lỗi saveData → toast đếm thêm file mặc dù catalog rollback.

**Phạm vi:** repository atomic upsert khóa `(ownerId, driveFileId)` từ snapshot nội bộ, không dùng LiveData làm nguồn giao dịch; single-flight sync theo account; đếm chỉ kết quả persist thành công.

**Test/nghiệm thu:** hai list hoàn tất đồng thời chỉ có một bản; lỗi đĩa không báo thêm thành công; import lại idempotent. Phụ thuộc V01/V02a; làm trước V05 để có nền reconcile.

### V07 — P1: Bất kỳ lỗi PATCH nào cũng biến thành upload mới

**Bằng chứng:** `GoogleDriveBackupWorker:90-93` fallback POST khi update trả null; `GoogleDriveService:217-231` gộp HTTP lỗi, timeout, parse failure thành null. POST chưa có khóa idempotency/reconciliation.

**Tái hiện:** PATCH 503/timeout nhưng file vẫn còn → POST thành file trùng. POST lần đầu đã được server nhận nhưng response mất → retry POST lại có thể sinh bản thứ hai. Trường hợp sai owner V02 còn có thể copy dữ liệu sang Drive khác.

**Phạm vi:** kết quả REST có loại lỗi/status; auth/quota/transient không tạo file mới; xác minh mất file/quyền trước khi chọn tái tạo, không mặc định coi mọi 404 là tài liệu đã bị xóa. Identity tài liệu ổn định (ví dụ appProperties) để reconcile create chưa rõ kết quả.

**Test/nghiệm thu:** PATCH 401/403/429/5xx/network không gọi POST; timeout sau server commit không sinh bản mới khi retry; lỗi quyền không chuyển account. Chia V07a error type + retry/update; V07b idempotent create/reconcile. Không viết retry vô hạn.

### V08 — P1: Tải PDF trực tiếp vào đích, thiếu validation và giao dịch catalog

**Bằng chứng:** `GoogleDriveService:298-306` mở FileOutputStream đích và trả true sau copy; không temp/atomic replace, checksum/length/PDF validation. `CloudBackupManager:191-204` vẫn lưu pdfPath nếu thumbnail render thất bại; `DocumentRepo:436-446` không rollback/trả lỗi saveData. Hai lần mở cùng doc có thể tải cùng dest.

**Tái hiện:** stream lỗi để lại file dở tại đích; HTTP 200 với body rỗng/sai PDF được nhận như thành công; hai download đồng thời ghi chồng. Với dữ liệu sai được commit, lần mở sau thấy exists nên không tải lại. Lưu ý: stream throw thông thường trả false và KHÔNG cập nhật pdfPath mới, không khẳng định mọi download lỗi đều được đánh dấu cached.

**Phạm vi:** temp riêng, validate PDF/số trang/kích thước và checksum nếu có; commit atomic bằng primitive đã kiểm chứng; single-flight theo owner/doc; chỉ công bố khi catalog lưu thành công. Lỗi giữ bản cache tốt và trạng thái có thể retry. Không dùng thumbnail thành công làm tiêu chí duy nhất xác nhận toàn PDF.

**Test/nghiệm thu:** mất mạng giữa stream, hết đĩa, 0 byte, sai PDF, hai download đồng thời, lỗi persist và process kill ở ranh giới commit. Không trả File thành công nếu chưa commit bền vững. Chia V08a file download an toàn; V08b catalog/single-flight. Phụ thuộc V04 cho session guard.

### V09 — P2: Luồng xin quyền Drive không nhất quán và có thể đổi tài khoản ứng dụng

**Bằng chứng:** `AppAuthManager:77-79` kiểm tra lastSignedInAccount không đối chiếu currentUser; intent `:82-88` không ràng buộc account. `MoreFragment:44-58` dùng kết quả xin scope như đăng nhập mới. Dialog VIP `:54-61` khi thiếu callback gọi startActivity thường, không nhận result để khởi chạy batch/sync; các caller Home/Viewer/CreatePdf/ID card có đường không truyền callback. Credential Manager success ở More chỉ toast, khác classic success có batch/sync.

**Tái hiện:** nâng VIP từ nút watermark khi chưa có scope → cấp quyền xong không có bước batch backup tài liệu cũ trong callback. Hoặc app đang là A, lastSignedInAccount là B → UI báo đã cấp Drive cho A theo quyền B; chọn B trong consent thay phiên đăng nhập ngoài ý muốn.

**Phạm vi:** một coordinator ActivityResult cho authorization, so sánh đúng account; tách kết quả consent khỏi sign-in; các entry point cùng gọi flow và continuation thành công. Hủy/từ chối không báo backup active, không đổi tài khoản; đang chạy lại không enqueue trùng. Credential success dùng chung post-login orchestration.

**Test/nghiệm thu:** từ More/Home/Viewer/CreatePdf/ID card: allow/deny/cancel/rotation; đúng account và backup tiếp tục đúng một lần; account khác không thay phiên âm thầm. Phụ thuộc V01/V04.

### V10 — P2: Sai trạng thái sync và lỗi bị báo như danh mục rỗng

**Bằng chứng:** DocumentRepo `:404-405` dùng `driveFileId ?: old`, `isSynced || old`; cleanMock `CloudBackupManager:95` truyền null nên không xóa mock ID và có thể giữ isSynced=true. Batch lọc `!isSynced`. Service query `:274-283` trả danh sách rỗng/partial khi lỗi, caller `CloudBackupManager:111-120` gọi onComplete(0) khi auth/folder lỗi. lastSyncError là biến global và không được reset/ghi đầy đủ ở từng request.

**Tái hiện:** mock doc isSynced=true được reset LOCAL_ONLY nhưng vẫn giữ ID/isSynced, nên batch bỏ qua; pagination trang 2 lỗi nhưng sync trông như hoàn tất; lỗi cũ được adapter dùng lại cho tài liệu khác.

**Phạm vi:** chia **V10a** state machine/tri-state update cho Drive ID + persist có kết quả; **V10b** result sync typed Success/Partial/AuthRequired/Failure, lỗi theo operation/document, retry rõ. Định nghĩa isSynced theo revision đã xác nhận, không theo việc từng upload trong quá khứ.

**Test/nghiệm thu:** reset mock xóa ID thật sự và được batch chọn; FAILED/LOCAL_ONLY không giữ boolean mâu thuẫn; lỗi trang 2 không báo full success; không xóa catalog khi list partial; lỗi request A không hiển thị nhầm ở B. Phụ thuộc V03a/V07a.

### V11 — P2: Sao lưu chưa khôi phục đầy đủ metadata; thao tác xóa local bị “hồi sinh”

**Bằng chứng:** upload metadata chỉ name/parents (`GoogleDriveService:153-155`); update chỉ media (`:209-214`). Rename/move/delete local (`DocumentRepo:282-340`) không cập nhật cloud/tombstone. Import đặt pageCount=1, pagePaths rỗng, createdAt=modifiedTime, không folder (`CloudBackupManager:131-144`); tải xong không cập nhật pageCount.

**Tái hiện:** đổi tên/chuyển folder trên A, restore máy B vẫn tên cũ và mất tổ chức folder; PDF 10 trang hiển thị 1 trang. Xóa doc đã backup ở local rồi cold-start: list Drive thêm trở lại với UUID mới.

**Phạm vi và quyết định sản phẩm:** phân biệt “xóa bản trên máy” với “xóa cả backup”; không tự xóa Drive. Nếu là backup PDF đơn thuần, UI phải nói đúng và có local tombstone/hide cho thao tác xóa theo semantics đã chọn. Nếu cam kết khôi phục thư viện, thêm versioned manifest metadata/folder. Không tự mở rộng thành backup draft/OCR/source ảnh mà chưa xác định phạm vi.

**Gói nhỏ:** V11a pageCount đọc từ PDF thật khi download và hiển thị unknown trước đó; V11b rename metadata; V11c local deletion/tombstone UI; V11d thiết kế manifest khôi phục folder/ngày tạo (chỉ thiết kế trước khi chốt phạm vi).

**Test/nghiệm thu:** PDF 1/10/100 trang; rename rồi restore giữ tên; local delete không bất ngờ hồi sinh; xóa local không xóa Drive; manifest nếu có phải round-trip thư mục/tên/ngày tạo và tương thích bản cũ. Phụ thuộc V05/V06/V08/V10.

### V12 — P2: Quyền watermark và UI VIP có thể giữ trạng thái sau hết hạn

**Bằng chứng:** Viewer `:232-237` chỉ bật isWatermarkRemoved khi VIP, không thu hồi khi FREE; export `:425,481,579` dùng biến đó mà không kiểm tra VIP hiện tại. ID card `:448-450` cũng chỉ chuyển một chiều. More `:172,202`, AccountDetail `:55,110` dùng isVip thay vì isVipActive; expiration được enforce ở init/More.onResume, không có đồng hồ cập nhật UI toàn app. Viewer.onResume còn tự bỏ lựa chọn bật watermark của người VIP.

**Tái hiện:** mở Viewer khi VIP, để hết hạn, tạo PDF mới: cờ bỏ watermark vẫn true. VIP chủ động bật watermark, đi qua activity khác rồi quay lại: onResume tự tắt nó.

**Phạm vi:** shared policy quyền tại lúc bắt đầu export; tách lựa chọn người dùng khỏi entitlement; refresh account UI khi resume/thay đổi quyền. Không đóng dấu ngược vào PDF cũ chỉ vì hết VIP.

**Test/nghiệm thu:** fake clock qua mốc expiry khi dialog/viewer đang mở; export mới tuân quyền hiện tại, UI đồng nhất; VIP chọn watermark on vẫn giữ sau resume. Chỉ sửa policy/export callsite liên quan, không đổi engine PDF. Phụ thuộc V01; có thể làm độc lập cloud.

## 4. VIP hiện là thử nghiệm, chưa phải quyền mua được khôi phục đa thiết bị

`VipUpgradeDialog:40-44` kích hoạt 365 ngày trực tiếp, `AppAuthManager:298-314,346-377` ghi SharedPreferences. Nút gia hạn AccountDetail mở lại dialog này nên có thể cộng thêm 365 ngày nhiều lần. Không có billing dependency trong app/build.gradle và không có purchase verification/restore entitlement trong luồng đã đọc. `loadVipForUser` chỉ đọc preferences trên thiết bị; đăng nhập cùng account trên máy mới không tự phục hồi VIP. Sao lưu catalog lại bị chặn bởi VIP (`CloudBackupManager:106`), nên máy mới không tự restore trong trạng thái FREE. Có thể kích hoạt trial lại thủ công, nhưng đó không phải restore quyền đã mua.

Đây là **giới hạn đã xác nhận của bản thử nghiệm**; trở thành P1 chặn phát hành nếu quảng bá/bán VIP thật. Không tự suy luận rằng người dùng đã bị thu tiền, cũng không tự thêm thanh toán trong đợt sửa cloud.

**V13a — task nhỏ được đề xuất:** cô lập trial/demo rõ ở build configuration và wording; test release không vô tình cấp quyền trial qua nút mua, debug vẫn dùng fixture. Chốt trước bản release có cho trial công khai hay không; nếu có, chính sách số lần/thời hạn phải được định nghĩa rõ, không giả định trial = giao dịch trả phí.

**V13b — task thiết kế trước triển khai:** xác định nguồn entitlement có thẩm quyền, định danh canonical, restore trên máy mới, revoke/refund/expiry/offline grace, idempotency giao dịch. Sau duyệt thiết kế mới tách purchase client, verification service và restore thành các task riêng. Không giao “làm toàn bộ Billing” cho một mô hình nhỏ. Test acceptance gồm restore cùng account trên máy sạch, đổi account, replay transaction, pending/cancel/refund; không dựa riêng vào đồng hồ và SharedPreferences của máy.

## 5. Thứ tự giao việc và ranh giới

1. **V01a → V01b**: thống nhất identity và migration; đây là nền cho ownership.
2. **V02a → V02b**, **V03a → V03b**, rồi **V04**: bảo vệ tài khoản, revision và phiên đang chạy. Mỗi lượt chỉ một task, không sửa cùng file đồng thời.
3. **V07a → V07b**, **V08a → V08b**, **V10a → V10b**: transport, file commit và trạng thái đúng.
4. **V06 → V05a → V05b → V09**: catalog idempotent, phiên bản remote, continuation quyền Drive.
5. **V11a/b/c** từng lượt; **V12** một lượt riêng. V11d và V13b là thiết kế cần chốt sản phẩm, không chặn việc sửa lỗi độc lập phía trên.
6. Chạy nghiệm thu hai account/hai thiết bị, offline/retry, process death trước khi phát hành. Nếu phát hành trả phí, V13 là điều kiện riêng bắt buộc.

Không sửa phần OCR/camera/sharpening, không reset working tree, không sửa tài liệu/local artifact có sẵn ngoài scope. Không tự xóa file Drive để “dọn bản trùng”. Migration phải giữ dữ liệu và có đường retry; ghi rõ dữ liệu nào chưa thể nhận diện owner an toàn.

## 6. Prompt dùng cho mô hình nhỏ

Chỉ gửi sau khi chủ dự án duyệt thực hiện; thay `TASK_ID` bằng một task hoặc nửa task, ví dụ V03a.

> Đọc PLAN_VIP_CLOUD_BACKUP_2026-09-22.md và chỉ thực hiện TASK_ID đã được duyệt. Kiểm tra lại bằng chứng trên working tree hiện tại; giữ nguyên thay đổi có sẵn. Đọc các task tiền đề và xác nhận API cần thiết đã có; nếu thiếu, báo chính xác phần thiếu, không tự làm tất cả task phụ thuộc. Trước khi sửa, nêu file/symbol sẽ chạm và invariant cần giữ. Thêm test hồi quy gọi production code hoặc test seam thật, chứng minh lỗi cũ; không sao chép thuật toán vào test. Sửa trong phạm vi gói, chạy test tập trung rồi :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline với GRADLE_USER_HOME phù hợp. Báo kết quả thực tế, test UP-TO-DATE nếu có, các tình huống chưa kiểm tra trên Android/Drive thật. Không upload/xóa dữ liệu thật, không commit/push hay triển khai dịch vụ ngoài scope. Kết thúc bằng file đã đổi, test, tiêu chí nghiệm thu đạt/chưa đạt và prompt cho đúng task kế tiếp.

## 7. Ma trận nghiệm thu tích hợp cuối

| Nhóm | Kịch bản bắt buộc | Kết quả mong đợi |
|---|---|---|
| Identity | Classic ↔ Credential, cùng account, account khác, migration lặp | Một ID chuẩn; không mất VIP/owner, không cấp quyền chéo |
| Ownership | Guest → A → logout → B; mở/search/share trực tiếp | Không lộ vùng riêng hoặc tự upload sang B |
| Session | Đổi account lúc token/list/download/upload đang chờ | Kết quả phiên cũ không commit/callback vào phiên mới |
| Revision | Upload R chậm; R+1 được tạo; worker cũ hoàn tất | Chỉ revision đã thực sự upload được xác nhận |
| Retry | 401/403/404/429/5xx, timeout trước/sau server commit | Phân loại đúng, không tạo bản trùng, không retry mù |
| Download | Mất mạng, hết đĩa, sai PDF, concurrent, kill | Không commit file dở; giữ bản tốt; retry được |
| Restore | Hai máy, remote edit, dirty local, folder/name/pageCount | Refresh đúng; conflict không mất dữ liệu; giới hạn backup minh bạch |
| Delete | Xóa local, restart/sync, tombstone, bản remote còn | Semantics rõ, không hồi sinh bất ngờ, không tự xóa cloud |
| Permission | Mọi entry point, allow/deny/cancel, account khác | Quyền đúng account, continuation nhất quán, không báo active sai |
| VIP | Expiry khi màn hình mở, giữ lựa chọn watermark, thiết bị mới | UI/export thống nhất; trial và paid restore được phân biệt |

Build/JVM/lint pass chỉ là điều kiện nền, không thay thế các kịch bản runtime ở bảng này.
