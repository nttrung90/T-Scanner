# Kế hoạch sửa lỗi cho mô hình nhỏ — 2026-09-21

Trạng thái: kế hoạch để duyệt và giao việc; chưa triển khai sửa mã.

## 1. Phạm vi và cách giao việc

Áp dụng tại `E:\DU AN AI\T-Scanner`, module Android `:app`. Nguồn phát hiện: `RECHECK_PROJECT_2026-09-21.md`. Đã đối chiếu lại các điểm F1/F2/F3/R1/R2 với mã hiện tại khi lập kế hoạch này. F1/F2 là lỗi nhánh thất bại trong helper; F3 là thiếu khôi phục trạng thái điều hướng; R1/R2 là rủi ro đồng thời và RAM có bằng chứng trong mã, chưa tái hiện trên thiết bị.

Giao **một gói mỗi lượt**, nhận báo cáo và kiểm tra diff trước khi giao gói tiếp theo. Không yêu cầu mô hình nhỏ sửa cả dự án. Thứ tự: **S01 → S02 → S03 → S04 → S05 → S06**. S02 bắt buộc dùng kết quả S01; S06 dùng bản tích hợp của cả năm gói trước.

| Gói | Đích sửa | Phạm vi chính | Điểm dừng |
| --- | --- | --- | --- |
| S01 | F1: chỉ báo thành công khi danh mục đã lưu | Helper nhập ảnh và nơi nhận kết quả | Test lỗi lưu qua; bàn giao hợp đồng kết quả |
| S02 | F2: đủ ảnh, đúng số trang | Helper nhập ảnh, test của S01 | Test URI lỗi và hủy tác vụ qua |
| S03 | F3: khôi phục tab | MainActivity | Nội dung và tab được chọn trùng nhau sau recreate |
| S04 | R1: chặn writer của phiên đã hủy | Repository bản nháp | Test thứ tự writer/hủy xác định được qua |
| S05 | R2: tính ngân sách theo cấp phát thực tế | Engine và hàm ước lượng thuần | Test phép tính và nhánh từ chối qua |
| S06 | Kiểm tra bản tích hợp | Test, báo cáo và thiết bị | Ghi rõ mục đạt/chưa chạy/lỗi còn lại |

## 2. Quy tắc chung cho mọi gói

- Đọc trạng thái Git và hướng dẫn AGENTS.md nếu có. Working tree có nhiều thay đổi chưa commit; sửa trực tiếp phần liên quan, không reset/checkout/clean hoặc ghi đè nguyên tệp để quay về HEAD. Không commit/push nếu chưa được yêu cầu.
- Các đường dẫn dưới đây tính từ gốc dự án. Số dòng là mốc khảo sát, phải tìm lại tên hàm trước khi sửa.
- Chỉ thay đổi tệp trong phạm vi gói. Nếu cần mở rộng, ghi rõ nguyên nhân và phạm vi tối thiểu; không nhân tiện sửa OCR, Drive, camera, thuật toán tăng nét hoặc toàn bộ cảnh báo lint.
- Mỗi lỗi dữ liệu/đồng thời phải có test hồi quy chạy qua logic production. Có thể tạo interface/callback nội bộ nhỏ để tiêm lỗi đọc, ghi, converter hoặc repository; không xây framework mới. Không viết lại thuật toán trong test rồi chỉ kiểm tra bản sao đó.
- Dùng JUnit hiện có cho logic thuần. Test Android cần Context, ContentResolver, PdfRenderer hoặc Activity thì chạy instrumentation, hoặc tách ranh giới I/O tối thiểu. Không coi Android stub của JVM là kiểm chứng runtime.
- Hủy coroutine phải truyền tiếp CancellationException; không đổi hủy tác vụ thành thành công hoặc lỗi thông thường. Dọn tệp theo quyền sở hữu của lần nhập, không xóa nguồn URI do người dùng chọn.
- Thông báo mới phải qua resource, hỗ trợ tám ngôn ngữ hiện có: `values`, `values-vi`, `values-es`, `values-pt`, `values-fr`, `values-in`, `values-de`, `values-ja`. Ưu tiên thông báo sẵn có đúng nghĩa.
- Báo cáo sau mỗi gói: nguyên nhân, tệp thay đổi, test nào bắt hành vi cũ, lệnh và kết quả thực tế, mục chưa kiểm chứng, gói tiếp theo. Không tự triển khai gói tiếp theo.

## 3. S01 — Kiểm tra kết quả lưu danh mục trước khi báo thành công

**Ưu tiên:** cao. **Phụ thuộc:** không.

**Bằng chứng:** `utils/DocumentImportHelper.kt:88–104` bỏ qua Boolean của `repo.addDocument(item)` rồi báo thành công/mở Viewer. `data/repository/DocumentRepo.kt:254–278` trả false và rollback danh sách khi saveData thất bại.

**Tái hiện cần kiểm chứng:** converter tạo PDF thành công, nhưng ép bước lưu danh mục trả false. Hiện tại helper vẫn trả tài liệu thành công.

**Tệp được sửa:** `utils/DocumentImportHelper.kt`; `ui/home/HomeFragment.kt` và `ui/tools/ToolsFragment.kt` nếu cần nhận kết quả có kiểu; một tệp test `DocumentImportHelperTest.kt` mới; resource thông báo nếu thiếu. `DocumentRepo.kt` chỉ đọc, vì đã có Boolean báo lỗi.

**Thực hiện:**

1. Định nghĩa kết quả tối thiểu phân biệt thành công và lỗi lưu danh mục. Chỉ phát thành công sau khi `addDocument` trả true. Giữ một nơi chịu trách nhiệm toast/mở Viewer để không hiện hai lần.
2. PDF và thumbnail của lần nhập thất bại chưa thuộc danh mục: dọn có kiểm soát, không đụng tệp cũ hoặc ảnh gốc. Dùng tên đầu ra chứa docId/UUID để xác định quyền sở hữu và tránh trùng tên khi nhập đồng thời.
3. Khi lưu danh mục thất bại: trả lỗi rõ ràng cho UI, không toast thành công, không tự mở Viewer như tài liệu đã lưu. Nếu dọn thất bại, ghi nhận đường dẫn còn sót trong log nội bộ; không đổi thành kết quả thành công.
4. Tạo ranh giới tiêm kết quả persist nhỏ nhất để test gọi đúng luồng đang được Home và Tools sử dụng.

**Test/tiêu chí nghiệm thu:** persist=false hoặc ném lỗi → không có callback thành công/mở Viewer; output riêng của lần nhập được xử lý; nguồn người dùng và tài liệu cũ còn nguyên. Persist=true → chỉ một tài liệu, một thông báo/một lần mở Viewer. Converter=false → không gọi persist. Hai caller vẫn dùng cùng helper và nhãn/điều hướng mới giữ đúng chức năng.

**Bàn giao:** tên kiểu kết quả, ai hiển thị lỗi/thành công, quyền sở hữu các tệp và test seam để S02 dùng tiếp.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ triển khai S01. Kiểm tra lại DocumentImportHelper và hợp đồng Boolean của DocumentRepo.addDocument. Sửa lỗi báo thành công khi persist thất bại; thêm test gọi logic production với persist=false, persist=true và converter=false. Áp dụng quy tắc chung mục 2, giữ thay đổi chưa commit. Báo hợp đồng kết quả và chính sách dọn tệp cho S02; dừng sau S01.

## 4. S02 — Không tạo PDF thiếu ảnh âm thầm

**Ưu tiên:** cao. **Phụ thuộc:** S01 đã được kiểm tra.

**Bằng chứng:** `DocumentImportHelper.kt:37–50` nuốt lỗi đọc và chỉ thêm tệp còn đọc được; dòng 83/101 dùng uris.size để đếm và báo thành công.

**Tái hiện:** chọn ba URI, URI thứ hai trả null hoặc lỗi I/O. Hai ảnh còn lại có thể thành PDF nhưng danh mục/thông báo vẫn ghi ba trang. Tệp sao chép dở nhưng có dung lượng cũng có thể lọt vào danh sách đầu vào.

**Tệp được sửa:** helper và test của S01; resource lỗi; caller chỉ khi hợp đồng S01 cần bổ sung nhánh lỗi.

**Quyết định hành vi:** lần nhập phải **đủ tất cả ảnh đã chọn mới thành công**. Nếu một ảnh không đọc/sao chép/giải mã được, báo lỗi và không lưu PDF thiếu trang. Không thêm tùy chọn bỏ qua ảnh trong gói này.

**Thực hiện:**

1. Chỉ ghi nhận ảnh sau khi sao chép hoàn tất, stream có dữ liệu và bước kiểm tra ảnh phù hợp thành công. Stream null, rỗng, IOException giữa chừng, ảnh hỏng đều là lỗi.
2. Dừng lần nhập lỗi; dọn tệp do lần nhập tạo, không persist. Dùng finally hoặc cơ chế tương đương để dọn khi hủy; tôn trọng ranh giới commit của S01, không xóa PDF đã được danh mục nhận thành công.
3. Số trang chỉ lấy từ đầu vào đã xác nhận đầy đủ và kết quả PDF được xác nhận. Không chỉ sửa `uris.size` thành số ảnh còn lại để che việc thiếu trang.
4. Trường hợp tất cả ảnh lỗi/converter thất bại phải có phản hồi; người dùng hủy picker khi chưa chọn ảnh thì kết thúc bình thường.

**Test/tiêu chí nghiệm thu:** ba ảnh hợp lệ → ba trang đúng thứ tự, một tài liệu. Lỗi tại ảnh đầu/giữa/cuối, stream null, tệp rỗng, đọc dở, dữ liệu ảnh hỏng → không persist, không báo thành công, có lỗi và dọn output của lần nhập. CancellationException vẫn truyền tiếp. PDF thật và số trang kiểm bằng PdfRenderer trên Android ở S06; JVM test không thay thế bước đó.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ triển khai S02 trên kết quả S01. Dùng hợp đồng kết quả hiện có, áp dụng quy tắc đủ tất cả ảnh mới tạo tài liệu thành công. Thêm test lỗi URI đầu/giữa/cuối, null, đọc dở, dữ liệu hỏng và cancellation qua logic production. Không sửa luồng Nhập ảnh riêng của Tools ngoài phạm vi helper này. Báo test và mục còn cần thiết bị; dừng sau S02.

## 5. S03 — Khôi phục tab cùng Fragment sau khi Activity được tạo lại

**Ưu tiên:** vừa. **Phụ thuộc:** không có phụ thuộc mã; thực hiện sau S02 để dễ kiểm tra diff.

**Bằng chứng:** `MainActivity.kt:146–149` chỉ chọn tab lúc khởi tạo mới; `activity_main.xml:55–71,116–131` mặc định tô Trang chủ. Chưa có lưu/khôi phục ID tab đang chọn. MainActivity khóa portrait nên xoay điện thoại đơn thuần không phải phép thử đáng tin cậy.

**Tệp được sửa:** `MainActivity.kt`; test Android điều hướng mới. Chỉ chỉnh cấu hình test trong `app/build.gradle` nếu dependency/runner thực sự thiếu; không nâng cấp thư viện hàng loạt.

**Thực hiện:** lưu ID tab đang chọn vào savedInstanceState; xác thực ID và fallback Home khi dữ liệu không hợp lệ. Khi phục hồi, đồng bộ màu/chữ với Fragment đã được FragmentManager khôi phục. Có thể tách cập nhật thanh tab khỏi chuyển Fragment; tránh tạo/thay Fragment mới chỉ để tô lại thanh dưới, làm mất trạng thái đã phục hồi.

**Test/tiêu chí nghiệm thu:** khởi chạy mới → Home; chọn từng tab rồi recreate → nội dung, màu, chữ đậm cùng tab; chọn Tất cả → Tools; recreate tại Tools rồi nhập PDF vẫn nhận đúng kết quả picker. Kiểm tra callback/Fragment không bị nhân đôi. Test Activity recreate hoặc kiểm tra thiết bị; không chỉ tìm chuỗi `savedInstanceState` trong mã rồi kết luận đạt.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ triển khai S03. Sửa MainActivity để khôi phục tab đang chọn cùng Fragment đã phục hồi, không tạo Fragment trùng hoặc đánh mất trạng thái. Kiểm tra Home mặc định, Tất cả sang Tools và recreate trên các tab. Nếu không có thiết bị, báo phần kiểm thử lifecycle chưa chạy; dừng sau S03.

## 6. S04 — Chặn ghi trở lại phiên bản nháp đã hủy

**Ưu tiên:** cao nếu tái hiện được race. **Phụ thuộc:** không có phụ thuộc mã với S01–S03.

**Bằng chứng:** `PostScanSessionRepository.kt:159–178,215–233` kiểm tra closedSessions nhưng discard xóa cả dấu đóng và lock. `initializeSession:79` cũng bỏ dấu đóng. `PostScanEditorViewModel.kt:397–404` tạo job lưu; hủy phiên không chờ/hủy toàn bộ các job đó trước khi cho phép ghi lại.

**Tệp được sửa:** `data/repository/PostScanSessionRepository.kt`; test đóng/hủy phiên mới. Chỉ sửa `PostScanEditorViewModel.kt` nếu việc phối hợp vòng đời cần thiết để giải quyết cùng race; không gộp sửa trạng thái SAVED/revision khác vào gói này.

**Thực hiện:**

1. Dựng test thứ tự xác định bằng barrier/latch/deferred: một yêu cầu lưu cũ bị giữ trước khi vào vùng ghi; hủy xong phiên rồi cho yêu cầu lưu chạy tiếp. Không dùng sleep ngẫu nhiên.
2. Giữ dấu đóng và đồng bộ hóa cho cùng session đủ lâu để writer cũ không mở lại phiên. Cách tối thiểu là coi sessionId đã đóng là không tái sử dụng trong vòng đời repository; mọi phiên mới nhận ID mới. Nếu chọn generation thay thế, phải kiểm chứng generation ở cả initialize và save.
3. Không chỉ xóa dòng `closedSessions.remove` trong discard rồi bỏ qua initialize hoặc thay lock trong khi có writer còn tham chiếu lock cũ. Kiểm tra mọi caller initialize/close/discard, kể cả discard từ Trang chủ.
4. Phiên đang hoạt động vẫn lưu được; session mới độc lập. Ghi rõ vòng đời và giới hạn lưu dấu đóng, không tự xóa dấu chỉ theo thời gian khi chưa chứng minh writer đã kết thúc.

**Test/tiêu chí nghiệm thu:** writer trước hủy hoàn tất trước khi dọn; writer đến muộn bị từ chối; thư mục/metadata không xuất hiện lại; hai writer không dùng hai lock khác nhau cho cùng phiên; phiên ID mới hoạt động. Test phải chạy cơ chế production. Nếu phụ thuộc AtomicFile/Context thì dùng instrumentation hoặc tách cơ chế đồng bộ thuần đang được repository sử dụng.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ triển khai S04. Trước hết dựng test race xác định cho saveDraft đến muộn sau discardSession. Sửa vòng đời dấu đóng và lock, kiểm tra cả initializeSession có thể mở lại session. Không dùng sleep để chứng minh race, không sửa thêm các lỗi editor khác. Báo thứ tự chạy test và giới hạn process-death chưa kiểm chứng; dừng sau S04.

## 7. S05 — Ước lượng RAM theo nhánh xử lý ảnh

**Ưu tiên:** cao với ảnh lớn. **Phụ thuộc:** không có phụ thuộc mã; nghiệm thu RAM thực tế ở S06.

**Bằng chứng:** `ImageProcessingEngine.kt:350–355` ước lượng 12 byte/pixel; nhánh full resolution có bitmap + mảng pixels tại dòng 388/429. `ImageProcessingAlgorithms.kt:300–301` tạo thêm IntArray độ sáng và LongArray integral. Riêng giai đoạn nhị phân có thể cần khoảng 20 byte/pixel, chưa tính bộ nhớ phụ. Thuật toán tăng nét cũng có nhiều mảng toàn ảnh tại dòng 59/74/75.

**Tệp được sửa:** `utils/ImageProcessingEngine.kt`, một hàm/lớp ước lượng thuần nhỏ và test `ImageMemoryBudgetTest.kt`. Đọc `ImageProcessingAlgorithms.kt` để đếm cấp phát; chưa thay đổi thuật toán/chất lượng ảnh trong gói này.

**Thực hiện:** liệt kê bitmap/mảng sống đồng thời tại từng giai đoạn original, xoay/crop, whitening, sharpen, grayscale, black-white và kết hợp. Dùng giá trị đỉnh theo giai đoạn; tính bảo thủ theo ảnh nguồn khi chưa xác định kích thước crop. Tách phép tính khỏi Runtime để test với ngân sách giả. Dùng Long, kiểm tra kích thước và overflow; kiểm tra trước cấp phát ảnh lớn. Từ chối có kiểm soát khi thiếu ngân sách, giữ draft và trả lỗi cho luồng export.

**Không coi tăng hệ số cố định hoặc bắt OutOfMemoryError là đủ:** hệ số cần gắn với cấp phát thực tế. Phép tính là dự phòng cho một tác vụ; chưa bảo đảm an toàn cho nhiều tác vụ đồng thời hoặc toàn bộ native heap. Nếu phát hiện cần semaphore/tiles, ghi thành đề xuất tiếp theo thay vì mở rộng gói.

**Test/tiêu chí nghiệm thu:** phép tính cho từng nhánh, 12/24/48 MP, kích thước không hợp lệ, overflow, ngân sách đúng ngưỡng và thiếu một lượng nhỏ. Cùng một ngân sách, nhánh nhiều mảng phải bị từ chối khi vượt đỉnh ước lượng; test kiểm tra guard thực sự được dùng trước decode. Export bị từ chối không xóa draft hoặc báo thành công. Chưa đóng R2 trước phép đo S06.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ triển khai S05. Đếm các cấp phát đồng thời trong engine và algorithms hiện tại; thay phép tính 12 byte/pixel bằng ước lượng theo nhánh có test thuần và guard trước decode. Không đổi thuật toán ảnh hoặc giảm chất lượng âm thầm. Báo bảng ngân sách, test và giới hạn chưa đo RAM trên thiết bị; dừng sau S05.

## 8. S06 — Kiểm tra tích hợp và ghi nhận phần chưa nghiệm thu

**Phạm vi:** test và báo cáo; không tự sửa production. Nếu có lỗi, tạo gói sửa nhỏ riêng với bằng chứng.

Chạy toàn bộ unit test, assembleDebug và lintDebug sau bản tích hợp. Trên thiết bị/emulator: Tất cả → Tools; tạo PDF từ một/nhiều ảnh; hủy picker; ảnh lỗi; mở lại ứng dụng; recreate MainActivity; hủy draft khi có lưu đang chờ. Với PDF thật, đối chiếu số trang/thứ tự và danh mục. Đo RAM/time khi export 12/24/48 MP ở các nhánh đã liệt kê, cả trường hợp từ chối vì thiếu RAM, xác nhận draft mở lại được. Lưu kích thước ảnh, thiết bị/API, giới hạn heap, bộ lọc, log và kết quả mỗi ca. Dùng dữ liệu thử được phép, tránh ghi nội dung tài liệu riêng tư vào báo cáo.

Nếu thiếu thiết bị, ghi **chưa nghiệm thu runtime**, liệt kê ca còn thiếu; vẫn hoàn tất phần kiểm tra tự động có thể chạy. Build/unit test pass không tự đóng các yêu cầu lifecycle, race I/O hoặc RAM.

**Prompt:**

> Đọc PLAN_FIX_SMALL_MODEL_2026-09-21.md và chỉ thực hiện S06 trên kết quả S01–S05. Chạy kiểm tra tích hợp, kiểm tra thiết bị và đo RAM khi có môi trường. Không sửa production để làm kết quả xanh. Báo bảng ca đạt/thất bại/chưa chạy, bằng chứng và lỗi còn lại; không tuyên bố toàn dự án hết lỗi.

## 9. Lệnh kiểm tra và điều kiện bàn giao

Từ thư mục gốc, dùng cache Gradle đã xác minh:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.tscanner.app.DocumentImportHelperTest' --offline --console=plain
```

Thay tên lớp bằng test thực tế của từng gói. Khi hoàn tất gói có thay đổi mã, chạy:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Nếu cần quyền cache, giải quyết quyền môi trường theo cơ chế công cụ; không coi Access denied là lỗi mã. Test Android chạy bằng `:app:connectedDebugAndroidTest` khi thiết bị và runner sẵn sàng. Không thêm dependency lớn hoặc tải qua mạng chỉ để kiểm tra nhãn/ID.

Mốc kiểm tra ở lượt audit trước: 200/200 JVM test; debug build qua; lint 0 lỗi/711 cảnh báo; không có thiết bị kết nối. Đây là mốc so sánh, không phải kết quả sau sửa. Lượt lập kế hoạch chỉ đối chiếu mã và viết tài liệu, không chạy lại build vì chưa sửa mã. Số test có thể tăng sau các gói; kiểm tra cả nội dung test và đường chạy, không chỉ số lượng.

Mỗi bàn giao phải nêu: gói đã làm; diff có giữ thay đổi sẵn có không; regression test có thất bại với hành vi cũ không; kết quả lệnh; runtime chưa chạy; phạm vi còn lại. Chỉ giao gói tiếp theo sau khi báo cáo gói trước đủ bằng chứng.
