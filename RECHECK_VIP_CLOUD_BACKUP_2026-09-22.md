# Kiểm tra lại VIP và Google Drive sau sửa — 22/09/2026

## Kết luận

**Chưa hết lỗi; chưa nên nghiệm thu phần sao lưu/khôi phục.** Nhiều sửa đổi đã đúng hướng, nhưng còn lỗi trong retry snapshot, xử lý conflict, refresh cache, lưu trạng thái và quyền truy cập. Có cả lỗi cũ chưa khép kín lẫn lỗi phát sinh trong cách sửa.

Kiểm tra trên working tree hiện tại, không phải chỉ HEAD. Không sửa mã ứng dụng hay test sẵn có. Chỉ thêm báo cáo này và bộ probe tạm trong `build/vip-cloud-reaudit/`. Không gọi Drive thật, không thao tác dữ liệu người dùng, không commit/push.

## Kết quả kiểm tra thực tế

1. `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`: **BUILD SUCCESSFUL**, 54 tasks, 1 executed / 53 up-to-date.
2. Sau khi chạy probe, chạy lại **bộ test gốc**, không dùng init script: `:app:testDebugUnitTest --offline`: **BUILD SUCCESSFUL**, compile test và test thực sự executed. XML xác nhận **276 tests, 0 failures, 0 errors, 0 skipped**.
3. Lint hiện tại: **0 errors, 694 warnings**. Build/lint trên không phải kiểm thử release hay runtime Drive.
4. Bộ probe riêng gọi mã production: **13 tests, 11 failures, 2 pass đối chứng**. Fail là assertion về hành vi an toàn chưa đạt; không phải lỗi compile. Không được hiểu 11 failure là 11 nhóm lỗi độc lập, vì vài probe cùng kiểm tra một nhóm.
5. ADB không khởi động được daemon trong môi trường kiểm tra; chưa chạy điện thoại/emulator, WorkManager thực tế, process death hay hai thiết bị Drive thật.

Probe và bằng chứng:

- Source: `build/vip-cloud-reaudit/src/VipCloudReauditProbeTest.kt`.
- Kết quả được giữ riêng: `build/vip-cloud-reaudit/probe-results.xml`.
- Init script: `build/vip-cloud-reaudit/audit.init.gradle` chỉ bổ sung nguồn test tạm khi được chỉ định, không sửa app/build.gradle. Context/SharedPreferences fixture lấy từ test catalog sẵn có; logic cần kiểm tra gọi trực tiếp AppAuthManager/DocumentRepo/GoogleDriveService. HTTP dùng interceptor trả response giả, không truy cập mạng.
- Các artifact trong build có thể bị xóa khi clean; báo cáo này lưu lại kết luận và cách tái hiện.

Lệnh tái chạy riêng probe:

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipCloudReauditProbeTest --offline -I build/vip-cloud-reaudit/audit.init.gradle
```

Probe được viết theo hành vi cần đạt nên dự kiến đang fail trên snapshot này. Sau probe, chạy lại lệnh test bình thường để bỏ probe khỏi đầu ra biên dịch mặc định; bước đó đã được thực hiện trong lần kiểm tra này.

## Các lỗi còn lại cần sửa

Các đường dẫn ngắn dưới đây tính từ `app/src/main/java/com/tscanner/app/`. P1 cần ưu tiên trước khi tin cậy backup; P2 vẫn cần xử lý trước nghiệm thu đầy đủ. Bằng chứng chỉ được ghi là “đã tái hiện” khi có probe thực sự chạy; các tình huống UI/worker được ghi riêng là suy ra từ mã.

### R01 — P1: Retry tự xóa snapshot, enqueue sau có thể xóa snapshot của work trước

**Vị trí:** `utils/GoogleDriveBackupWorker.kt:98-110,160-163,188-197`; `utils/CloudBackupManager.kt:75-96`.

- `doWork()` trả `Result.retry()` nhưng `finally` vẫn xóa `KEY_SNAPSHOT_PATH`. WorkManager chạy lại với input cũ; dòng 78-82 thấy snapshot không còn rồi trả failure. Mạng chập chờn/token lỗi lần đầu đủ làm retry tự hỏng.
- Enqueue mới xóa tất cả snapshot cùng doc trước khi thay thế work; tên snapshot chỉ có docId/revision. Enqueue cùng revision có thể dùng lại đúng path mà worker cũ sẽ xóa trong finally. Enqueue revision mới cũng có thể xóa file worker cũ chưa mở để gửi.
- Copy snapshot lỗi thì fallback sang file PDF đang được sửa, phá invariant “upload đúng bytes của revision”. Copy đang diễn ra cũng chưa được phối hợp với writer PDF.

**Mức chứng minh:** đường chạy mã xác định; chưa chạy WorkManager trên Android.

**Sửa gói nhỏ:** R01a quản lý snapshot theo work ID, giữ qua retry, chỉ dọn đúng work terminal; R01b snapshot nhất quán với revision, không fallback sang file mutable và không copy PDF lớn trên UI thread.

**Nghiệm thu:** lần 1 HTTP 503/token tạm lỗi, lần 2 thành công vẫn có đúng snapshot; enqueue hai lần cùng revision không xóa file của nhau; R+1 không phá upload R; kill/retry và cache bị hệ điều hành dọn có đường recovery rõ.

### R02 — P1: Có cờ conflict nhưng vẫn tự upload ghi đè remote

**Vị trí:** `data/repository/DocumentRepo.kt:739-747,815-818`; `utils/CloudBackupManager.kt:40-113,120-129`; `utils/GoogleDriveBackupWorker.kt:58-118`; `utils/AppAuthManager.kt:175-183`.

`upsertFromDrive()` đặt `isConflict=true`, nhưng `getUnsyncedDocuments()`, enqueue và worker không kiểm tra cờ này. Toàn bộ mã UI cũng chưa sử dụng isConflict để thông báo hay chọn giải pháp. Post-login còn enqueue dirty docs trước khi reconcile remote. Do đó A/B cùng sửa có thể ghi đè bản trên Drive mà không cho người dùng xử lý conflict; CAS revision local không bảo vệ phiên bản remote.

**Đã tái hiện:** `conflictMustNotBeReturnedForAutomaticBackup` fail: doc đã conflict vẫn được trả trong danh sách tự backup.

**Sửa:** R02a chặn tại repository/enqueue/worker và kiểm tra remote baseline trước overwrite; R02b UI conflict có lựa chọn giữ bản local/remote hoặc tạo bản riêng. Không tự clear conflict chỉ vì upload thành công.

**Nghiệm thu:** conflict không gửi PATCH; local/remote đều được giữ; post-login/offline retry không vượt qua conflict; người dùng chọn giải pháp rồi mới resume.

### R03 — P1: Refresh catalog xóa PDF tốt trước khi có bản thay thế; rollback không phục hồi file

**Vị trí:** `data/repository/DocumentRepo.kt:748-779`; OCR dùng page cache tại `ui/home/HomeFragment.kt:462-465` và luồng tương tự Files.

Khi remote mới hơn và local clean, upsert xóa PDF/thumbnail ngay rồi mới save catalog. Nếu mất mạng khi tải lại, hết VIP hoặc saveData lỗi, người dùng mất bản PDF dùng offline. Nhánh rollback chỉ khôi phục DocumentItem, để pdfPath trỏ tới file đã bị xóa. Việc xóa cũng không kiểm tra file có được tài liệu khác tham chiếu như deleteDocument hay không.

Ngoài ra upsert giữ nguyên `pagePaths`/`pageCount` cũ. OCR ưu tiên pagePaths còn tồn tại, nên có thể OCR/xuất Word từ trang cũ trong khi PDF remote đã cập nhật.

**Đã tái hiện:** `remoteRefreshMustKeepOldFileUntilReplacementAvailable` và `failedCatalogRefreshMustNotDeleteOldFile` đều fail. Probe thứ hai inject lỗi ghi catalog rồi xác nhận rollback path nhưng PDF vật lý mất.

**Sửa:** đánh dấu remote pending/stale, giữ cache tốt cho tới khi tải và kiểm chứng được phiên bản mới; đổi file + metadata có recovery; cache trang/thumbnail/số trang gắn với revision và được cập nhật nhất quán.

**Nghiệm thu:** list thành công + download thất bại vẫn mở được bản cũ có nhãn stale; lỗi lưu catalog không xóa file; OCR/convert sau refresh dùng đúng trang mới.

### R04 — P1: API lưu trạng thái/ownership vẫn báo thành công khi ghi catalog thất bại

**Vị trí:** `data/repository/DocumentRepo.kt:502-539,599-637,641-663,667-694,990-1006`.

`updateSyncStatusCas()` kiểm tra owner/revision đúng, nhưng bỏ qua Boolean `saveData()` và trả true. `setDocumentOwner`, claim guest, migration owner, mark modified cũng thay memory rồi không xử lý persist failure. Với cache download, API có trả false nhưng vẫn publish pdfPath mới trong memory; lần mở kế tiếp thấy file tồn tại có thể coi là thành công mặc dù catalog trên đĩa chưa commit.

**Đã tái hiện:** `failedSyncCasMustNotReportSuccess` và `failedCacheCommitMustRollbackInMemoryPath` fail với dataFile đặt dưới một regular file để ép lỗi IO thật. Không mô phỏng thuật toán saveData trong test.

**Sửa:** R04a giao dịch CAS/status/owner trả kết quả persist thật và rollback memory; R04b download commit/publish và migration retry theo cùng nguyên tắc. Callsite không enqueue/hoàn tất dựa trên claim chưa lưu bền vững.

**Nghiệm thu:** fault injection đầy đĩa/không ghi được: không báo SYNCED/claim thành công; memory và reload thống nhất; dữ liệu cũ còn nguyên; không mất dirty revision sau restart.

### R05 — P1: Tìm file để chống trùng bị lỗi vẫn cho tạo file mới

**Vị trí:** `utils/GoogleDriveService.kt:223-250,270-277`; nhánh folder `:166-195`; worker `:121-124`.

`findFileByDocId()` trả null cho cả “không có file” lẫn HTTP 503/401/network/parse error. `uploadPdfFile()` coi null là không có và POST mới. Kịch bản response upload trước bị mất rồi retry lookup 503 vẫn tạo bản trùng. Folder lookup cũng tạo folder sau nhiều loại HTTP lỗi, đồng thời chưa single-flight theo account; có thể phân tán backup vào nhiều folder cùng tên.

**Đã tái hiện:** `lookupErrorMustNotBecomeCreateRequest` fail: GET 503 được tiếp nối bằng POST.

**Lỗi phân loại liên quan:** worker vẫn mặc định 404 nghĩa là đã bị xóa. Drive 404 cũng có thể nghĩa là không có quyền đọc, không được tự suy ra delete. [Tài liệu Google](https://developers.google.com/workspace/drive/api/guides/handle-errors).

**Sửa:** result typed Found/ConfirmedAbsent/Error; chỉ create sau lookup hoàn tất hợp lệ; recover create chưa rõ kết quả bằng identity ổn định, tránh check-then-create đồng thời; không recreate mù khi 404. Tách R05a file lookup/create, R05b folder resolution.

**Nghiệm thu:** lookup 401/403/429/5xx/network không POST; retry sau response loss không tạo bản thứ hai; hai work đầu tiên của một account dùng cùng folder.

### R06 — P1: PDF hỏng chỉ cần có `%PDF-` vẫn thay được file tốt và được cache

**Vị trí:** `utils/GoogleDriveService.kt:491-503,532-546`; `utils/CloudBackupManager.kt:399-418`; `data/repository/DocumentRepo.kt:681-694`.

Temp/atomic replacement đã có, nhưng validation chỉ đọc 5 byte đầu. Body `%PDF-1.7 truncated garbage` vẫn commit. Render thumbnail thất bại không ngăn commit catalog; đọc số trang lỗi lại fallback số trang cũ hoặc 1. Khi đó mở lại bỏ qua download vì file tồn tại.

**Đã tái hiện:** `malformedPdfMustNotReplacePreviousFile` fail; interceptor trả body không có cấu trúc PDF hợp lệ, API vẫn trả true và thay file đích.

**Sửa:** validate PDF có thể mở/đọc trang, kiểm tra size/checksum/version được server công bố, rồi mới thay file và commit catalog. Không coi việc nhìn thấy magic bytes là PDF đã khôi phục được.

**Nghiệm thu:** header đúng nhưng body cụt/sai cấu trúc, HTTP 200 lỗi, mismatch checksum/length đều không thay cache tốt hoặc publish success; PDF nhiều trang hợp lệ vẫn tải được. Test cũ `testDownloadPdfFile_validPdfCommitsAtomically` đang dùng chuỗi giả `%PDF-1.7 complete document bytes`, cần thay fixture PDF thật.

### R07 — P1: Cách ly tài khoản vẫn có đường hở ở Home sau logout và file xuất

**Vị trí:** `data/repository/DocumentRepo.kt:543-561,851-898`; `ui/home/HomeFragment.kt:295-303`; `ui/ocr/OcrResultActivity.kt:222-223,264-265`; `utils/FileUtils.kt:32-35`.

- Home truyền userId=null sau logout, nhưng getRecent/search khi null lại dùng toàn memoryDocs. Kết quả là tên/thumbnail/metadata tài liệu A vẫn hiện. Điểm mở PDF chính đã thêm getDocumentForUser nên không khẳng định mọi PDF đó đều mở được sau logout.
- Quản lý file quét toàn thư mục exports; bộ lọc “file của account khác” chỉ chứa pdfPath trong catalog. File OCR/Word/Excel xuất ra exports chưa có ownership nên B/khách vẫn có thể thấy, mở/share bản xuất của A qua quản lý file.
- Mutation API như delete/rename/markModified bỏ guard khi userId=null; không nên dùng null vừa là khách vừa là quyền bypass.

**Đã tái hiện:** `loggedOutRecentAndSearchMustNotRevealAccountDocuments` fail ngay tại recent list. Nhánh export được xác nhận bằng đường ghi exports → scan managed files; chưa thao tác UI Android.

**Sửa:** R07a thống nhất null=guest ở mọi query/mutation public; R07b ownership cho export/managed file và kiểm tra lại tại open/share/delete, không chỉ lọc danh sách. Không xóa dữ liệu account khác để giải quyết lọc.

**Nghiệm thu:** A export Word/Excel rồi logout/B login: không lộ vùng riêng trong Home/search/folder count/managed files; khách chỉ thấy guest; ownership tồn tại sau restart.

### R08 — P2: So remote modifiedTime với thời gian điện thoại làm bỏ lỡ bản mới

**Vị trí:** `utils/GoogleDriveBackupWorker.kt:147-154`; `data/repository/DocumentRepo.kt:627-633,734-735`.

Upload thành công lưu `System.currentTimeMillis()` vào lastSyncedAt; reconcile so với Drive modifiedTime. Máy nhanh vài giờ/ngày thì cập nhật remote mới hơn bản đã upload vẫn có timestamp nhỏ hơn lastSyncedAt và bị bỏ qua. Máy chậm có thể tự coi chính bản vừa upload là mới, kéo theo refresh/xóa cache.

**Đã tái hiện:** `clientClockMustNotHideNewerRemoteVersion` fail: lastSyncedAt local=10000 che mất remote version mới tại server=500. Đây là mô phỏng clock skew trên repository production, không giả lập Drive thật.

**Sửa:** tách thời điểm UI “backup xong” khỏi remote baseline/version; lấy modifiedTime/version/checksum từ server sau upload và lưu với revision tương ứng.

**Nghiệm thu:** clock ±24 giờ không ảnh hưởng phát hiện thay đổi; sync sau upload không tự invalidate bản vừa upload; local dirty không bị dùng sai baseline.

### R09 — P2: Nâng VIP từ Home/Viewer/CreatePDF/ID card không mở được consent khi thiếu callback

**Vị trí:** `ui/dialogs/VipUpgradeDialog.kt:51-54`; `ui/home/HomeFragment.kt:266-268`; caller trong Viewer/CreatePdfDialog/IdCardComposeActivity không truyền onRequestDrivePermission.

Fallback startActivity cũ đã được bỏ, nhưng chỉ thay bằng `onRequestDrivePermission?.invoke()`. Với caller không có callback, app toast yêu cầu quyền rồi đóng dialog, không mở màn hình xin quyền. More đã có launcher riêng và handler đúng account, nhưng các entry point khác chưa được nối vào coordinator chung.

**Mức chứng minh:** truy vết callsite; chưa UI test.

**Sửa:** coordinator authorization dùng được từ mọi host, callback/continuation không nullable ở luồng cần consent; lifecycle-safe qua rotation; không đổi account âm thầm.

**Nghiệm thu:** từng entry point, allow/deny/cancel/rotation; allow chạy tiếp batch/catalog, deny không báo backup active.

### R10 — P2: Đổi tên Drive thất bại vẫn báo toàn bộ đã đồng bộ; file cloud-only không được enqueue rename

**Vị trí:** `utils/GoogleDriveService.kt:408-411`; `data/repository/DocumentRepo.kt:386-408`.

Update media thành công rồi PATCH name lỗi 503 vẫn trả `mediaResult.Success`, worker đánh dấu SYNCED. Với tài liệu vừa restore chưa tải, rename đặt LOCAL_ONLY/isSynced=false nhưng chỉ enqueue nếu pdfPath!=null. Tải sau đó chỉ update path, không tự enqueue rename; tên remote vẫn cũ. Đây là hai đường thất bại khác nhau của cùng luồng metadata.

**Đã tái hiện:** `metadataFailureMustNotReportCompleteUploadSuccess` fail khi media=200, metadata=503. Nhánh cloud-only được xác nhận từ điều kiện enqueue và download completion.

**Sửa:** work metadata riêng hoặc kết quả hợp nhất đảm bảo cả phần đã yêu cầu được commit; retry metadata không cần upload lại toàn PDF; hỗ trợ rename cloud-only.

**Nghiệm thu:** rename lỗi không báo synced; retry chỉ sửa phần còn thiếu; đổi tên trước download vẫn hiện tên mới trên máy khác.

### R11 — P2: Xóa trong Quản lý tài liệu vẫn bị đồng bộ hồi sinh

**Vị trí:** `data/repository/DocumentRepo.kt:933-979`; `ui/docmanagement/DocumentManagementActivity.kt:305`.

Tombstone chỉ được thêm ở deleteDocument, còn màn Quản lý gọi deleteManagedFile, xóa PDF/catalog mà không thêm tombstone. Catalog Drive tiếp theo tạo lại doc với UUID mới. Local tombstone theo docId cũng chưa xử lý trường hợp upload đầu đang chạy chưa có driveFileId rồi bị xóa: import chỉ xét driveFileId.

**Đã tái hiện:** `managedFileDeletionMustPreventDriveResurrection` fail: deleteManagedFile → upsert cùng Drive ID trả isNew=true.

**Sửa:** dùng chung giao dịch xóa local/tombstone theo account + stable document/Drive identity; hủy/đối soát work liên quan; không tự xóa cloud.

**Nghiệm thu:** xóa từ Home/Files/Quản lý đều không hồi sinh sau restart/sync; xóa khi upload đầu đang chạy có hành vi nhất quán; lỗi persist không trả thành công giả.

### R12 — P2: Typed sync error chưa đến được UI; rate limit bị coi là lỗi vĩnh viễn

**Vị trí:** `utils/CloudBackupManager.kt:199-206,277-294`; `ui/adapter/DocumentAdapter.kt:78-89`; `utils/GoogleDriveService.kt:109-119`; worker `:174-185`.

Tất cả caller production vẫn gọi wrapper trả Int, wrapper nuốt AuthRequired/Failure thành 0. Upsert null vì lỗi IO cũng bị bỏ qua rồi query success vẫn được báo Success. Adapter vẫn dùng lastSyncError global; có error cũ thì chỉ toast, không chạy retry.

403 `userRateLimitExceeded`/`rateLimitExceeded` được gộp QuotaExceeded và worker kết thúc failure, dù đây là hạn mức tốc độ cần backoff theo [Google Drive error guidance](https://developers.google.com/workspace/drive/api/guides/handle-errors). Không nhầm với dung lượng đã đầy, vốn cần người dùng xử lý.

**Mức chứng minh:** đường gọi và phân loại trong code; các test transport hiện còn assert phân loại QuotaExceeded cho userRateLimitExceeded nên pass không chứng minh retry đúng.

**Sửa:** R12a typed result đến UI, per-operation error và persist failure vào Partial/Failure; R12b phân biệt rate-limit/storage quota/auth, backoff hữu hạn.

**Nghiệm thu:** lỗi trang 2/auth/disk được báo đúng; retry doc không bị lỗi global của doc khác chặn; rate-limit phục hồi sau backoff.

### R13 — P1: Download commit không kiểm tra remote revision; session guard chưa nguyên tử

**Vị trí:** `utils/CloudBackupManager.kt:390-416`; `data/repository/DocumentRepo.kt:667-694`; worker `:45-58,147-154`.

Download giữ docItem cũ, cuối cùng commit pdfPath chỉ theo docId. Nếu catalog sync nhận remote R+1 trong khi download R đang chạy, cuối download vẫn gắn bytes R vào doc đã ghi lastSyncedAt R+1. Lần sync sau không thấy remote mới hơn và lần mở trả cache R. owner/session được kiểm tra ngoài repository, không có expected remote version/revision trong commit.

Worker cũng chưa nhận session generation; CAS kiểm tra owner của tài liệu với currentUser đã chụp đầu work, không kiểm tra active session tại thời điểm commit. Cancel WorkManager và coroutine scope là cải thiện, nhưng chưa khép kín khoảng giữa check và commit. Không khẳng định mọi logout đều vượt guard; đây là các interleaving cần test barrier.

**Mức chứng minh:** phân tích concurrency từ API/callsite, chưa tái hiện barrier runtime trong lần này.

**Sửa:** download trả staged file + expected owner/session/local/remote version; repository commit nguyên tử chỉ khi tất cả còn khớp, nếu không bỏ staging/retry phiên bản mới. Namespace download/snapshot theo session/work; không để finally phiên cũ tháo callback/file của lần mới.

**Nghiệm thu:** giữ HTTP download R, upsert R+1, rồi trả HTTP R: không cache R dưới metadata R+1; logout/login nhanh trong download/upload không commit hoặc xóa dữ liệu work mới; deletion giữa download không phục hồi doc đã xóa.

## Đối chiếu từng mục kế hoạch cũ

| Mục | Đánh giá sau sửa | Phần đã thấy đúng / phần còn thiếu |
|---|---|---|
| V01 identity | Cải thiện rõ, chưa nghiệm thu migration IO | Classic/Credential cùng resolve sub; có migration. Persist owner vẫn R04; canonical VIP đã tồn tại thì migration không merge entitlement legacy còn hạn, cần test thêm trường hợp hai khóa cũ cùng tồn tại. |
| V02 ownership | Một phần | Có getDocumentForUser và guard open; còn null-user/exports/mutation R07 và claim chưa bền vững R04. |
| V03 revision/snapshot | Một phần, có regression | CAS từ chối revision cũ đã probe pass; snapshot retry/lifecycle R01, persist CAS R04. |
| V04 session | Một phần | Scope cancel/generation/tag đã thêm; commit/version/callback race còn R13. isSyncingCatalog global có thể khiến sync B bị bỏ qua khi A đang hủy; cần kiểm thử đổi account đang list. |
| V05 remote refresh/conflict | Chưa an toàn | Có reconcile và flag; R02/R03/R08/R13. |
| V06 idempotent catalog | Phần chính đã có | Upsert synchronized theo Drive ID giảm trùng, count chỉ tăng khi add thành công. Persist failure chưa truyền thành lỗi cuối R12. |
| V07 transport/idempotency | Một phần | PATCH transient không POST trực tiếp nữa; còn lookup null/error, 404, folder và rate-limit R05/R12. |
| V08 safe download | Một phần | Temp + atomic replace + coalescing đã thêm; validation/persist/version R06/R04/R13. |
| V09 authorization | Một phần | More tách consent/sign-in và kiểm tra email; các host khác R09. |
| V10 state/error | Một phần | isSynced không OR với cờ cũ nữa; clearDriveFileId có. Typed errors chưa đến UI, IO/rate-limit R04/R12. |
| V11 metadata/delete | Một phần | Page count unknown và resolve sau tải có; rename/tombstone chưa đủ R10/R11. Manifest/folder restore chưa triển khai. |
| V12 watermark | Đã sửa các điểm chính trong mã | Shared policy tại export và More/Account dùng isVipActive; onResume không tự tắt lựa chọn watermark. Có unit test policy. Chưa chạy UI/export Android qua mốc expiry nên không coi là nghiệm thu runtime toàn phần. |
| V13 trial/paid | Chưa thay đổi đáng kể | Dialog vẫn cấp thêm 365 ngày bằng local preferences, build không có cổng trial/demo, chưa có restore quyền mua đa thiết bị. Giới hạn bản thử nghiệm, không suy luận đã thu tiền người dùng. |

## Vì sao 276 test pass vẫn còn lỗi

- Test CAS hiện kiểm tra revision/owner nhưng chưa inject saveData failure; probe mới bổ sung được khoảng này.
- `CloudSessionGenerationGuardTest.testCancelActiveCloudTasks_invalidatesInFlightCloudScope` không tạo cloud task đang chạy và kết thúc bằng assertTrue(true). Test “mid-flight” khác đưa expectedOwner=B vào doc A; worker thực lại giữ expectedOwner=A từ đầu. Vì vậy chưa kiểm thử đúng logout race.
- Test PDF “valid” dùng chuỗi chỉ có header thay vì PDF thật, vô tình chấp nhận validator quá yếu.
- Test Drive phân loại status chưa chạy vòng đời retry → snapshot cleanup của worker.
- Test conflict chỉ xác nhận isConflict=true và local tồn tại; chưa thử enqueue/worker ngay sau đó.
- Test permission chủ yếu kiểm tra model/helper/repository; chưa kiểm thử đủ các host mở dialog.

## Thứ tự sửa tiếp cho mô hình nhỏ

1. **R01a** snapshot retry/lifetime — phạm vi nhỏ, lỗi rõ, ưu tiên đầu.
2. **R04a/b** giao dịch persist và **R07a/b** account isolation, làm từng gói riêng để tránh sửa chồng DocumentRepo.
3. **R03** giữ cache tốt → **R06** PDF validation → **R13** version/session-aware commit.
4. **R08** remote baseline → **R02a/b** conflict end-to-end.
5. **R05a/b** create/reconcile/folder, **R10** metadata retry, **R11** deletion thống nhất.
6. **R09**, **R12**, rồi nghiệm thu Android/Drive thật theo ma trận kế hoạch cũ. V13 chỉ triển khai thanh toán sau khi chốt yêu cầu sản phẩm riêng.

Prompt tiếp nối sau khi được duyệt sửa:

> Đọc RECHECK_VIP_CLOUD_BACKUP_2026-09-22.md và chỉ sửa Rxx đã được duyệt. Kiểm tra lại symbol trên mã hiện tại, giữ mọi thay đổi có sẵn. Chuyển probe tương ứng thành regression test trong suite chính nếu phù hợp; bổ sung integration/barrier test cho worker và phiên tài khoản khi task yêu cầu. Không dùng test mô phỏng lại thuật toán thay cho production. Nêu file sẽ sửa, invariant, điều kiện nghiệm thu; chạy test tập trung rồi unit/lint/assemble. Không tự sửa các R khác, không xóa hoặc upload Drive thật, không thay đổi trial thành billing. Báo rõ phần đã test JVM và phần còn chờ Android/Drive thật.

**Kết luận nghiệm thu:** các cải thiện hiện tại chưa đủ bảo đảm backup đúng phiên bản, retry bền vững và cách ly tài khoản. Cần xử lý các P1 ở trên trước khi công bố hoàn tất kế hoạch.
