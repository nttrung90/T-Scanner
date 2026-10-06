# Kiểm tra lại đa ngôn ngữ — 2026-09-17

Phạm vi: working tree hiện tại của module `app`, gồm thay đổi chưa commit. Chỉ rà soát và chạy kiểm tra; không sửa production code. Đối chiếu kế hoạch ngày 16/09 và đọc lại implementation, không xem các mục đã đánh dấu hoàn thành là bằng chứng.

## Kết luận kiểm tra

Đã tiến bộ rõ về đưa văn bản vào resources, số lượng bản dịch, alias locale, tìm kiếm ngôn ngữ, mũi tên RTL và empty state OCR. Tuy nhiên **chưa hoàn tất hỗ trợ đa ngôn ngữ**; vẫn có lỗi hành vi OCR, locale, nội dung dịch và cổng kiểm tra.

| Kiểm tra thực hiện | Kết quả |
|---|---|
| `python scripts/audit_localization.py` | PASS: 575 strings, 7 plurals, 41 thư mục locale ngoài mặc định; đủ key |
| `:app:assembleDebug --offline` | PASS |
| `:app:testDebugUnitTest --rerun --offline` | PASS, 68 tests, 0 failures/errors/skipped; đã thực sự chạy lại task test |
| `:app:lintDebug --offline` | **FAIL: 84 errors, 2064 warnings** |
| Thiết bị/emulator, OCR accuracy, migration runtime, AAB language splits | Chưa chạy trong lần rà soát này |

Đã giải quyết lỗi lock cache Gradle bằng `GRADLE_USER_HOME=C:\Users\nguye\.gradle` và quyền thực thi phù hợp; lỗi Lint phía dưới là lỗi thực, không phải lỗi môi trường.

Report Lint mới: `app/build/reports/lint-results-debug.html`, `app/build/reports/lint-results-debug.sarif` và `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`. Không dùng file XML lint cũ trong thư mục reports: lần chạy này xuất HTML/SARIF/text.

## Các lỗi còn lại, theo ưu tiên

### R01 — P1: Lint bị chặn bởi 84 lỗi plural thiếu quantity

**Vị trí:** `app/src/main/res/values-ar/strings.xml:579` và các nhóm plurals cuối những locale bên dưới.

Mỗi locale đều được bổ sung `one/other`, nhưng quy tắc số nhiều khác nhau theo ngôn ngữ. Lint báo 7 lỗi ở mỗi thư mục: `values-ar`, `values-cs`, `values-hr`, `values-lt`, `values-lv`, `values-pl`, `values-ro`, `values-sk`, `values-sl`, `values-b+sr+Latn`, `values-sr`, `values-tl`: tổng 84.

Ví dụ Arabic thiếu `zero`, `two`, `few`, `many`. Runtime có thể fallback sang `other` thay vì crash, nhưng câu đếm sẽ không đúng dạng; tác vụ `lintDebug` hiện thất bại. Còn 35 cảnh báo MissingQuantity trong tổng warnings, cần đọc riêng theo mức độ của Lint.

**Sửa:** thêm đúng quantity và bản dịch tương ứng theo từng locale/alias; không copy nguyên `other` vào tất cả dạng và không suppress MissingQuantity. Kiểm tra 0/1/2/3/11/100 với Android resources. Nghiệm thu: lint không còn lỗi MissingQuantity, kiểm tra nội dung chứ không chỉ tồn tại tên plurals.

### R02 — P1: chưa có luồng UI chọn ngôn ngữ tài liệu OCR

**Vị trí:** `utils/TextRecognitionHelper.kt:64–80`; `ui/dialogs/OcrEngineSelectionDialog.kt:33–65`.

Đã có preference và setter `setOcrDocumentLanguage`, nhưng tìm toàn bộ main source chỉ thấy **khai báo setter, không có callsite**. Key `ocr_doc_language_title` chỉ có trong resources. Dialog OCR vẫn chỉ chọn engine.

Vì chưa có ai lưu ngôn ngữ tài liệu, `getOcrDocumentLanguage` tiếp tục đọc UI locale mỗi lần. Người dùng UI Việt không thể chọn OCR Nhật độc lập. Nghiêm trọng hơn, UI Arabic/Thai bị tự chuyển sang `vi`, nên nhánh `UnsupportedLanguage` không được dùng trong luồng mặc định cho chính hai ngôn ngữ này.

**Sửa:** nối bộ chọn ngôn ngữ tài liệu vào settings/entrypoint OCR, lưu lựa chọn, hiển thị lựa chọn đang dùng. Chưa chọn và UI không hỗ trợ thì yêu cầu chọn ngôn ngữ tài liệu, không âm thầm dùng Việt. Test UI vi + tài liệu ja, UI ar + tài liệu en, và đổi UI mà không đổi lựa chọn OCR đã lưu.

### R03 — P1: forced Tesseract cho French/German vẫn dùng model Việt

**Vị trí:** `utils/OcrModels.kt:56–68`; `utils/TextRecognitionHelper.kt:195–213`.

`isEngineCompatible(TESSERACT, MLKIT_LATIN)` trả true, trong khi `getTessLanguage("fr")`/`("de")` vẫn trả `vie`. Assets hiện có `vie` và `eng`, không có model chuyên biệt French/German. Kết quả có thể được gắn `documentLanguage=fr` dù engine dùng model Việt.

Test mới `OcrRoutingTest.testTessLanguage_selection` còn assert chính `fr -> vie`, nên 68 tests xanh không bắt được lỗi này. Resolver cũng mặc định mọi mã chưa nhận biết thành MLKIT_LATIN; test hiện còn xác nhận `ru` là Latin. Đây không phải kiểm tra năng lực đúng theo script.

**Sửa:** capability table theo ngôn ngữ/model thực tế; không chấp nhận engine không có model phù hợp. Với French/German dùng ML Kit theo capability hiện có, hoặc báo không tương thích khi ép Tesseract. Thêm tests thất bại cho fr→vie, ru→Latin, mã rỗng/sai được coi là hỗ trợ. Không mở rộng model ngoài phạm vi sửa.

### R04 — P1: lỗi model vẫn bị nuốt thành NoText; ModelUnavailable chưa được tạo

**Vị trí:** `utils/TextRecognitionHelper.kt:396–405` (Japanese), các wrapper Korean/Chinese/Latin/Devanagari tương tự; `:164,175`; `ui/viewer/PdfViewerActivity.kt:343–377`.

Các `addOnFailureListener` trả `Result.success("")` cho mọi lỗi. `OcrResult.ModelUnavailable` chỉ có khai báo và nhánh xử lý, không có nơi tạo; `ocr_model_downloading` cũng chưa được sử dụng trong code.

Khi model chưa sẵn sàng/lỗi chạy, nhánh ja/ko/hi trả NoText. Các hàm `recognizeTextFrom*Sync` lại rút mọi trạng thái về `textOrNull ?: ""`, nên caller không thể phân biệt không tương thích, không hỗ trợ hay lỗi tải. Nhánh Chinese vẫn fallback Tesseract English/Latin và có thể trả vài ký tự Latin/số như kết quả tài liệu Chinese thành công.

**Sửa:** giữ structured result xuyên suốt caller; phân loại lỗi model/lỗi chạy và giữ cancellation; chỉ fallback trong capability phù hợp. Báo lỗi/thử lại khi cần, không biến thất bại thành ảnh không có chữ. Test bằng fake recognizer báo model unavailable và trên fresh install offline.

### R05 — P1: tài liệu nhiều trang OCR thất bại vẫn có thể xuất Word thành công

**Vị trí:** `ui/home/HomeFragment.kt:368–383`; `ui/files/FilesFragment.kt:362–375`.

Trong vòng lặp, header trang được append nếu có nhiều trang, kể cả khi `pageText` rỗng do lỗi hoặc không có chữ. Sau đó `resultText.isEmpty()` kiểm tra cả header nên không phát hiện không có nội dung OCR.

**Tái hiện theo luồng code:** tài liệu hai trang, chọn engine không tương thích (ví dụ UI Nhật + Tesseract); wrapper sync trả chuỗi rỗng cho cả hai; kết quả vẫn chứa hai tiêu đề trang và có thể được xuất Word/báo thành công. Trường hợp ảnh trắng cũng gặp. PDF Viewer đã kiểm tra `pageText.isNotBlank()` trước header; Home/Files chưa đồng nhất.

**Sửa:** chỉ append header khi trang có text thật; theo dõi trạng thái từng trang và xử lý lỗi có cấu trúc. Không báo hoàn tất đầy đủ nếu bỏ mất trang vì lỗi. Test hai trang đều rỗng và một trang lỗi/một trang thành công.

### R06 — P2: đủ key nhưng nhiều chuỗi vẫn nguyên tiếng Anh

**Vị trí ví dụ:** `res/values-fr/strings.xml:35,552,553`.

French vẫn có `Instant recognition via Google AI from Camera or Gallery`, `OFFICIAL VIP PLAN`, `Most popular`; nhóm QR còn nhiều chuỗi tiếng Anh. So sánh chính xác với base: French 111/575, German 109/575, Japanese 101/575, Arabic 101/575, Thai 101/575; toàn bộ locale ngoài vi/en có khoảng 99–171 chuỗi giống base.

Số này có cả thương hiệu/ký hiệu hợp lệ, **không phải số lỗi dịch**. Các câu ví dụ trên là bằng chứng nội dung chưa dịch, không phải chỉ suy từ thống kê. Một số đoạn đã dịch cũng hỏng: French `vip_expired_dialog_message` có `100% s.`, Arabic cùng key chứa `100% safe.`.

**Sửa:** duyệt các key giống English có allowlist thương hiệu; review ngữ nghĩa những chuỗi đã dịch, ưu tiên QR/VIP/engine. Không dùng “100% localization integrity” để khẳng định chất lượng dịch.

### R07 — P2: chữ tiếng Việt còn sót ở luồng camera và lỗi export

**Vị trí:** `MainActivity.kt:109,131,173`; `ui/editor/viewmodel/PostScanEditorViewModel.kt:398,424,436`; `ui/editor/PostScanEditorActivity.kt:497`.

Mở camera nhanh vẫn toast “Đang mở Camera Siêu Tốc...”; trường hợp không có trang/ảnh thẻ vẫn tiếng Việt. ViewModel trả lỗi export tiếng Việt và Activity hiển thị trực tiếp `result.message`; chỉ tiêu đề dialog đã resource hóa.

Ngoài ra `TextRecognitionHelper.kt:546,573` trả exception literal `No text recognized`; Home/Tools lấy `e.message` để toast nên UI Việt/French vẫn gặp câu tiếng Anh.

**Sửa:** resource hóa thông báo ở UI; lỗi ViewModel trả mã + tham số. Đừng đưa Activity Context vào ViewModel để dịch. Rà cả callback/error path, không chỉ XML.

### R08 — P2: mô tả “Theo hệ thống” lấy nhầm locale của ứng dụng

**Vị trí:** `utils/AppLanguageManager.kt:195–218`; `ui/dialogs/LanguageSelectionDialog.kt:46`.

Khi truyền context, `getSystemLanguageCode` đọc `context.resources.configuration`, vốn mang override của app. Máy English, app chọn Japanese: dòng mô tả “Theo hệ thống” sẽ lấy Japanese thay vì English.

Hơn nữa `resolveLocaleFromList` luôn trả locale được hỗ trợ hoặc `en`, nên `isSupported(resolved)` luôn true. Nhánh lấy mã máy không hỗ trợ và mô tả fallback không thể chạy như ý định.

**Sửa:** tách system locale list thật và resolved UI locale; tách kết quả “không match” khỏi fallback English. Test hệ thống en/app ja, hệ thống ru, hệ thống `[ru,fr]`.

### R09 — P2: migration locale gọi quá sớm rồi xóa legacy preference

**Vị trí:** `TScannerApplication.kt:18`; `utils/AppLanguageManager.kt:260–273`.

Đã bỏ áp lại locale ở mọi startup, nhưng migration vẫn gọi AppCompat API ngay trong `Application.onCreate`, rồi đánh dấu migrated/xóa key cũ không điều kiện.

Đã đối chiếu **source AppCompat 1.7.0 trong cache dependency hiện dùng**: `AppCompatDelegate.java:895–905` tìm LocaleManager từ active activity delegates; ở API 33+, setter `:782–791` không làm gì khi chưa có delegate, getter `:820–835` trả list rỗng. Cold start Application chưa tạo activity, nên không thể dùng getter này để kết luận framework chưa có lựa chọn; import legacy qua setter có thể không có tác dụng nhưng marker vẫn được lưu. AppCompat auto-storage có thể cứu trường hợp đã có bản lưu riêng, không bảo đảm case chỉ có legacy preference.

**Sửa:** API 33+ dùng framework LocaleManager với application context hoặc migration ở lifecycle phù hợp; API cũ phối hợp thời điểm restore AppCompat storage. Chỉ xóa legacy khi đã chuyển thành công. Test nâng cấp chỉ có legacy explicit, có framework override khác, System, process death. Chưa thực nghiệm migration trên thiết bị trong lượt này.

### R10 — P2: validator đang báo thành công vượt quá những gì nó kiểm tra

**Vị trí:** `scripts/audit_localization.py:98–128`, phần in báo cáo cuối file.

Script chỉ so tên nhóm plurals, không kiểm tra item/quantity/placeholder bên trong. Config chỉ được đọc và đếm, chưa đối chiếu với catalog/resources. `base_dupes` được đọc nhưng không đưa vào lỗi; không quét Kotlin và không nhận biết chuỗi untranslated. Kết quả thực tế: script exit 0 trong khi Android Lint có 84 lỗi MissingQuantity.

**Sửa:** bổ sung kiểm tra thực, dùng Lint làm gate bắt buộc và giữ phạm vi tuyên bố đúng với checker. Unit test validator bằng dữ liệu malformed/thiếu quantity/placeholders, không chỉ chạy trên bộ dữ liệu hiện có. Thay câu “100% LOCALIZATION INTEGRITY VERIFIED” bằng kết luận chỉ rõ phạm vi.

## Điểm đã sửa đúng

- 26 key editor thiếu trước đây đã có; mọi locale hiện có đủ 575 strings và 7 plurals theo kiểm kê key.
- Chọn System đã truyền empty locale list thay vì cố định locale thiết bị.
- Đã có normalize alias `in/tl/no` và canonical `zh-Hans/sr-Latn/nb`; danh sách tìm kiếm có tên English/locale UI và xử lý đ/Đ.
- Quyền lợi Drive `vip_perk_5` đã thống nhất với nguồn trong các ví dụ kiểm tra; không lặp lại kết luận 10GB/15GB của audit cũ. Key giá cũ vẫn còn một số bản dịch nhưng chưa có callsite hiện tại nên không kết luận lỗi tính tiền.
- Back drawable và chevron đã auto-mirror; phần lớn XML được chuyển sang resources.
- OCR empty state đã tách khỏi `extractedText`; copy/share/export có chặn chuỗi rỗng. Vấn đề header giả ở R05 nằm trước màn hình này.
- Alias resource pairs sr/sr-Latn, zh/zh-Hans, no/nb, tl/fil hiện đồng nhất nội dung string.

## Thứ tự sửa đề xuất

1. R01 + R10: làm Lint và validator phản ánh đúng lỗi.
2. R02 + R03: nối UI ngôn ngữ tài liệu và sửa capability/model routing, sửa tests đang khóa hành vi sai.
3. R04 + R05: giữ trạng thái lỗi đến UI/export, không xuất kết quả giả hoặc bỏ trang im lặng.
4. R08 + R09: system locale và migration; thêm instrumentation lifecycle.
5. R06 + R07: review nội dung dịch và các nhánh lỗi còn hardcode.

Chưa coi bản sửa đạt nghiệm thu chỉ vì 68 unit tests xanh: bộ test hiện tại chưa kiểm tra UI chọn ngôn ngữ OCR, quantity thực tế của Arabic, migration Android hoặc lỗi model thực.
