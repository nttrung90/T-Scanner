# Kiểm tra triển khai UI thủ công + OCR tự nhận diện

Ngày 20/09/2026. Chỉ kiểm tra, không sửa code sản phẩm. Phạm vi: DESIGN_LANGUAGE_MODES_AUTO_OCR_2026-09-20.md và các luồng gọi liên quan. Lỗi hành vi dưới đây có bằng chứng từ code; chưa tái hiện trên thiết bị. Không phải chứng nhận toàn bộ ứng dụng hết lỗi.

## Phần đã triển khai đúng hướng

Kiểm tra tự động trong lượt này: `python scripts/audit_localization.py` FAIL với 6 bộ locale thiếu 9 key/bộ; 12/12 unittest của validator PASS. Gradle `:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain` BUILD SUCCESSFUL; 166 unit tests, 0 failures/errors; lint 0 errors, 688 warnings. Build thành công không làm mất hiệu lực lỗi validator: app có thể compile nhờ fallback tiếng Anh. Không build/ghi đè release AAB.

- UI có SYSTEM/MANUAL, preference riêng và `applyPolicy` không còn luôn ép lựa chọn thủ công về ngôn ngữ máy.
- OCR có AUTO/VI_EN/MANUAL, migration riêng; AUTO không còn mặc định suy ngôn ngữ từ UI. Có Tesseract `vie+eng` và Language Identification.
- Có metadata detection theo trang và bộ kiểm thử mới. Tuy nhiên các thành phần chưa được nối đầy đủ đến luồng người dùng.

## F01 — P2: “Nhận dạng lại” không chạy OCR tại các luồng hiện tại

`ui/home/HomeFragment.kt:430,559`, `ui/files/FilesFragment.kt:424`, `ui/viewer/PdfViewerActivity.kt:394` gọi `OcrResultActivity.start` mà không truyền imagePath/imagePaths. `ui/ocr/OcrResultActivity.kt:316–328` gặp nguồn rỗng thì chỉ đổi nhãn thành “Phát hiện: [ngôn ngữ người dùng chọn]” rồi return. Văn bản vẫn là kết quả cũ; thao tác chọn cũng đã thay preference OCR toàn app trong dialog.

Tái hiện: OCR ảnh/PDF → chọn ngôn ngữ khác ở kết quả → bấm nhận dạng lại. Nhãn đổi nhưng không có OCR mới. Không được trình bày lựa chọn của người dùng như kết quả phát hiện.

Sửa: truyền nguồn bền vững (ảnh hoặc PDF/URI và số trang) có quyền đọc/vòng đời rõ ràng. Khi thiếu nguồn phải báo không thể chạy lại, không giả cập nhật detection. Giữ kết quả cũ đến khi lần chạy mới thành công.

**Cần sửa cùng gói trước khi nối nguồn:** dòng 342 chỉ chọn `imagePaths.firstOrNull()` và thay toàn bộ extractedText bằng trang đầu. Nếu chỉ bổ sung imagePaths, nhận dạng lại PDF sẽ làm mất các trang sau trong kết quả. Cần chạy đủ trang, gom kết quả với policy lỗi trang hiện có. Lần chạy lại hiện cũng không lưu kết quả mới vào saved state/nguồn Intent: recreate sẽ đọc lại kết quả cũ. Test nhiều trang, lỗi giữa chừng, đổi locale sau rerun và hai thao tác rerun liên tiếp.

## F02 — P2: Nhận nhầm dấu Latin phổ biến thành tiếng Việt

`utils/OcrLanguageIdentifier.kt:23–26,115,188` đưa é/à/ó/ã/ê… vào regex “dấu tiếng Việt”; có dấu thì bỏ qua ML Kit và gán đoạn là vi. Hai từ có dấu đủ để synthesize trả CONFIDENT vi ngay cả khi paragraphLanguages chứa fr.

Ví dụ đầu vào `Le café est déjà fermé pour la journée.` hoặc `Información sobre educación y atención al cliente.` thỏa nhánh vi mặc dù không phải tiếng Việt. Đây là hệ quả trực tiếp của điều kiện code, chưa phải kết quả chạy trên máy.

Sửa: không dùng các dấu dùng chung làm bằng chứng quyết định, không bỏ qua language-ID chỉ vì có dấu. Phối hợp bằng chứng ngôn ngữ/độ dài/ngữ cảnh; trả chưa chắc khi xung đột. Test Pháp/Tây Ban Nha/Bồ Đào Nha với dấu, Việt có dấu và Việt không dấu. Không gán confidence cố định 0.9 như một xác suất đã được đo.

## F03 — P2: Tách đoạn dùng chuỗi literal nên không phát hiện song ngữ đúng

`utils/OcrLanguageIdentifier.kt:175`: `trimmed.split("\n+")` dùng delimiter chuỗi (newline theo sau dấu cộng), không phải regex. Văn bản xuống dòng bình thường không được chia đoạn. Toàn trang Việt+Anh có dấu bị nhánh dòng 188 đưa thành một đoạn vi; nhánh MIXED cần cả viParagraphs và enParagraphs nên không đạt.

Test song ngữ hiện gọi trực tiếp synthesize với danh sách `vi,en` dựng sẵn, bỏ qua bước split thực tế nên không phát hiện lỗi.

Sửa: tách đoạn đúng, tách hàm phân đoạn thuần để test dữ liệu xuống dòng thật. Test end-to-end với detector giả theo từng đoạn và trang Việt+Anh; không chỉ test danh sách nhãn tự dựng.

## F04 — P2: AUTO hiện chưa có lựa chọn engine dựa trên nhận diện

`utils/TextRecognitionHelper.kt:450–510`: AUTO và VI_EN chạy cùng một nhánh; Tesseract trả bất kỳ Success nào thì return ngay. Language-ID chỉ gắn metadata sau đó, không chọn lại engine khi phát hiện ngôn ngữ khác hoặc không chắc. `OcrScriptProbe` chỉ phân tích chuỗi Unicode và không có callsite production; không phải bộ phân loại ảnh đã tích hợp.

Tác động: tài liệu ngoài Việt–Anh có thể giữ kết quả sai từ model Việt–Anh; Nhật/Trung/Hàn không có đường auto chọn recognizer tương ứng. Khi cả hai recognizer Latin không thấy text có thể thành NoText, không chứng minh trang ảnh thật sự trắng.

Sửa: hoặc công bố rõ auto giai đoạn đầu chỉ ưu tiên Việt–Anh và chuyển trạng thái cần người dùng chọn khi không chắc, hoặc hoàn thiện router/probe có giới hạn theo thiết kế. Không đánh dấu O04/O05 hoàn thành từ unit test phân loại ký tự. Kiểm thử ảnh thật và ground truth trước khi mở rộng auto; không chọn output chỉ vì không rỗng.

## F05 — P2: Metadata nhận diện không khớp engine hoặc mất trên PDF

- Trong `success` ở TextRecognitionHelper, AUTO/VI_EN gán documentLanguage="vi" và modelLanguages="vie+eng" kể cả khi engine thực tế là ML Kit Latin. Tài liệu Anh có thể có nhãn engine tiếng Việt và metadata model Tesseract sai.
- Nhánh MANUAL tự tạo CONFIDENT/confidence=1 từ lựa chọn người dùng; UI gọi nó là “Phát hiện”, mặc dù không chạy language-ID.
- `ui/ocr/OcrResultActivity.kt` overload MultiPage (cuối file) không truyền pageDetections/detectedLanguages/status sang Intent. Banner vì thế hiện chưa xác định dù aggregator đã có kết quả.

Sửa: tách requested language, model thực dùng và detected language; MANUAL hiển thị “Đã chọn” nếu chưa phát hiện. Truyền/khôi phục detection theo trang, tổng hợp trạng thái đúng cho PDF đa ngôn ngữ; không dùng nhãn vi mặc định thay cho kết quả không biết. Test metadata đi hết đến payload màn hình kết quả.

## F06 — P2: Sáu ngôn ngữ thiếu bản dịch của luồng mới

Validator chạy thật FAIL: de/es/fr/in/ja/pt mỗi bộ thiếu 9 keys, tổng cộng 54 mục dịch. Ví dụ `ocr_action_select_and_re_recognize`, `ocr_detected_format`, `ocr_detected_uncertain`, `ocr_detected_vi_en`, `ocr_lang_mode_auto`.

Người dùng chọn sáu UI này sẽ thấy các nhãn mới fallback tiếng Anh. Bổ sung bản dịch thật và kiểm duyệt; chạy lại validator, không suppress MissingTranslation hoặc copy nguồn Anh để đạt pass.

## F07 — P2: Ép engine thủ công vẫn chuyển sang engine khác

`utils/TextRecognitionHelper.kt:480–510` xử lý ENGINE_MODE_TESSERACT cùng AUTO: nếu Tesseract thất bại vẫn gọi ML Kit. Nhánh ENGINE_MODE_MLKIT cũng fallback Tesseract. Các nhánh manual language phía dưới còn chuyển Paddle↔ML Kit.

Khác hợp đồng thiết kế: khi ép engine phải tôn trọng lựa chọn, báo lỗi engine đó hoặc cho người dùng chuyển Auto. Chỉ chế độ engine AUTO được fallback tự động. Test inject lỗi primary ở từng engine mode và kiểm tra engine thứ hai không được gọi khi manual.

## F08 — P2: LanguageIdentifier không được đóng và nuốt hủy tác vụ

`utils/OcrLanguageIdentifier.kt:185–200` tạo client mới mỗi lần identify nhưng không close; catch Throwable bắt cả CancellationException. OCR nhiều trang/lặp lại giữ tài nguyên client không cần thiết; tác vụ đã hủy có thể tiếp tục trả detection giả thành công.

Sửa theo lifecycle: đóng client trong finally hoặc tái sử dụng có owner rõ ràng; rethrow cancellation. Test đường thành công, thất bại và hủy đều giải phóng đúng, hủy không xuất Success. Chưa đo RAM trên thiết bị nên không khẳng định đã gây OOM.

## Ưu tiên và nghiệm thu

F01 trước (nguồn + toàn bộ trang + khôi phục sau recreate), F02/F03, F05, F06, F07/F08; F04 cần gói thử nghiệm/giới hạn phạm vi auto rõ ràng. Không cần viết lại phần UI SYSTEM/MANUAL nếu kiểm thử thiết bị xác nhận đúng.

Không phát hiện lỗi mất file gốc mới trong phạm vi này. Nguy cơ mất các trang sau ở rerun là nhánh có sẵn nhưng hiện chưa có nguồn truyền đến; không ghi nhận như sự cố đã tái hiện với luồng hiện tại.

Thiết bị: `adb devices` không có thiết bị kết nối. Chưa nghiệm thu Activity recreate, OCR ảnh thật, tốc độ/RAM, cài AAB offline hoặc chất lượng bản dịch bản ngữ. Không sửa code sản phẩm trong lượt kiểm tra.
