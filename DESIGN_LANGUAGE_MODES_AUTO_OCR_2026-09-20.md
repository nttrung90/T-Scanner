# Đề xuất chế độ ngôn ngữ giao diện và OCR tự nhận diện

Ngày nghiên cứu: 20/09/2026. Chỉ nghiên cứu và thiết kế, chưa thay đổi code sản phẩm.

## 1. Hành vi giao diện đề xuất

Mục **Ngôn ngữ ứng dụng** có chín lựa chọn: Theo thiết bị (mặc định), English, Tiếng Việt, Español, Português (Brasil), Français, Bahasa Indonesia, Deutsch, 日本語.

| Chế độ | Ngôn ngữ máy | UI |
|---|---|---|
| Theo thiết bị | Một trong tám nhóm hỗ trợ | Theo ngôn ngữ chính của máy |
| Theo thiết bị | Ngoài tám nhóm | Anh |
| Chọn thủ công | Bất kỳ | Giữ lựa chọn của người dùng |
| Chuyển từ thủ công về Theo thiết bị | Bất kỳ | Tính lại từ ngôn ngữ chính của máy |

Giữ quy tắc primary-only đã thống nhất: máy `[ko,vi]` ở chế độ Theo thiết bị vẫn dùng Anh. Không thay đổi UI vì phát hiện tài liệu Nhật hoặc Việt. Đổi UI không đổi ngôn ngữ OCR.

Ví dụ nhãn ở More: “Theo thiết bị · Tiếng Việt” hoặc “日本語”. Bộ chọn nên hiển thị tên bản địa để người dùng tìm được ngôn ngữ dù UI hiện tại khó hiểu.

### Tác động lên kiến trúc hiện có

`AppLanguageManager.enforceAutoSystemLanguage` hiện chạy khi khởi tạo/resume và luôn ép theo máy. Phải thay bằng hàm áp dụng chính sách có hai nhánh SYSTEM và MANUAL; chỉ khôi phục dialog cũ sẽ khiến lựa chọn bị ghi đè khi resume.

Lưu **ý định** riêng: `uiLanguageMode = SYSTEM | MANUAL`, `manualUiTag` là một trong tám mã. Locale áp dụng trong framework chỉ là kết quả, không dùng nó để suy ra ý định: bản hiện tại đã ghi locale tự động thành override framework.

Vì SYSTEM yêu cầu chỉ dùng ngôn ngữ đầu tiên, không thể chỉ xóa app locale và mặc nhiên dùng Android resource matching theo toàn bộ danh sách. Dùng resolver primary-only rồi áp dụng locale hiệu lực; MANUAL dùng mã đã lưu. Chỉ gọi setter khi thay đổi, bảo toàn draft khi Activity recreate.

**Phạm vi bộ chọn:** triển khai trong app trước, tiếp tục không quảng bá `localeConfig` trong Android Settings. Android có API và bộ chọn riêng từ API 33, nhưng đồng bộ nó với SYSTEM primary-only và override nội bộ đòi hỏi thiết kế bổ sung. Không bật bộ chọn hệ thống rồi để callback resume ghi đè lựa chọn của người dùng ngoài app. Nếu muốn tích hợp Android Settings sau này, phải chốt semantics và kiểm thử riêng.

**Migration:** bản hiện tại luôn auto nên mặc định chuyển sang SYSTEM, không hiểu locale framework đã lưu là manual. Người chọn thủ công sau nâng cấp được lưu MANUAL bền vững. Tag manual không hợp lệ/đã bị loại phải quay SYSTEM. Tách version migration, xét cả khôi phục backup, không xóa dữ liệu OCR.

## 2. Thiết kế “Ngôn ngữ tài liệu”

Đổi dòng mặc định thành:

**Ngôn ngữ tài liệu — Tự nhận diện**

Mô tả: “Ưu tiên tiếng Việt và tiếng Anh. Bạn có thể chọn ngôn ngữ nếu kết quả chưa đúng.”

Khi mở:

- Tự nhận diện — mặc định.
- Tiếng Việt + English — chế độ cố định cho tài liệu song ngữ.
- Chọn ngôn ngữ cụ thể — mở catalog OCR hiện có, độc lập tám UI.

Ở màn hình kết quả, hiển thị riêng “Phát hiện: Tiếng Việt”, “Phát hiện: Việt + Anh”, hoặc “Chưa xác định chắc chắn”, kèm hành động **Chọn ngôn ngữ và nhận dạng lại**. Không gắn nhãn “Việt + Anh” chỉ vì đã dùng hai model đó: model được thử không đồng nghĩa ngôn ngữ được phát hiện.

Không bắt người dùng chọn ngôn ngữ trước lần quét đầu. Kết quả phát hiện là thông tin theo trang/phiên, không tự chuyển thiết lập toàn ứng dụng thành ngôn ngữ vừa phát hiện.

### Cơ sở kỹ thuật và giới hạn

1. Tesseract hỗ trợ dùng nhiều language model trong một lần OCR. Helper hiện tại đã chấp nhận `vie+eng`; cần kiểm chứng đầu ra và hiệu năng với bộ dữ liệu của app. Thứ tự model có thể ảnh hưởng kết quả. Đây là OCR song ngữ, chưa phải phép xác định ngôn ngữ ảnh.
2. ML Kit Language Identification nhận chuỗi văn bản và có thể trả `und` khi không đủ chắc. Danh sách probable languages là các giả thuyết cho cả chuỗi, không phải phân đoạn tài liệu đa ngôn ngữ. Muốn nhận biết Việt+Anh phải phân tích các đoạn đủ dài rồi tổng hợp, chấp nhận vùng chưa xác định.
3. ML Kit OCR có các recognizer theo hệ chữ Latin, Trung, Nhật, Hàn, Devanagari. Tự nhận diện văn bản không tự động chọn đúng recognizer trước khi OCR. Không được chạy Latin rồi kết luận ảnh Nhật không có chữ.

## 3. Pipeline đề xuất

### Nhánh mặc định Việt–Anh

1. Nhận trang, kiểm tra ảnh, tạo đầu vào có giới hạn kích thước nhưng giữ đủ chi tiết chữ. Xử lý tuần tự, có hủy và giới hạn RAM.
2. OCR thử bằng Tesseract `vie+eng`, tận dụng model local sẵn có. Nếu lỗi model, báo lỗi phù hợp; chỉ fallback engine khi policy cho phép và ghi đúng metadata.
3. Phân tích văn bản theo khối/đoạn bằng Language Identification bundled để không chờ tải model ở lần đầu. Chọn biến thể bundled sau khi kiểm tra dependency/license và đo APK thực tế.
4. Nếu nhận Việt/Anh rõ: dùng kết quả phù hợp; chỉ OCR lại với model chuyên biệt khi benchmark chứng minh có lợi. Tài liệu song ngữ tiếp tục dùng kết quả song ngữ để không làm mất phần còn lại.
5. Nếu chữ Latin có tín hiệu ngôn ngữ khác: thử recognizer Latin hiện có; so sánh chất lượng theo tiêu chí đã hiệu chỉnh, không chỉ chọn chuỗi dài nhất hay confidence nhận diện ngôn ngữ cao nhất.
6. Chuỗi ngắn, toàn số, tên riêng, dấu mờ hoặc tiếng Việt không dấu: cho phép chưa xác định; vẫn hiển thị văn bản đọc được. Không tự gắn nhãn Anh chỉ vì không có dấu Việt.

### Nhánh tự động cho các hệ chữ khác

Đây là phần mở rộng cần thử nghiệm trước khi tuyên bố nhận diện toàn bộ catalog OCR. Chữ Latin nhận sai vẫn có thể tạo văn bản trông hợp lệ, nên điều kiện “Latin có text” không đủ để bỏ qua hệ chữ khác.

Đề xuất nghiên cứu bộ phân loại hệ chữ trên ảnh trước OCR, hoặc một vòng probe giới hạn bằng các recognizer theo hệ chữ sẵn có. Hiện chưa có bộ phân loại ảnh như vậy được xác nhận trong code. Probe phải có giới hạn thời gian/RAM, chạy tuần tự, kiểm tra model sẵn sàng và không tự tải tất cả model theo mỗi lần quét. Không so sánh trực tiếp confidence giữa các engine nếu chưa hiệu chỉnh.

Với tài liệu Trung/Nhật chỉ chứa Hán tự, có thể không đủ bằng chứng phân biệt. Báo nhóm chữ hoặc chưa xác định, cho phép người dùng chọn lại; không đoán chắc giản thể/phồn thể từ script chung. Tài liệu đa hệ chữ cần quyết định theo vùng hoặc trang, không áp ngôn ngữ trang đầu cho toàn bộ PDF.

Trong giai đoạn đầu, tự động Việt–Anh và Latin được triển khai/đánh giá trước; các hệ chữ khác vẫn có chọn thủ công. Không quảng bá “tự động mọi ngôn ngữ” khi nhánh này chưa nghiệm thu. Đây là lộ trình triển khai, không loại bỏ khả năng OCR thủ công hiện có.

### Chất lượng và thất bại

Không đồng nhất confidence language-ID với độ chính xác OCR. Kết hợp đủ lượng chữ, tỉ lệ ký tự bất thường, coverage vùng chữ, tính ổn định và kết quả benchmark. Ngưỡng phải được hiệu chỉnh trên dữ liệu kiểm thử, chưa chốt con số tùy ý trong thiết kế.

Ảnh mờ, trang trắng, model lỗi, mất mạng và nhận diện không chắc là các trạng thái khác nhau. Khi nghi có chữ nhưng chưa nhận diện được, không tự coi là trang trắng để xuất thiếu nội dung. Giữ chính sách lỗi trang chặn xuất âm thầm hiện tại.

## 4. Dữ liệu và xung đột thiết lập

Đề xuất tách:

- `uiLanguageMode`, `manualUiTag`: UI.
- `ocrLanguageMode = AUTO | VI_EN | MANUAL`, `manualOcrTag`: ý định OCR.
- `requestedMode`, `candidateLanguages`, `detectedLanguages`, `detectionStatus`, `engineId`, `modelLanguages`, `fallbackUsed`: kết quả theo trang. Engine/model language không phải detected language.

Không truyền `"auto"` hoặc `"vi+en"` như một BCP-47 languageTag bình thường vào router cũ. Chỉ adapter Tesseract đổi chế độ song ngữ sang `vie+eng`.

Hiện `getOcrDocumentLanguage` còn suy luận và lưu ngôn ngữ từ UI khi chưa có lựa chọn. Bỏ phụ thuộc này; mặc định OCR AUTO với ưu tiên Việt–Anh dù UI Nhật, Đức hoặc Anh. Trạng thái AUTO là cấu hình hợp lệ, không mở dialog bắt chọn ngôn ngữ.

Preferences cũ không phân biệt chắc được người dùng chọn với giá trị tự lưu từ UI. Đề xuất chuyển mặc định sang AUTO như yêu cầu mới, giữ giá trị cũ dưới dạng lựa chọn gần nhất để người dùng chọn lại; không giả vờ biết nguồn gốc lựa chọn. Xét migration một lần và backup restore, không reset thiết lập mới mỗi lần mở app.

Mục **Công cụ OCR** mặc định Auto. Đưa ép engine vào nâng cao. Khi ép engine, hiển thị phạm vi hỗ trợ và không âm thầm chuyển sang engine khác; nếu chọn AUTO language nhưng engine bị ép không hỗ trợ nhận diện rộng, cho người dùng chuyển engine về Auto hoặc chọn ngôn ngữ tương thích. Nhánh mặc định AUTO language + AUTO engine phải chạy ngay không hỏi thêm.

## 5. Gói thực hiện cho model nhỏ

| Gói | Phạm vi | Nghiệm thu |
|---|---|---|
| U01 | `AppLanguageManager`, preferences, migration, unit tests | SYSTEM/MANUAL độc lập; manual không bị resume ghi đè; `[ko,vi]` SYSTEM→Anh |
| U02 | Dialog chọn UI, More, tài nguyên tám ngôn ngữ, lifecycle | Chọn đủ tám UI, quay về Theo thiết bị; giữ draft và lựa chọn sau khởi động lại |
| O01 | `OcrModels`, preferences/helper, dialog OCR | AUTO mặc định; không suy từ UI; VI_EN khác MANUAL; migration đúng |
| O02 | Adapter Tesseract và pipeline song ngữ | Đúng `vie+eng`; không hạ một model âm thầm; văn bản song ngữ giữ đủ, lỗi model rõ |
| O03 | Language-ID, kết quả theo đoạn/trang, UI kết quả | Việt/Anh/mixed/und đúng trên bộ mẫu; có chọn lại; không ghi detection thành preference |
| O04 | Router fallback Latin, trạng thái lỗi và metadata đa trang | Không xuất thiếu trang, giữ engine/fallback theo trang, không dùng confidence ngôn ngữ làm điểm OCR |
| O05 | Thử nghiệm phân loại/probe hệ chữ | Đo tốc độ/RAM/độ sai trên Nhật/Trung/Hàn/Hindi; chỉ mở rộng auto sau khi có bằng chứng |
| Q01 | Kiểm thử tích hợp và bản cài AAB | UI và OCR độc lập, offline đúng khả năng model đã đóng gói, lifecycle/migration pass |

Thứ tự U01→U02; sau đó O01→O02→O03→O04→O05→Q01. Mỗi lượt chỉ một gói. Không trộn engine refactor lớn với chuyển preference/UI.

### Bộ kiểm thử bắt buộc

- Máy Hàn + UI SYSTEM→Anh; chọn Việt thủ công→Việt; đổi máy Nhật vẫn Việt; quay SYSTEM→Nhật. Kiểm tra API 26–32 và 33+.
- UI bất kỳ + tài liệu Việt/Anh/mixed: kết quả OCR không phụ thuộc UI.
- Mẫu tiếng Việt đủ dấu/không dấu, tiếng Anh, tài liệu hai cột song ngữ, số/tên riêng, ảnh mờ/nghiêng, trang trắng.
- PDF xen kẽ Việt/Anh/Nhật; không dùng kết luận trang đầu cho toàn tài liệu.
- Model thiếu/hỏng, hết dung lượng, offline, người dùng hủy; không báo đang tải nếu không có tác vụ tải.
- Người dùng sửa ngôn ngữ và chạy lại: giữ ảnh gốc, không mất kết quả trước khi kết quả mới thành công.
- Benchmark CER/WER trên mẫu có ground truth, độ đúng nhãn ngôn ngữ, thời gian và RAM trên máy yếu; so sánh pipeline hiện tại với mới. Ngưỡng nghiệm thu chốt từ baseline đo được, không khẳng định model kết hợp luôn tốt hơn.

## 6. Nguồn nghiên cứu

- [Android per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages): API áp dụng locale và lựa chọn độc lập với hệ thống.
- [ML Kit Language Identification](https://developers.google.com/ml-kit/language/identification/android): đầu vào text, trạng thái und, bundled/unbundled; không phân đoạn đa ngôn ngữ trong một chuỗi.
- [ML Kit OCR Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android): lựa chọn recognizer theo hệ chữ và model phân phối.
- [Tesseract multiple languages](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html): kết hợp language model và ảnh hưởng của thứ tự.

Đã đọc code hiện tại và đối chiếu tài liệu chính thức. Chưa benchmark, chạy thử OCR trên ảnh hoặc sửa code; đây là thiết kế để triển khai và nghiệm thu, không phải kết quả đã đạt.
