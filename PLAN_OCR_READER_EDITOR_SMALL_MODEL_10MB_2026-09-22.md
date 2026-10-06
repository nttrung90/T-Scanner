# Kế hoạch giao mô hình nhỏ: OCR reader + editor + DOCX/XLSX, trần 10 MB

Ngày: 22/09/2026. Tài liệu này là kế hoạch thực hiện chính, bổ sung và thay ngân sách cũ trong PLAN_OCR_DOCUMENT_READER_2026-09-22.md.

## 1. Phạm vi và định nghĩa hoàn thành

Người dùng quét → Trích xuất văn bản → tự mở trình đọc nội bộ → xem bản quét/chữ/bảng → sửa chữ hoặc bảng → lưu nháp → xuất DOCX/XLSX. Tính năng mới hoạt động trên thiết bị; trạng thái model OCR chưa tải xử lý theo cơ chế hiện có.

Bản quét giữ hình thức bằng ảnh nguồn và lớp OCR để chọn/copy/tìm. Tab Văn bản/Bảng chứa dữ liệu sửa được. Sửa chữ không xóa/vẽ thay chữ trong ảnh; font/ngắt dòng của bản tái tạo không được cam kết giống tuyệt đối. Nội dung đã sửa mất tương ứng word box phải bỏ highlight cấp từ hoặc chuyển sang anchor đoạn với nhãn phù hợp, không vẽ sai tọa độ.

Bản đầu có sửa text, undo/redo, đậm/nghiêng, cỡ chữ, căn lề; bảng có sửa ô, hàng/cột, gộp/tách, độ rộng và loại text/number. Không bao gồm editor Office tổng quát, đọc mọi file bên ngoài, DOC/XLS cũ, công thức tính toán, macro, biểu đồ, pivot hay sửa trực tiếp ảnh quét. Preview nội bộ đọc mô hình tài liệu, không tuyên bố là parser DOCX/XLSX. Đọc file Office bên ngoài là gói tương lai riêng, chưa được tính là chức năng đã hoàn thành.

Đây là mục tiêu kỹ thuật có gate nghiệm thu, không phải cam kết đã đạt dung lượng/độ chính xác. Không chạy subagent hay sửa code trong lượt lập kế hoạch.

## 2. Trần dung lượng và baseline

- Trần cứng toàn tính năng: tăng **không quá 10.000.000 byte (10 MB thập phân)** so với bản ứng dụng hiện tại.
- Baseline lấy ở S00 từ cả working tree hiện tại, kể cả thay đổi chưa commit. Ghi HEAD + manifest/hash file liên quan + cấu hình + hash artifact. Không reset/stash/xóa thay đổi người dùng.
- Đo cùng build release, ABI, signing, shrink/minify và dependency. APK phổ thông so với APK phổ thông cùng hai ABI; tải theo device spec so cùng device spec khi phát hành AAB. Dung lượng file AAB không đại diện dung lượng tải thiết bị.
- Ràng buộc áp dụng APK phân phối và lượng tải của cấu hình Play được hỗ trợ. Module/model mới bắt buộc tải để dùng tính năng phải báo và tính vào tổng tải của tính năng; không đẩy sang tải lần đầu để lách trần. Data tài liệu người dùng không thuộc kích thước app, nhưng phải đo riêng cache/cài đặt/RAM.
- Với build không tái lập tuyệt đối, lưu các biến ảnh hưởng và đối chiếu artifact breakdown; không lấy thay đổi signing/build config làm công tính năng.
- Cảnh báo ở **8 MB**, còn 2 MB dự phòng. Vượt 10 MB: không nghiệm thu, phân tích dependency/asset để giảm; không tự nâng trần.
- R8, loại ABI hoặc di chuyển model cũ ra ngoài là dự án tối ưu riêng. Nếu làm sau này phải rebuild cả baseline và candidate dưới cùng cấu hình, vẫn giữ số so với baseline người dùng đã chốt.

| Phần | Ngân sách tăng đề xuất |
|---|---:|
| Schema, geometry, repository và viewer | 2,0 MB |
| Bộ sửa chữ, định dạng và history | 1,5 MB |
| Phân tích bố cục, nhận bảng và sửa bảng | 2,0 MB |
| DOCX/XLSX writer và nối xuất | 1,5 MB |
| Resources, tích hợp và phần chung | 1,0 MB |
| Dự phòng | 2,0 MB |
| Tổng tối đa | 10,0 MB |

Đây là phân bổ quản trị, chưa phải ước lượng thư viện đo được. Dependency dùng chung chỉ tính một lần; luôn lấy delta artifact tổng làm kết luận. Kiểm tra mốc S00, S10, S14, S18, S21 và S23; gói thêm dependency phải đo ngay. Không tự thêm SDK/model/font pack lớn.

## 3. Bằng chứng code đã kiểm tra để giao việc

Đường dẫn rút gọn dưới đây tương đối với app/src/main/java/com/tscanner/app/, trừ khi ghi app/build.gradle hoặc res/. File mới là vị trí đề xuất, chưa tồn tại; model triển khai phải kiểm tra AGENTS.md và tên thật trước khi sửa. Số dòng là snapshot nghiên cứu, có thể dịch sau mỗi gói.

| Mã | Bằng chứng | Tình huống cần thay đổi / mức ưu tiên |
|---|---|---|
| B0 | app/build.gradle:18 hai ABI; :25/:29 minifyEnabled false | Chưa có baseline tính năng; đo trước khi thêm thư viện — gate bắt buộc |
| B1 | utils/OcrModels.kt:108–113, :138, :439–449 | Result giữ text/fullText và metadata, thiếu layout/page content đầy đủ — nền tảng |
| B2 | paddleocr/PaddleOcrEngine.kt:253 và :337 join textLines | Có geometry nội bộ nhưng kết quả đầu ra chỉ chuỗi; không thể chọn chữ đúng chỗ — nền tảng |
| B3 | utils/TesseractOcrHelper.kt:92, :100, :182 getUTF8Text | Adapter chưa trả hình học; phải xác minh API của dependency đang dùng — nền tảng |
| B4 | ui/ocr/OcrResultActivity.kt:149–151 text view; :214 doc, :256 csv; utils/PdfConverterHelper.kt:352, :387 | OCR xong hiện chữ thuần; Word là HTML, Excel CSV, chưa có editor/bảng thật — chức năng chính |
| B5 | utils/SafeFileWriter.kt:104 writeSafely, :151 commitAtomic | Có helper cần đọc/test lại trước tái sử dụng; không mặc định chứng minh crash safety — an toàn dữ liệu |
| B6 | OcrResultActivity.start callers tại HomeFragment, FilesFragment, PdfViewerActivity; rerun UI tại :424/:479 | Cần mọi cửa vào cùng contract và giữ bản sửa khi OCR lại — tích hợp |

Đây là bằng chứng đọc code và khoảng trống tính năng, không phải tất cả đều là lỗi runtime đã tái hiện. Chưa đo build/APK hay chạy thiết bị trong lượt lập kế hoạch. Không kế thừa số test pass từ báo cáo cũ.

## 4. Quy tắc cho mô hình nhỏ

1. Mỗi lượt chỉ nhận một ID. Đọc mục chung và gói đó, không triển khai ID kế tiếp.
2. Recheck code và thay đổi đang có trước khi sửa; không ghi đè công việc người dùng. Chỉ mở file ngoài scope khi cần đọc; thay đổi ngoài scope phải có lý do và giới hạn rõ trong báo cáo.
3. Sau khi người dùng giao triển khai ID cụ thể, thực hiện gói đó, không hỏi lại các lựa chọn thường quy đã chốt trong kế hoạch.
4. Viết test vào logic production; không tự dựng fake chứa bản sao thuật toán rồi coi đó là bằng chứng.
5. Không cập nhật OCR engine/model hay thêm thư viện chỉ để thuận tiện. Nếu contract/dependency không phù hợp, ghi trở ngại, đề xuất chỉnh gói và dừng phần phụ thuộc.
6. Mỗi gói xuất báo cáo docs/ocr-reader/handoffs/Sxx.md: file đổi, contract, test và kết quả thực, size delta nếu cần, giới hạn, prerequisites cho gói tiếp. Không ghi PASS khi NOT RUN.
7. Full bitmap và IO không chạy trên Main; cancellation phải được truyền đúng; không làm mất tài liệu cũ nếu ghi/xuất lỗi.
8. Dùng strings resource và giữ chính sách watermark hiện hành. Không âm thầm đưa dữ liệu lên cloud.

Các gói chạy theo thứ tự số để giảm xung đột. Bảng phụ thuộc chỉ chỉ ra prerequisite kỹ thuật; không phải yêu cầu chạy song song.

## 5. Các gói thực hiện
### S00 — Chốt baseline và phép đo

- **Phụ thuộc:** Không.
- **Bằng chứng/điểm xuất phát:** B0 trong mục 3.
- **File/phạm vi:** app/build.gradle, settings.gradle (đọc); mới scripts/measure_ocr_size.ps1 và docs/ocr-reader/baseline.md.
- **Việc làm:** Ghi SHA HEAD, diff/hash cả file untracked liên quan, phiên bản toolchain và dependency; build baseline từ trạng thái làm việc hiện tại, không chỉ từ HEAD. Lưu artifact/hash ra thư mục build, không commit binary. Script xuất bytes APK, device download nếu có bundletool và delta; giữ nguyên cấu hình release hiện tại.
- **Kiểm thử và nghiệm thu:** Chạy script với artifact giống nhau → delta 0; artifact thiếu → thất bại rõ. Có kích thước baseline thực, hash và cấu hình tái tạo; không tự bật R8 để làm đẹp số đo. Không build được thì báo gate chưa đạt, không bịa baseline.
- **Điểm dừng:** chỉ hoàn tất gói S00, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S00 và handoff của các prerequisite. Tôi giao triển khai duy nhất S00 — Chốt baseline và phép đo. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S00.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S01 — Bộ mẫu và hợp đồng dữ liệu

- **Phụ thuộc:** S00.
- **Bằng chứng/điểm xuất phát:** B1–B4 trong mục 3.
- **File/phạm vi:** mới docs/ocr-reader/contract.md và fixtures OCR trong app/src/test/resources/ocr_reader/.
- **Việc làm:** Chọn 30–50 trang có quyền sử dụng; nhóm Việt/Anh, 2 cột, bảng đơn/ô gộp, xoay, mờ, trang trắng. Ghi ground truth và 5–10 ca nhỏ cho mỗi thay đổi. Định nghĩa ID ổn định, text gốc/text sửa, revision, page status, tọa độ và giới hạn bảng. Dữ liệu ảnh lớn để ngoài APK.
- **Kiểm thử và nghiệm thu:** Có quy tắc đo CER, cấu trúc bảng và ghép đúng trang; fixture tổng hợp không được thay thế toàn bộ ảnh thực. Nếu thiếu corpus thật thì ghi rõ phần nghiệm thu chờ dữ liệu.
- **Điểm dừng:** chỉ hoàn tất gói S01, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S01 và handoff của các prerequisite. Tôi giao triển khai duy nhất S01 — Bộ mẫu và hợp đồng dữ liệu. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S01.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S02 — Schema OCR có bố cục

- **Phụ thuộc:** S01.
- **Bằng chứng/điểm xuất phát:** B1 trong mục 3.
- **File/phạm vi:** utils/OcrModels.kt; mới ocr/model/OcrDocument.kt; tests tương ứng.
- **Việc làm:** Thêm model thuần Kotlin cho document/page/block/line/token và table/cell; confidence nullable, source revision, polygon, transform, engine, language, trạng thái từng trang. Giữ caller chuỗi cũ tương thích bằng giá trị mặc định/adapter. Chưa làm UI.
- **Kiểm thử và nghiệm thu:** Roundtrip dữ liệu Unicode; validate polygon, page order, ô gộp không chồng lấn, ID duy nhất. Model chứa trang trống/lỗi mà không mất số trang; test gọi contract production.
- **Điểm dừng:** chỉ hoàn tất gói S02, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S02 và handoff của các prerequisite. Tôi giao triển khai duy nhất S02 — Schema OCR có bố cục. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S02.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S03 — Lưu hồ sơ OCR an toàn

- **Phụ thuộc:** S02.
- **Bằng chứng/điểm xuất phát:** B5 trong mục 3.
- **File/phạm vi:** mới ocr/data/OcrDocumentRepository.kt; utils/SafeFileWriter.kt (đọc, chỉ sửa nếu có bằng chứng cần); repository tests.
- **Việc làm:** Persist schema/revision bằng ghi nguyên tử, lock theo document, xác nhận expectedRevision. Giữ quyền sở hữu ảnh theo document và cơ chế cleanup; không nhét bitmap/text lớn vào Bundle. Xác định recovery, corruption và version migration.
- **Kiểm thử và nghiệm thu:** Inject lỗi trước/sau commit; bản cũ còn nguyên khi lỗi; ghi đồng thời không ghi đè revision mới; mở lại từ process mới đọc đúng. Không xóa ảnh còn được tham chiếu. Chỉ lưu thành công mới báo đã lưu.
- **Điểm dừng:** chỉ hoàn tất gói S03, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S03 và handoff của các prerequisite. Tôi giao triển khai duy nhất S03 — Lưu hồ sơ OCR an toàn. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S03.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S04 — Chuẩn hóa phép biến đổi tọa độ

- **Phụ thuộc:** S02.
- **Bằng chứng/điểm xuất phát:** B2 trong mục 3.
- **File/phạm vi:** mới ocr/geometry/OcrCoordinateMapper.kt; ui/editor/model/PageGeometry.kt và utils/ImageMemoryBudgetCalculator.kt (đọc).
- **Việc làm:** Chuẩn hóa tọa độ từ bitmap OCR về ảnh trang đã crop/xoay; lưu kích thước và ma trận rõ chiều. Không giả định kích thước ảnh OCR bằng ảnh hiển thị.
- **Kiểm thử và nghiệm thu:** Fixture resize không đều, 90/180/270 độ, crop, điểm góc; roundtrip sai số ≤1 pixel do làm tròn. Kích thước 0/ma trận suy biến trả lỗi rõ.
- **Điểm dừng:** chỉ hoàn tất gói S04, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S04 và handoff của các prerequisite. Tôi giao triển khai duy nhất S04 — Chuẩn hóa phép biến đổi tọa độ. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S04.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S05 — Adapter ML Kit giữ hình học

- **Phụ thuộc:** S02, S04.
- **Bằng chứng/điểm xuất phát:** B1–B2 trong mục 3.
- **File/phạm vi:** utils/TextRecognitionHelper.kt; mới ocr/engine/MlKitLayoutMapper.kt; tests mapper.
- **Việc làm:** Giữ block/line/element và text engine tương ứng; gắn engine/language/source revision. Không thay cơ chế chọn ngôn ngữ hay thêm model.
- **Kiểm thử và nghiệm thu:** Kết quả có đủ text/polygon, thứ tự đọc được giữ làm đầu vào; text rỗng hợp lệ; cancellation không bị đổi thành thành công. Test mapper bằng fixture và chạy ít nhất một mẫu engine thật khi có thiết bị.
- **Điểm dừng:** chỉ hoàn tất gói S05, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S05 và handoff của các prerequisite. Tôi giao triển khai duy nhất S05 — Adapter ML Kit giữ hình học. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S05.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S06 — Adapter Paddle giữ polygon

- **Phụ thuộc:** S02, S04.
- **Bằng chứng/điểm xuất phát:** B2 trong mục 3.
- **File/phạm vi:** paddleocr/PaddleOcrEngine.kt; PaddleOcrEngineTest.kt.
- **Việc làm:** Giữ polygon đi cùng từng dòng qua crop/recognize/sort, map về ảnh chuẩn; cập nhật cả seam kiểm thử để không lệch production. Chưa tạo word boxes nếu engine chỉ có line boxes.
- **Kiểm thử và nghiệm thu:** Dòng đảo thứ tự, crop thất bại, ảnh resize, cancellation có regression tests. Text và polygon không lệch cặp; engine thật vẫn nhận dạng trên mẫu trước/sau.
- **Điểm dừng:** chỉ hoàn tất gói S06, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S06 và handoff của các prerequisite. Tôi giao triển khai duy nhất S06 — Adapter Paddle giữ polygon. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S06.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S07 — Adapter Tesseract có mức hỗ trợ rõ

- **Phụ thuộc:** S02, S04.
- **Bằng chứng/điểm xuất phát:** B3 trong mục 3.
- **File/phạm vi:** utils/TesseractOcrHelper.kt; mới mapper nếu cần; TesseractOcrHelperTest.kt.
- **Việc làm:** Kiểm tra API iterator/hOCR đúng phiên bản đang cài; lấy line/word geometry nếu hỗ trợ đáng tin cậy. Quản lý tài nguyên iterator; không thêm engine hay parser lớn. Nếu chưa khả thi, trả geometryUnavailable kèm text và ghi giới hạn.
- **Kiểm thử và nghiệm thu:** Unicode Việt, box ngoài biên, iterator đóng trên success/error/cancel. Chỉ đánh dấu hỗ trợ selection khi có box thật. Fallback text-only hoạt động; không coi fallback này là đạt mọi tiêu chí geometry.
- **Điểm dừng:** chỉ hoàn tất gói S07, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S07 và handoff của các prerequisite. Tôi giao triển khai duy nhất S07 — Adapter Tesseract có mức hỗ trợ rõ. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S07.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S08 — Ghép nhiều trang và chuyển giao bằng ID

- **Phụ thuộc:** S03, S05, S06, S07.
- **Bằng chứng/điểm xuất phát:** B1, B5, B6 trong mục 3.
- **File/phạm vi:** utils/OcrModels.kt aggregator; utils/TextRecognitionHelper.kt; ui/ocr/OcrResultActivity.kt start methods; caller HomeFragment.kt, FilesFragment.kt, PdfViewerActivity.kt.
- **Việc làm:** Tổng hợp document giữ mọi page ID/status, commit rồi mở màn hình bằng ID. Rà rg mọi caller Trích xuất văn bản, gồm luồng sau quét thực tế. Nhận diện lại tạo revision mới; có edit thì hỏi giữ sửa hoặc thay OCR, không ghi đè âm thầm.
- **Kiểm thử và nghiệm thu:** Ca 3 trang với trang giữa trắng/lỗi giữ đúng thứ tự; mở màn hình chỉ sau commit; kết quả worker cũ không đè revision mới; đường gọi cũ vẫn hoạt động qua adapter.
- **Điểm dừng:** chỉ hoàn tất gói S08, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S08 và handoff của các prerequisite. Tôi giao triển khai duy nhất S08 — Ghép nhiều trang và chuyển giao bằng ID. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S08.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S09 — Trình xem ảnh trang nhẹ

- **Phụ thuộc:** S08.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** mới ui/ocr/reader/OcrPageView.kt và OcrReaderViewModel.kt; ui/ocr/OcrResultActivity.kt; res/layout/activity_ocr_result.xml.
- **Việc làm:** Tab Bản quét mặc định, paging/zoom/pan, tải ảnh theo trang/ROI và cache có giới hạn; state bằng ID/page/zoom nhỏ. Tái dùng thư viện ảnh có sẵn; giải phóng bitmap đúng vòng đời.
- **Kiểm thử và nghiệm thu:** So full-resolution/ROI; không crop mất nội dung; 1/10/50 trang không giữ tất cả bitmap. Xoay máy và quay lại không mất vị trí; source thiếu có thông báo. Đo kích thước ở mốc viewer.
- **Điểm dừng:** chỉ hoàn tất gói S09, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S09 và handoff của các prerequisite. Tôi giao triển khai duy nhất S09 — Trình xem ảnh trang nhẹ. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S09.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S10 — Chọn/copy/tìm kiếm trên bản quét

- **Phụ thuộc:** S09.
- **Bằng chứng/điểm xuất phát:** B1, B2 trong mục 3.
- **File/phạm vi:** ui/ocr/reader/OcrPageView.kt; mới OcrSelectionController.kt; tests.
- **Việc làm:** Hit-test cùng ma trận với ảnh, chọn dòng/vùng và từ khi có box; tìm kiếm Unicode, tô sáng đúng trang. Expose accessibility text và thứ tự đọc; fallback text-only rõ ràng.
- **Kiểm thử và nghiệm thu:** Zoom/pan/xoay không lệch highlight; copy không đảo cột; engine line-only không tạo word boxes giả. Kết quả đã sửa đổi độ dài không tái dùng offset/box từ cũ để highlight sai.
- **Điểm dừng:** chỉ hoàn tất gói S10, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S10 và handoff của các prerequisite. Tôi giao triển khai duy nhất S10 — Chọn/copy/tìm kiếm trên bản quét. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S10.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S11 — Bộ lệnh sửa và undo/redo

- **Phụ thuộc:** S02, S03.
- **Bằng chứng/điểm xuất phát:** B1, B5 trong mục 3.
- **File/phạm vi:** mới ocr/edit/OcrEditCommand.kt, OcrEditHistory.kt, OcrEditReducer.kt; tests.
- **Việc làm:** Một nguồn dữ liệu chuẩn cho text/table; immutable source OCR riêng với edited content. Lệnh replace/insert/delete và history có giới hạn; dirty revision, snapshot export, không làm thay ảnh quét.
- **Kiểm thử và nghiệm thu:** Edit → undo → redo hoàn nguyên chính xác Unicode và ID; sửa sau undo xóa redo đúng; text bảng không có hai bản mâu thuẫn; giới hạn history không làm mất bản lưu. Chưa xây UI.
- **Điểm dừng:** chỉ hoàn tất gói S11, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S11 và handoff của các prerequisite. Tôi giao triển khai duy nhất S11 — Bộ lệnh sửa và undo/redo. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S11.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S12 — Giao diện sửa văn bản

- **Phụ thuộc:** S10, S11.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** mới ui/ocr/editor/OcrTextEditorFragment.kt và layout; OcrReaderViewModel.kt.
- **Việc làm:** Tab Văn bản dùng widget Android: sửa chữ, xuống dòng, chọn/copy/paste, undo/redo. Đồng bộ qua edit commands, hỗ trợ IME tiếng Việt; sửa đoạn giữ source anchor, đoạn mới không có geometry nguồn.
- **Kiểm thử và nghiệm thu:** Nhập dấu qua composing text không nhân chữ; paste nhiều dòng, undo và chuyển tab không mất sửa; export snapshot lấy edited text. Bản quét vẫn hiển thị ảnh gốc với trạng thái nội dung đã sửa.
- **Điểm dừng:** chỉ hoàn tất gói S12, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S12 và handoff của các prerequisite. Tôi giao triển khai duy nhất S12 — Giao diện sửa văn bản. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S12.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S13 — Định dạng đoạn giới hạn

- **Phụ thuộc:** S12.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** ocr/model/OcrDocument.kt phần style đã định nghĩa; ocr/edit commands; UI editor.
- **Việc làm:** Thêm đậm/nghiêng, cỡ chữ và căn trái/giữa/phải; format runs chuẩn hóa, chỉ dùng font hệ thống. Không triển khai kéo thả bố cục hay text box tự do.
- **Kiểm thử và nghiệm thu:** Selection nhiều runs, Unicode, undo style và roundtrip repository giữ đúng; viewer Văn bản thể hiện style; source image không bị sửa. Kiểm tra size trước khi thêm toolbar/assets.
- **Điểm dừng:** chỉ hoàn tất gói S13, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S13 và handoff của các prerequisite. Tôi giao triển khai duy nhất S13 — Định dạng đoạn giới hạn. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S13.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S14 — Autosave và phục hồi bản sửa

- **Phụ thuộc:** S12, S13.
- **Bằng chứng/điểm xuất phát:** B5 trong mục 3.
- **File/phạm vi:** OcrReaderViewModel.kt; OcrDocumentRepository.kt; lifecycle/instrumentation tests.
- **Việc làm:** Debounce ghi ở IO, flush khi lưu/rời màn hình; phân biệt đang lưu/đã lưu/lỗi. Revision guard khi load/re-OCR/autosave chạy đồng thời. Persist bản sửa đã commit, history có thể giới hạn trong phiên và phải ghi rõ.
- **Kiểm thử và nghiệm thu:** Kill process sau commit phục hồi đúng; kill trước commit chỉ được mất phần chưa xác nhận đã lưu; lỗi disk full/Back/rotation không báo lưu giả. Export không đọc bản nháp cũ khi còn dirty.
- **Điểm dừng:** chỉ hoàn tất gói S14, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S14 và handoff của các prerequisite. Tôi giao triển khai duy nhất S14 — Autosave và phục hồi bản sửa. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S14.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S15 — Phân tích đoạn và cột

- **Phụ thuộc:** S08.
- **Bằng chứng/điểm xuất phát:** B1, B2 trong mục 3.
- **File/phạm vi:** mới ocr/layout/DocumentLayoutAnalyzer.kt; fixtures/tests.
- **Việc làm:** Heuristic đoạn/tiêu đề/cột từ geometry hiện có, giữ provenance và thứ tự đọc; chạy phân tích trên bản OCR gốc, không tự ghi đè edits. Không thêm model layout.
- **Kiểm thử và nghiệm thu:** Tài liệu 1/2 cột đọc đúng thứ tự theo ground truth; trang mờ không tự tạo chữ. Có báo cáo phạm vi đạt, không chỉ test tự tạo theo thuật toán.
- **Điểm dừng:** chỉ hoàn tất gói S15, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S15 và handoff của các prerequisite. Tôi giao triển khai duy nhất S15 — Phân tích đoạn và cột. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S15.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S16 — Nhận bảng cơ bản

- **Phụ thuộc:** S15.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** mới ocr/table/TableStructureAnalyzer.kt; fixtures/tests.
- **Việc làm:** Ưu tiên bảng có đường kẻ và lưới rõ; kết hợp geometry chữ, hàng/cột, cell spans, ngưỡng không chắc chắn. Không coi tab/khoảng trắng là đủ để xác nhận mọi bảng. Bảng không kẻ khó để manual.
- **Kiểm thử và nghiệm thu:** Chấm cấu trúc ô trên ground truth riêng; đoạn nhiều cột không bị nhận nhầm thành bảng. Ô rỗng/ô gộp được giữ; kết quả không chắc chắn cho sửa hoặc tạo bảng thủ công.
- **Điểm dừng:** chỉ hoàn tất gói S16, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S16 và handoff của các prerequisite. Tôi giao triển khai duy nhất S16 — Nhận bảng cơ bản. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S16.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S17 — Sửa nội dung ô

- **Phụ thuộc:** S11, S14, S16.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** mới ui/ocr/editor/OcrTableEditorFragment.kt, adapter/layout; commands table.
- **Việc làm:** Hiển thị bảng tải theo vùng/hàng, sửa text ô, loại text/number, undo/redo; cho tạo bảng thủ công khi detector không đủ. Không suy công thức từ chuỗi OCR; text là mặc định.
- **Kiểm thử và nghiệm thu:** 00123 giữ số 0; dấu phẩy/chấm, ngày, Unicode không bị chuyển sai; sửa ô đồng bộ preview/export/autosave; bảng lớn không tạo toàn bộ View mỗi ô cùng lúc.
- **Điểm dừng:** chỉ hoàn tất gói S17, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S17 và handoff của các prerequisite. Tôi giao triển khai duy nhất S17 — Sửa nội dung ô. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S17.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S18 — Sửa cấu trúc bảng

- **Phụ thuộc:** S17.
- **Bằng chứng/điểm xuất phát:** B4 trong mục 3.
- **File/phạm vi:** table editor và edit commands.
- **Việc làm:** Thêm/xóa hàng/cột, gộp/tách ô hình chữ nhật, chỉnh độ rộng/căn lề cơ bản. Chốt cách giữ nội dung khi gộp và xác nhận thao tác xóa dữ liệu; undo/redo nguyên tử.
- **Kiểm thử và nghiệm thu:** Gộp ô có nội dung không mất âm thầm; insert/delete xuyên vùng merge không tạo spans sai; undo khôi phục cả giá trị và cấu trúc; giới hạn kích thước được báo rõ.
- **Điểm dừng:** chỉ hoàn tất gói S18, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S18 và handoff của các prerequisite. Tôi giao triển khai duy nhất S18 — Sửa cấu trúc bảng. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S18.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S19 — Writer DOCX giới hạn

- **Phụ thuộc:** S13, S14, S15, S18.
- **Bằng chứng/điểm xuất phát:** B4, B5 trong mục 3.
- **File/phạm vi:** mới ocr/export/DocxWriter.kt; fixtures và tests writer.
- **Việc làm:** ZIP/XML OOXML cho paragraphs/runs, page breaks, bảng và ảnh trong phạm vi; escape XML, units, relationships, style, watermark hiện có. Ghi temp rồi commit an toàn; snapshot revision cố định. Chưa đổi UI xuất.
- **Kiểm thử và nghiệm thu:** Parse ZIP/XML và đối chiếu với snapshot; mở file trong Word và LibreOffice không repair. Ảnh trang chèn phải gắn nhãn ảnh, không gọi là chữ sửa được. IO/cancel không phá file cũ.
- **Điểm dừng:** chỉ hoàn tất gói S19, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S19 và handoff của các prerequisite. Tôi giao triển khai duy nhất S19 — Writer DOCX giới hạn. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S19.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S20 — Writer XLSX giới hạn

- **Phụ thuộc:** S18, S14.
- **Bằng chứng/điểm xuất phát:** B4, B5 trong mục 3.
- **File/phạm vi:** mới ocr/export/XlsxWriter.kt; fixtures/tests.
- **Việc làm:** OOXML workbook/worksheet/styles; inline/shared strings nhất quán, merges và column widths cơ bản. Text OCR mặc định là string, chỉ số khi người dùng xác nhận; không tạo formula nodes từ text.
- **Kiểm thử và nghiệm thu:** Mở Excel và LibreOffice không repair; giữ 00123, Unicode, merged cells, ngày/số theo quy tắc; =SUM(...), +, -, @ không tự chạy thành formula; giữ revision snapshot.
- **Điểm dừng:** chỉ hoàn tất gói S20, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S20 và handoff của các prerequisite. Tôi giao triển khai duy nhất S20 — Writer XLSX giới hạn. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S20.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S21 — Nối xuất, lưu và chia sẻ

- **Phụ thuộc:** S19, S20.
- **Bằng chứng/điểm xuất phát:** B4, B6 trong mục 3.
- **File/phạm vi:** ui/ocr/OcrResultActivity.kt; ui/dialogs/ExportDocDialog.kt; utils/PdfConverterHelper.kt; resources.
- **Việc làm:** Nút Word/Excel dùng DOCX/XLSX thật và MIME đúng, lấy snapshot bản sửa mới nhất; flush thành công hoặc snapshot committed riêng trước xuất. Giữ CSV/text như định dạng có tên rõ nếu còn dùng. Chỉ báo thành công sau lưu đích/Downloads thành công; bảo toàn chính sách watermark.
- **Kiểm thử và nghiệm thu:** Luồng sửa → xuất → mở ngoài chứa dữ liệu mới; cancel/thiếu dung lượng/lỗi Downloads không báo thành công giả; FileProvider/share hoạt động; không phụ thuộc app Office để xem kết quả nội bộ.
- **Điểm dừng:** chỉ hoàn tất gói S21, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S21 và handoff của các prerequisite. Tôi giao triển khai duy nhất S21 — Nối xuất, lưu và chia sẻ. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S21.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S22 — Ngôn ngữ và kiểm thử hành trình

- **Phụ thuộc:** S21.
- **Bằng chứng/điểm xuất phát:** B6 trong mục 3.
- **File/phạm vi:** res/values*/strings.xml; OcrUserFlowIntegrationTest.kt; mới androidTest OCR reader/editor.
- **Việc làm:** Rà mọi chuỗi mới, ngôn ngữ UI tách OCR; kiểm hành trình quét → OCR → xem → sửa → lưu → khôi phục → DOCX/XLSX. Không tái dịch phần ngoài phạm vi.
- **Kiểm thử và nghiệm thu:** Việt/Anh và fallback các locale hiện có không crash/mất placeholder; trang trắng/lỗi, thiếu model, engine fallback và đổi ngôn ngữ không mất bản sửa. Tests chạy production flow, không chỉ fake lặp logic.
- **Điểm dừng:** chỉ hoàn tất gói S22, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S22 và handoff của các prerequisite. Tôi giao triển khai duy nhất S22 — Ngôn ngữ và kiểm thử hành trình. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S22.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

### S23 — Đo cuối và quyết định nghiệm thu

- **Phụ thuộc:** S22.
- **Bằng chứng/điểm xuất phát:** B0–B6 trong mục 3.
- **File/phạm vi:** scripts/measure_ocr_size.ps1; mới docs/ocr-reader/acceptance.md; không sửa code để che kết quả.
- **Việc làm:** Build baseline/candidate tương đương; đo delta bằng bytes, dependency/asset/native breakdown, RAM/cache và tốc độ. Chạy full JVM/build/lint cùng release, kiểm thử thiết bị 3–4 GB ARM và file thực. Ghi từng gate PASS/FAIL/NOT RUN.
- **Kiểm thử và nghiệm thu:** Tổng tăng ≤10.000.000 bytes cho APK cùng ABI và tải theo device nếu phát hành Play; tính cả module/model mới cần cho tính năng. Không có bằng chứng thiết bị/tương thích thì gate đó chưa đạt. Vượt trần phải giảm dependency/phạm vi, không tự nâng ngân sách.
- **Điểm dừng:** chỉ hoàn tất gói S23, ghi handoff; không tự làm gói sau.

**Prompt giao việc:**

> Đọc E:\DU AN AI\T-Scanner\PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md, mục 1–4, gói S23 và handoff của các prerequisite. Tôi giao triển khai duy nhất S23 — Đo cuối và quyết định nghiệm thu. Kiểm tra code hiện tại, giữ mọi thay đổi có sẵn; chỉ sửa phạm vi gói, chạy kiểm thử thích hợp và báo kết quả thực. Tuân thủ trần tổng tăng 10.000.000 byte, không thêm SDK/model ngoài phạm vi. Ghi docs/ocr-reader/handoffs/S23.md rồi dừng; nếu thiếu prerequisite thì báo cụ thể, không tự mở rộng công việc.

## 6. Kiểm thử và gate cuối

Mỗi gói chạy test lớp liên quan bằng Gradle --tests nếu có, và kiểm build khi đổi contract/UI. Không ép viết test thuần để kiểm một thay đổi nhãn nhỏ; kiểm resource/build phù hợp. Các mốc S08/S14/S21 chạy hồi quy OCR và assemble/lint; S23 chạy:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Đo release bằng tác vụ build phù hợp cấu hình dự án đã xác minh ở S00; lưu câu lệnh chính xác vào baseline. Nếu thiếu dependency/cache thì báo rõ, không coi đó là lỗi app và không đổi GRADLE_USER_HOME sang thư mục không có quyền. Không dùng debug size thay release size để nghiệm thu.

| Gate | Điều kiện |
|---|---|
| Dung lượng | Delta tổng ≤10.000.000 byte, baseline/candidate so sánh được; báo riêng modules/models/cache |
| Chức năng | Tất cả cửa vào OCR tự mở reader; sửa/lưu/khôi phục/xuất đúng revision |
| Dữ liệu | Không mất/trùng/đảo trang; autosave và export lỗi không phá bản cũ; không formula injection |
| Hình học | Phép transform fixture sai số ≤1 pixel; box OCR thực kiểm riêng; ảnh hiển thị đúng cả ROI |
| Nội dung | Mục tiêu CER ≤2% trên tập in rõ Việt/Anh; ≥95% cấu trúc ô đúng trên tập bảng đơn giản đã chốt S01; báo từng nhóm khó riêng |
| Hiệu năng | Sau OCR, trang đầu ≤1 giây p95 trên thiết bị chuẩn; 1/10/50 trang không OOM/ANR; ghi RAM peak và giới hạn cache thực ở S00/S09 |
| Tương thích | DOCX mở Word và LibreOffice, XLSX mở Excel và LibreOffice không repair; đối chiếu text, merges, style, số 0 đầu |
| Lifecycle | Xoay, Back, process death, disk full, cancel, re-OCR khi có sửa; thiết bị thật là gate độc lập |
| Build | Test liên quan + full JVM/build/lint; phân biệt warning có sẵn và lỗi mới |

Không có thiết bị hoặc Office để xác minh: ghi NOT RUN và danh sách thao tác nghiệm thu, không dùng XML valid làm bằng chứng file hiển thị đúng. Không giảm ngưỡng CER/bảng hay tăng ngân sách để tự đánh dấu hoàn tất. Nếu engine/corpus chưa đạt, báo thiếu hụt và đề xuất gói cải thiện độc lập.

## 7. Các điểm nghiệm thu trung gian

- **S00–S08:** dữ liệu OCR có trang/tọa độ và lưu bền vững; chưa tuyên bố có reader/editor hoàn chỉnh.
- **S09–S10:** xem giống bản quét, chọn/copy/tìm; đo size lần đầu cho viewer.
- **S11–S14:** sửa chữ/định dạng, undo/redo, autosave/phục hồi.
- **S15–S18:** tái tạo bố cục và sửa bảng cơ bản; đo size trước thêm exporters.
- **S19–S21:** DOCX/XLSX thật chứa bản sửa mới nhất.
- **S22–S23:** nghiệm thu hành trình, ngôn ngữ, dung lượng, thiết bị và file thực.

Nếu S16 không đạt bảng tự động, vẫn giữ sửa/tạo bảng thủ công nhưng ghi nhận nhận bảng tự động chưa đạt, không quảng bá đã hoàn thiện tính năng đó. Nếu S07 chỉ text-only thì người dùng engine đó vẫn đọc ảnh/text, nhưng chọn chữ trên ảnh chưa đạt; ghi rõ trong bảng khả năng.

## 8. Cách dùng kế hoạch

Gửi prompt của S00 cho mô hình nhỏ trước. Chỉ giao S01 khi handoff S00 đủ bằng chứng baseline, rồi lần lượt các ID tiếp theo. Handoff FAIL/BLOCKED không được bỏ qua ở gói phụ thuộc. Với gói UI lớn hơn năng lực một lượt, chỉ tách đúng gói thành phần logic rồi UI/test, giữ nguyên ID và contract; không bỏ kiểm thử để rút ngắn.

Không cần giao cả kế hoạch để model tự sửa tất cả trong một lượt. Kế hoạch này chưa phải ủy quyền triển khai trong phiên hiện tại.

