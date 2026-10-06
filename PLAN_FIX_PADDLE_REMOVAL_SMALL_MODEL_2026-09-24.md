# Kế hoạch sửa sau re-audit gỡ Paddle/ONNX — cho mô hình nhỏ

Ngày: 24/09/2026. Báo cáo gốc: `RECHECK_PADDLE_REMOVAL_2026-09-24.md`.

## 1. Phạm vi và trạng thái

Đây là kế hoạch triển khai tiếp sau `PLAN_REMOVE_PADDLE_ONNX_SMALL_MODEL_2026-09-23.md`, không làm lại việc gỡ Paddle. Phiên hiện tại chỉ viết tài liệu, không sửa production, không chạy lại test/build vì chưa có thay đổi code.

Bằng chứng từ lượt re-audit trước: bộ chuẩn 460 tests đạt; debug/release build đạt; lint 0 errors/718 warnings; APK 28.691.181 byte, AAB 17.988.354 byte, không còn entry Paddle/ONNX; 2/3 probe độc lập fail do cleanup nuốt cancellation. Các số này là baseline lịch sử, phải đo lại sau sửa. Không có thiết bị tại lượt re-audit; không coi kiểm tra JVM là nghiệm thu Android.

Ba nhóm công việc:
- R01/P2: adapter ML Kit không đóng recognizer sau thành công/lỗi. Lỗi đường code có sẵn, chưa đo mức tăng RAM hay crash thực tế.
- R02/P3: cleanup nuốt CancellationException ở hai catch bên trong, tái hiện bằng fault injection. Deleter mặc định là File.delete đồng bộ; không tuyên bố đang gây mất dữ liệu.
- R03: thiếu kiểm chứng repository/export legacy và nâng cấp Android; tài liệu nghiệm thu có khẳng định vượt bằng chứng, hướng dẫn dùng APK unsigned cần sửa.

## 2. Quy tắc giao việc

Thực hiện tuần tự **F00 → F01a → F01b → F02 → F03 → F04 → F05 → F06**. Mỗi lượt chỉ giao một gói. Không giao các agent sửa cùng file đồng thời. Một gói quá lớn thì báo điểm dừng, không tự mở rộng scope.

- Giữ toàn bộ thay đổi có sẵn; ghi git status/diff trước gói. Không reset --hard, clean, checkout đè file hoặc commit tất cả working tree.
- Không thêm lại Paddle/ONNX, không đổi engine/ngôn ngữ AUTO, không nâng dependency, không đổi ABI, applicationId hoặc chữ ký phát hành.
- Không sửa các lỗi OCR editor/Drive/camera ngoài phạm vi. Nếu test chạm lỗi có sẵn: ghi bằng chứng, tách blocker và chuyển cho người điều phối; không xóa assertion để pass.
- Test phải gọi logic production. Không thay adapter cần kiểm tra bằng `mlKitChineseRunner` rồi kết luận lifecycle SDK đúng.
- Không dùng delay/sleep để cố làm race test pass. Dùng barrier/latch/task controllable, reset test hooks trong finally/@After.
- Mọi báo cáo phân biệt: tái hiện bằng test; suy ra từ code; kiểm tra thật trên Android; chưa chạy.
- Không phát hành trong bất kỳ gói nào. Không xin xác nhận giữa các thao tác đã nằm trong gói được giao; chỉ dừng nếu cần quyết định ngoài phạm vi hoặc thiếu điều kiện bắt buộc.

## F00 — Cố định baseline và bảo toàn probe

**File:** `docs/paddle-removal/fix-baseline.md`, thư mục log riêng `build/paddle-removal-fix/`.

1. Đọc báo cáo re-audit và các file được nhắc đến; xác nhận code chưa được sửa bởi lượt khác. Kiểm tra AGENTS.md nếu xuất hiện.
2. Lưu git status, danh sách diff có sẵn, thời gian và hash artifact. Không sao chép source hoặc secrets ra ngoài workspace.
3. Kiểm tra `build/paddle-reaudit/tests/com/tscanner/app/PaddleIndependentReauditTest.kt` và XML probe còn tồn tại. Nếu mất do clean, dựng lại đúng hai ca cancellation mô tả trong báo cáo, không cần khôi phục toàn bộ build folder.
4. Chạy bộ chuẩn hiện tại một lần; ghi failures và lint warnings. Lưu log baseline trước khi sửa. Không dùng init script probe khi đếm bộ chuẩn.

**Nghiệm thu:** biết rõ lỗi nền; có cách tái hiện hai probe; production chưa đổi.

**Prompt:** Đọc `PLAN_FIX_PADDLE_REMOVAL_SMALL_MODEL_2026-09-24.md`, chỉ làm F00. Giữ working tree, xác minh baseline và probe, ghi kết quả thật. Không sửa production; dừng trước F01a.

## F01a — Tạo điểm kiểm thử lifecycle ML Kit nhỏ và test tái hiện

**Bằng chứng:** `utils/TextRecognitionHelper.kt:746-801`: client mới mỗi lượt, close chỉ ở invokeOnCancellation.

**File được sửa:** `TextRecognitionHelper.kt`; tối đa một helper/adapter nội bộ trong utils nếu cần; test mới `MlKitRecognizerLifecycleTest.kt`. Không đổi OcrModels, mapper, Activity hoặc Tesseract.

**Cách thực hiện:**
1. Đọc toàn bộ `processMlKitRecognition`, hiểu lúc tạo recognizer, tạo InputImage, đăng ký callbacks, map layout, resume, hủy và close.
2. Tạo seam hẹp cho client factory/task lifecycle; giữ factory mặc định gọi TextRecognition.getClient. Test sử dụng fake client/task có điều khiển completion, close counter và callback đến trễ. Không tạo một pipeline song song chỉ dùng trong test.
3. Nếu Android Bitmap/InputImage làm JVM không gọi được adapter, tách riêng bước tạo input qua seam nhỏ hoặc phần quản lý một client/request thành helper production mà đường thật bắt buộc sử dụng. Không dùng unsafe để tuyên bố đã chạy SDK Android thật.
4. Viết test chứng minh hiện trạng: Success, NoText và Failure hoàn thành nhưng chưa close. Test ít nhất một ca đi qua chính lifecycle được production dùng, không chỉ kiểm tra source text.
5. Kiểm tra factory ném lỗi trước khi có client không được yêu cầu close một đối tượng không tồn tại. Test hooks không làm thay đổi behavior production ngoài khả năng inject.

**Nghiệm thu:** test tái hiện đỏ đúng nguyên nhân thiếu close, production compile; các test routing cũ vẫn đạt. Đây là trạng thái trung gian có test đỏ có chủ đích, không phát hành và không báo gói tổng đã hoàn tất.

**Điểm dừng:** bàn giao rõ test đang đỏ cho F01b. Không tiếp tục tự sửa lifecycle trong F01a.

**Prompt:** Đọc kế hoạch, chỉ làm F01a sau F00. Thêm seam nhỏ trong đường adapter thực và test tái hiện thiếu close ở success/no-text/failure. Không dùng mlKitChineseRunner để bỏ qua adapter, không thêm framework/dependency mới. Báo test đỏ dự kiến và dừng trước F01b.

## F01b — Đóng recognizer đầy đủ và xử lý race completion/cancellation

**File:** chỉ các file của F01a.

**Hợp đồng bắt buộc:**
- Một client thuộc một request; mọi client tạo thành công đều có đúng một lần thử close khi request kết thúc/hủy. Factory chưa trả client thì close count = 0.
- Success giữ text/layout/engineId; NoText giữ NoText; thiếu model giữ ModelUnavailable; lỗi thực thi giữ Failure; coroutine hủy giữ cancellation.
- Không double resume, không bỏ treo continuation nếu mapping hoặc đăng ký listener ném lỗi.
- Exception từ close không che kết quả/lỗi gốc và không gây uncaught callback exception. Không log nội dung tài liệu.

**Các bước sửa:**
1. Đưa release vào hàm close-once có guard an toàn giữa các thread. Không chỉ chuyển close ra finally ngay sau đăng ký listener: task SDK còn bất đồng bộ, đóng lúc đó là quá sớm.
2. Hợp nhất kết thúc request vào cơ chế terminal/complete-once rõ ràng. Dùng synchronization/atomic phù hợp hoặc API continuation ổn định trong coroutines hiện có; không thêm API experimental chỉ để tránh thiết kế race.
3. Đăng ký cancellation handling đủ sớm. Nếu cancel xảy ra trong lúc factory đang tạo client, client trả về sau đó vẫn phải được close và không được khởi chạy inference mới. Kiểm tra ownership khi bàn giao client, không chỉ `if (cont.isActive)` một lần.
4. Bọc xử lý callback success, đặc biệt mapVisionTextToOcrPage: mapping ném lỗi phải chuyển Failure hoặc cancel phù hợp và close; không để exception thoát listener khiến coroutine treo. Lỗi đồng bộ lúc tạo input/process/đăng ký listener cũng đi terminal path.
5. Late success/failure sau cancel không publish kết quả, không map/recycle/đụng bitmap đã hết quyền sở hữu và không close lần hai. Không giả định close() bảo đảm hủy native task ngay lập tức; giữ chính sách ownership bitmap hiện có và kiểm tra Android ở F06. Không tự refactor bitmap lifecycle ngoài gói.
6. Giữ classification lỗi model đang có; không biến thiếu model thành Success rỗng hoặc NoText. Không thay routing Chinese hay metadata tài liệu cũ.

**Ma trận test bắt buộc:**
| Tình huống | Kết quả | Close |
|---|---|---|
| Success có chữ/layout | Success đúng nội dung/geometry | 1 |
| Success không chữ | NoText | 1 |
| Task thiếu model | ModelUnavailable | 1 |
| Task lỗi thường | Failure | 1 |
| Factory ném lỗi | Failure/model classification đúng | 0 |
| Tạo input/process/đăng ký callback ném lỗi sau có client | Failure | 1 |
| Mapper ném lỗi trong callback | Failure, không treo | 1 |
| Cancel trước/during factory | Cancellation, không dispatch sau cancel | 0 hoặc 1 tùy client có được tạo |
| Cancel khi task pending, sau đó late callback | Cancellation, không publish/map | 1 |
| Completion và cancel cạnh tranh | Một terminal outcome hợp lệ, không double resume | 1 |
| close ném lỗi | Giữ outcome gốc, không crash | 1 lần thử |
| Nhiều request nối tiếp/cạnh tranh | Không đóng nhầm client request khác | 1 mỗi client |

Không dùng `isActive` check đơn độc làm chứng cứ thread-safe. Race tests phải điều khiển thứ tự, và kiểm tra callback thread không có uncaught exception.

**Nghiệm thu:** test F01a chuyển xanh; toàn ma trận trên đạt ở logic production; các test Chinese routing/failure/cancel cũ đạt. Memory ổn định trên thiết bị vẫn là việc F06, không suy ra từ fake close counter.

**Prompt:** Đọc kế hoạch, chỉ làm F01b trên seam F01a. Sửa close-once/terminal handling của adapter, bao phủ cancellation trong lúc factory tạo client và callback mapping ném lỗi. Giữ result contract và bitmap ownership; chạy test lifecycle/routing. Dừng trước F02.

## F02 — Cleanup truyền cancellation nhưng vẫn chịu được lỗi I/O

**Bằng chứng:** `LegacyPaddleCleanup.kt:138-147,155-159`; hai probe trong `build/paddle-reaudit/` đã fail.

**File:** `utils/LegacyPaddleCleanup.kt`, `LegacyPaddleCleanupTest.kt`. Chỉ sửa `TScannerApplication.kt` nếu phát hiện nó nuốt cancellation; hiện đã có nhánh rethrow thì giữ nguyên.

1. Chuyển hai test probe file/directory deletion cancellation vào bộ test chuẩn. Chạy trước sửa để thấy fail.
2. Thêm catch CancellationException trước catch Throwable ở cả hai điểm xóa file và thư mục; rethrow đúng exception.
3. Để cancellation thật của coroutine được thấy giữa các thao tác đồng bộ, thêm checkpoint nhỏ: ví dụ `cleanupDirectory(baseDir, checkActive = {})`, production wrapper lấy coroutine context hiện tại và truyền callback ensureActive. Kiểm tra trước vòng xử lý, trước mỗi deletion và trước xóa root. Test trực tiếp có thể inject callback. Không đổi helper thành kiến trúc job phức tạp.
4. Không hứa interrupt File.delete đang chạy; chỉ ngăn tiếp tục xóa sau khi checkpoint nhận cancellation. Cancellation không ghi success summary hoặc cờ completed.
5. Giữ nguyên root filesDir/paddleocr, allowlist bốn file, kiểm tra link/canonical, chỉ regular files và xóa root khi rỗng; không recursive. I/O failure vẫn skip/retry lần sau, không chặn khởi động.

**Test:** cancellation từ fileDeleter; directoryDeleter; checkpoint sau xóa file đầu (file đầu có thể mất, các file sau còn); lần chạy tiếp dọn phần còn lại; IOException/false vẫn không crash; unknown/sibling/Tesseract sentinel giữ hash; root thiếu và repeated cleanup; các test symlink hiện có vẫn đạt hoặc ghi lý do chưa chạy.

**Nghiệm thu:** hai probe cũ xanh, cancellation không bị nuốt, lỗi I/O vẫn fail-soft, không mở rộng phạm vi xóa.

**Prompt:** Đọc kế hoạch, chỉ làm F02. Chuyển probe thành test chuẩn, rethrow cancellation ở hai inner catch và thêm checkpoint nhỏ giữa thao tác. Giữ allowlist/no-link/no-recursion, kiểm tra sentinel và retry; dừng trước F03.

## F03 — Test legacy qua repository, edit và writer thực

**Bằng chứng thiếu coverage:** `PaddleRemovalVerificationTest.kt:79-193` chỉ serialize/parse/copy/serialize. Không xóa test này, bổ sung đường thực.

**File được thêm/sửa:** test mới `PaddleLegacyPersistenceExportTest.kt`; fixture dưới `app/src/test/resources/`; không sửa production để ép test pass. Đọc cách dựng repository ở `OcrDocumentRepositoryTest.kt` và writer tests hiện có.

**Production phải được gọi:**
- `ocr/data/OcrDocumentRepository.kt`: saveDocument/loadDocument, lưu thực trong temporary directory.
- Command/reducer edit thực đang dùng trong `ocr/editor/` hoặc vị trí được code hiện tại khai báo; xác minh tên trước sử dụng, không tự thay bằng data class copy.
- `ocr/export/DocxWriter.kt`: generateDocxStream; `XlsxWriter.kt`: generateXlsxStream, dùng stream thật.

**Kịch bản:**
1. Fixture tối thiểu 2 trang engineId=paddle: chữ Trung, line-only không tokens, polygon, bảng có ô đã sửa, editedContent/hasUserEdits phù hợp schema; source images nằm trong thư mục thử có hash. Chỉ bỏ field mới nếu thật sự kiểm tra tương thích schema cũ, không tự nhận mọi fixture là bản lịch sử thực.
2. Persist theo repository API và expected revision thực; assert RepositoryResult.Success, không unwrap null rồi bỏ qua failure.
3. Khởi tạo instance repository mới nếu API test cho phép, đọc từ disk để tránh chỉ kiểm tra state trong RAM. Kiểm tra engineId, text, geometry, bảng, editedContent, source image path/hash.
4. Dùng command/reducer thật sửa text và một ô bảng; save rồi mở lại, đối chiếu nội dung/metadata. Không kích hoạt OCR để “nâng cấp” tài liệu.
5. Xuất DOCX/XLSX bằng writer production vào ByteArrayOutputStream hoặc file tạm, đọc ZIP/XML để kiểm tra nội dung text/cell đã sửa, không chỉ `size > 0`. Kiểm tra XML namespace/escaping, các trang không bị mất. Không yêu cầu OOXML chứa engineId nếu định dạng export không có trường đó; kiểm tra engineId trong repository document trước/sau export.
6. Chạy line-only selection bằng OcrSelectionController như control; export không đổi tài liệu đã lưu, source image hashes không đổi.
7. Nếu test lộ lỗi repository/editor/export có sẵn, ghi repro và dừng sửa production ở gói này; báo gói bổ sung cần thiết. Không đổi scope sang chữa cả OCR editor.

**Nghiệm thu:** có bằng chứng disk round-trip + edit production + export production. Không tuyên bố test này kiểm chứng Activity tự động OCR lại hoặc PDF Android renderer; phần đó để F06.

**Prompt:** Đọc kế hoạch, chỉ làm F03. Thêm test tích hợp legacy bằng repository/command/writer thật, đọc lại disk và nội dung XML DOCX/XLSX. Giữ metadata paddle, nguồn ảnh và dữ liệu đã sửa. Không sửa production ngoài scope khi phát hiện lỗi nền; báo và dừng trước F04.

## F04 — Sửa báo cáo và chuẩn bị quy trình Android có thể thực hiện

**File:** `docs/paddle-removal/verification.md`, `device-acceptance.md`; thêm `docs/paddle-removal/fix-handoffs/F04.md` nếu cần. Không sửa số liệu lịch sử như thể là kết quả mới; thêm phần đính chính có ngày và link bằng chứng.

1. Bỏ hoặc giới hạn các câu “100%”, “hoàn hảo”, “toàn diện” vượt evidence. Tách serialization, repository/export, Activity và Android thành các cột độc lập.
2. Thay hướng dẫn install APK unsigned bằng chuẩn bị hai bản baseline/candidate đã ký cùng test key. Ký là yêu cầu cài APK, không chỉ Android 14+.
3. Giữ baseline unsigned nguyên vẹn, tạo bản sao ký trong thư mục test riêng. Nếu bước ký thực sự được giao, dùng SDK tools đã cài: zipalign trước, apksigner sign sau, apksigner verify --verbose --print-certs; không sửa keystore production, không hardcode password vào script/log. Nếu không có test key, ghi rõ bước tạo/chuẩn bị key cho môi trường thử; không chuyển sang dùng khóa production.
4. Trước install -r: xác nhận applicationId bằng nhau, certificate digest bằng nhau, ABI tương thích và versionCode không gây downgrade. Không sửa version toàn dự án hoặc uninstall để vượt qua lỗi upgrade. Máy đã cài bản ký khác thì dùng emulator/thiết bị thử riêng.
5. Phân biệt test upgrade giữ dữ liệu với fresh-install/offline. Fresh profile/emulator phù hợp cần Google Play Services nhưng chưa tải model Chinese; xác minh thiếu model thật trước bật mạng. Uninstall T-Scanner không chứng minh đã xóa model do Play Services quản lý. Không clear data Play Services trên máy cá nhân.
6. Đặt MANUAL zh-Hans/zh-Hant và engine Auto/ML Kit khi kiểm tra Chinese; language AUTO hiện là VI/EN, không dùng ca đó rồi kết luận routing Chinese hỏng. Ca batch mixed phải có request/language setup thực sự đi Chinese, không chỉ ghép ảnh Trung vào pipeline VI/EN.
7. Hướng dẫn đặt sentinel/data trong private storage phải chỉ rõ debug build/run-as, instrumentation hoặc emulator test có quyền phù hợp. Không hứa `adb shell` thường ghi được `/data/data` của release app; không root thiết bị người dùng để nghiệm thu.

**Nghiệm thu:** quy trình không dùng unsigned APK để install, không mất dữ liệu upgrade, không false pass từ model cache và không giả lập quyền private storage. Tất cả ca chưa thực chạy giữ trạng thái CHƯA CHẠY.

**Prompt:** Đọc kế hoạch, chỉ làm F04. Sửa mức khẳng định trong báo cáo và quy trình Android: hai APK ký cùng test key, xác minh identity, model cache sạch, MANUAL Chinese và quyền tạo dữ liệu thử. Không publish/cài lên máy người dùng; dừng trước F05.

## F05 — Chạy cổng máy build và kiểm tra artifact cuối

**Phạm vi:** log, kết quả, `docs/paddle-removal/fix-verification.md`; không sửa production khi kiểm tra trừ khi được giao lại gói lỗi.

Chạy từ repo root, dùng Gradle cache có quyền ghi; Access denied wrapper lock là lỗi môi trường trước khi kết luận compile lỗi:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline --console=plain
```

- Chạy focused tests F01–F03 trước full suite. Không bật init script probe khi đếm suite chuẩn. Không cần tăng số test cho đẹp; ghi số thật và failures/skips.
- Kiểm tra ZIP APK/AAB không còn assets Paddle hay libonnxruntime/libonnxruntime4j_jni ở mọi ABI; dependency release không có ONNX.
- Đo bytes/SHA-256 của artifact cuối và so baseline cùng cấu hình. Không lấy số 28,69/17,99 MB cũ làm kết quả mới. Tăng nhỏ do helper/test seam có thể hợp lý; giải thích delta, không thay ABI/shrink options để bù.
- Đọc diff xác nhận không thêm lại engine, không sửa dữ liệu lịch sử, không đụng secrets/signing. Rà test hook reset và không vô tình đưa fixtures model vào main assets.
- Ghi lint errors/warnings so baseline; build UP-TO-DATE phải ghi đúng, không tuyên bố đã clean build.

**Nghiệm thu:** bộ chuẩn xanh, không regression mới, lifecycle/cancellation/legacy integration đạt, artifact đúng. Chỉ được ghi “đạt cổng máy build, chờ F06”.

**Prompt:** Đọc kế hoạch, chỉ làm F05 sau F04. Chạy focused/full checks, release/dependency/ZIP, đo bytes và kiểm tra diff. Ghi kết quả thật và runtime còn thiếu; không tự phát hành, dừng trước F06.

## F06 — Kiểm tra Android và nghiệm thu cuối

**Điều kiện:** thiết bị/emulator thử riêng, baseline/candidate cùng ký đã xác minh. Nếu không có thiết bị, ghi BLOCKED bởi thiếu thiết bị trong báo cáo công việc, không đánh dấu runtime đạt và không suy diễn từ JVM. Không cần đặt trạng thái goal/tool tự động.

Các ca bắt buộc:
1. Upgrade tại chỗ có prefs paddle + MANUAL zh-Hant + tài liệu legacy đã sửa: preference thành Auto, zh-Hant giữ nguyên; dữ liệu/hash không mất; cleanup chỉ xóa allowlist.
2. Fresh install khi Chinese model chưa có, offline: lỗi model có kiểm soát, không NoText, không đè kết quả cũ, không bỏ trang. Bật mạng tải model, OCR thành công; tắt mạng kiểm tra lại.
3. Chinese giản thể/phồn thể, bảng/chữ nhỏ/ảnh nghiêng/mixed English: so cùng bộ ảnh và ground truth; kiểm tra text, thứ tự đọc, vùng chọn, edit/save/reopen/export. Ghi sai khác, không đánh giá chỉ qua “có chữ”.
4. OCR lặp/nhiều trang, success/error/cancel/back/rotate: không uncaught exception, không resume sau Activity đã hủy, không treo request. Thu heap/native memory hoặc meminfo theo quy trình lặp cố định trước/sau, ghi thiết bị và warm-up; không đặt ngưỡng RAM tùy tiện, không coi một snapshot là chứng minh không leak.
5. Legacy line-only/table: chọn, sửa, lưu, mở lại và xuất trên UI thật; không tự OCR lại chỉ vì engineId cũ.
6. Smoke Việt/VI_EN, Latin, Nhật/Hàn/Hindi và chụp/crop/PDF để phát hiện regression adapter chung; chỉ kiểm tra Drive theo phạm vi smoke đang có.
7. ARM64 bắt buộc; ARM32 nếu còn phát hành thì thử hoặc ghi thiếu nghiệm thu ABI đó, không tuyên bố tất cả thiết bị đạt.

**Báo cáo:** mỗi ca có setup, thiết bị/OS/ABI, APK hash/cert, bước, kỳ vọng, thực tế, log/screenshot cần thiết và PASS/FAIL/CHƯA CHẠY. Che nội dung tài liệu cá nhân. Dùng dữ liệu thử.

**Nghiệm thu cuối:** F01–F05 đạt và các ca runtime bắt buộc đạt, không mất dữ liệu/nâng cấp lỗi, thiếu model không thành trang trắng, không lifecycle crash/hang. Nếu lỗi mới phát hiện, ghi gói sửa riêng và chạy lại phần ảnh hưởng; không kết luận mọi lỗi toàn ứng dụng đã hết. Chưa publish.

**Prompt:** Đọc kế hoạch, chỉ làm F06 trên môi trường thử đã chuẩn bị. Kiểm tra upgrade không uninstall, missing-model/offline, Chinese quality, lifecycle nhiều trang và legacy UI thật. Ghi evidence từng ca; thiếu thiết bị thì giữ CHƯA CHẠY. Không phát hành.

## 3. Mẫu bàn giao bắt buộc sau mỗi gói

```text
Gói / phụ thuộc đã đạt:
Files sửa (phân biệt thay đổi có sẵn):
Hành vi trước → sau và bằng chứng:
Regression tests gọi production nào:
Lệnh đã chạy / kết quả / failures / skips / lint:
Thiết bị đã kiểm tra hoặc chưa kiểm tra:
Lỗi nền, rủi ro hoặc blocker:
Điểm dừng và gói tiếp theo:
```

Nếu cần hoàn tác, chỉ hoàn tác diff của chính gói dựa trên baseline đã lưu; không reset repository và không cài hạ phiên bản lên dữ liệu người dùng thật. Việc có báo cáo kế hoạch này không đồng nghĩa các sửa đổi đã được thực hiện hoặc kiểm thử.
