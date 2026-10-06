# Kế hoạch trình đọc kết quả OCR, Word và Excel nhẹ

Ngày: 22/09/2026. Trạng thái: đề xuất để phê duyệt, chưa triển khai mã nguồn.

Cập nhật phạm vi: người dùng đã chốt mục tiêu tổng tăng không quá 10 MB cho trình đọc, bộ sửa cơ bản và xuất Word/Excel. Kế hoạch thực hiện chính là [PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md](PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md), gồm 24 gói S00–S23; ngân sách và định nghĩa phép đo trong tài liệu mới thay các mốc tổng cũ ở dưới. Đây vẫn là yêu cầu lập kế hoạch, chưa triển khai code.

## 1. Kết luận và phạm vi

Có thể tự mở trình đọc ngay sau “Trích xuất văn bản” mà không nhúng cả bộ Office. Đề xuất dùng trình xem nội bộ dựa trên ảnh trang và dữ liệu OCR có tọa độ. Xuất DOCX/XLSX là chức năng riêng dùng chung dữ liệu này. Đọc mọi file Word/Excel từ bên ngoài là phạm vi mở rộng, không nên gộp vào bản đầu.

Cần phân biệt ba kết quả:

- **Bản quét có lớp chữ:** giữ ảnh trang làm nền nên giữ hình thức bản quét; lớp OCR phục vụ chọn/copy/tìm kiếm. Chữ nhìn thấy vẫn là ảnh, nội dung copy có thể sai nếu OCR sai. Đây không phải văn bản Office đã tái tạo.
- **Văn bản tái tạo:** dựng lại tiêu đề, đoạn, cột, bảng bằng chữ thật. Có thể sửa và xuất Word nhưng font, ngắt dòng, hình ảnh, dấu và chữ ký không thể được bảo đảm giống tuyệt đối.
- **Bảng dữ liệu:** tái tạo hàng/cột/ô để xuất Excel. Cần nhận diện cấu trúc bảng; trình đọc Excel không tự làm được việc này. Không ép tài liệu không có bảng thành bảng.

Chọn mặc định “Bản quét”; có tab “Văn bản”, và “Bảng” khi phát hiện bảng. Nếu yêu cầu bắt buộc chữ thật nhìn giống ảnh ngay từ đầu thì phải nghiệm thu phần tái tạo bố cục trước khi tuyên bố đạt yêu cầu; bản quét kèm lớp chữ chỉ đáp ứng phần nhìn và truy xuất nội dung.

## 2. Bằng chứng từ checkout hiện tại

- `app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt`: gán `extractedText` vào `tvOcrContent`; nút Word dùng đuôi `doc`, Excel dùng `csv`; mở file qua `ACTION_VIEW` sang ứng dụng khác.
- `app/src/main/java/com/tscanner/app/utils/OcrModels.kt`: `OcrResult.Success` và `EngineRunResult.Success` giữ chuỗi chữ, chưa mang tọa độ block/line/word; kết quả nhiều trang có `fullText` nhưng chưa có mô hình bố cục từng trang.
- `app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt`: `exportTextToWord` ghi HTML; `exportTextToExcel` tách dòng/tab/chấm phẩy để ghi CSV, chưa nhận diện bảng và chưa tạo XLSX.
- `app/src/main/java/com/tscanner/app/paddleocr/PaddleOcrEngine.kt`: có quadrilateral lúc detect nhưng kết quả cuối ghép thành chuỗi; cần giữ hình học xuyên suốt pipeline.
- `app/src/main/java/com/tscanner/app/utils/TesseractOcrHelper.kt`: adapter hiện dùng `getUTF8Text`; cần kiểm chứng API layout/iterator hoặc hOCR của phiên bản đang dùng trước khi triển khai adapter tọa độ.
- Điểm gọi hiện có: `HomeFragment.kt`, `FilesFragment.kt`, `PdfViewerActivity.kt`; cần rà thêm đường đi sau quét khi triển khai để tránh chỉ sửa một cửa vào.
- `app/build.gradle`: đã dùng ML Kit dạng Play Services, ONNX Runtime và Tesseract; release đang `minifyEnabled false`, có hai ABI ARM. Không cần thêm engine OCR cho bản đầu.
- Asset chưa nén: Paddle recognition 10.83 MB, detection 4.74 MB, eng.traineddata 4.11 MB, vie.traineddata 0.53 MB (MB thập phân). Đây không phải dung lượng tải APK hay dung lượng tăng của tính năng.

Chưa build bản thử nghiệm, chưa đo APK/AAB, RAM, độ chính xác hay chạy thiết bị trong lần nghiên cứu này. Checkout có nhiều thay đổi có sẵn; chỉ thêm tài liệu kế hoạch.

## 3. So sánh phương án

| Phương án | Độ giống ảnh | Dung lượng và giới hạn | Quyết định |
|---|---|---|---|
| Native page viewer + lớp OCR | Giữ hình thức khi dùng ảnh nền; OCR copy cần kiểm chứng | Tái dùng Android Canvas/View và thư viện ảnh đang có; không cần model mới | Chọn cho bản đầu |
| WebView + HTML bố cục nội bộ | Có thể gần ảnh, phụ thuộc font và layout | Dùng WebView hệ thống; vẫn tốn RAM/cache, cần quản lý selection và an toàn nội dung | Phương án thay thế khi prototype chứng minh có lợi |
| docx-preview + SheetJS trong WebView | Đọc DOCX/bảng XLSX; không khôi phục bố cục đã mất ở OCR | Có JS và dependency bổ sung; phải đo bản bundle thực tế, không suy từ dung lượng npm | Chỉ cân nhắc cho đọc file ngoài |
| Mammoth DOCX → HTML | Ưu tiên nội dung/ngữ nghĩa hơn độ giống định dạng | Không đáp ứng mục tiêu giữ trang chính xác | Không chọn làm bộ dựng chính |
| SDK Office đầy đủ | Tùy SDK, font và loại file | Cần PoC Android, đo APK/RAM, rà giấy phép và giá tại thời điểm chọn | Chưa nhúng vào base app |
| Chuyển đổi trên server | Tùy engine chuyển đổi và OCR | Client nhẹ, nhưng cần mạng, chi phí vận hành và xử lý dữ liệu tải lên | Chỉ là tùy chọn tương lai |
| Mở bằng Office bên ngoài | Tùy ứng dụng được cài | Hầu như không thêm renderer trong app; không đạt đọc ngay bên trong | Giữ như tùy chọn mở ngoài |

## 4. Kiến trúc đề xuất

Ảnh trang đã crop/xoay → engine OCR hiện có → adapter chuẩn hóa hình học → hồ sơ OCR trên đĩa → trình xem / tái tạo văn bản / nhận diện bảng → exporter.

Mô hình mới dự kiến `OcrDocument`, `OcrPage`, `OcrBlock`, `OcrLine`, `OcrToken`, `OcrTable`, `OcrCell`. Lưu page ID, thứ tự, kích thước ảnh chuẩn, polygon, thứ tự đọc, text, engine/ngôn ngữ/trạng thái và confidence nếu engine cung cấp. Confidence thiếu phải là null, không bịa giá trị và không so trực tiếp giữa các engine.

Lưu rõ biến đổi từ ảnh gốc/ảnh trang cuối sang ảnh OCR đã resize, crop hoặc xoay. Tọa độ chuẩn phải gắn đúng phiên bản ảnh trang; khi chỉnh sửa ảnh phải vô hiệu hóa hoặc tái tạo OCR. Bảng giữ row/column spans và loại dữ liệu, không suy ô chỉ bằng khoảng trắng.

Persist bằng file có phiên bản schema và ghi nguyên tử; Intent/Bundle chỉ truyền document ID và trạng thái UI nhỏ, không truyền toàn bộ OCR nhiều trang. Quản lý quyền sở hữu ảnh nguồn để cleanup cache không xóa ảnh đang được trình đọc sử dụng. Giữ nguồn ảnh theo vòng đời tài liệu, dọn phiên bị hủy, khôi phục sau process death.

Native viewer tải trang theo nhu cầu, zoom với ảnh đủ độ phân giải/ROI, lớp chọn chữ căn theo cùng ma trận ảnh. Phải có copy từng dòng/vùng, tìm và tô sáng, thứ tự đọc nhiều cột, mô tả accessibility; lựa chọn từ chỉ bật khi engine có hình học đủ chính xác. Engine thiếu tọa độ cho xem ảnh và text riêng, không dựng hộp chọn giả.

Kết quả trống/lỗi từng trang phải có trạng thái rõ; không âm thầm bỏ trang. Nhận diện lại ngôn ngữ tạo phiên bản kết quả mới rồi thay thế sau khi lưu thành công. Giữ độc lập ngôn ngữ giao diện và ngôn ngữ OCR.

## 5. Word/Excel trong phạm vi nhỏ

- DOCX: bản đầu hỗ trợ đoạn, tiêu đề, ngắt trang, bảng cơ bản và ảnh. Ưu tiên nội dung sửa được; định dạng gần nguồn trong phạm vi đã nghiệm thu. “Giữ nguyên hình thức” bằng ảnh chèn vào DOCX phải được ghi rõ là ảnh, không quảng bá là text có thể sửa.
- XLSX: chỉ xuất vùng bảng đã xác nhận; bảo toàn số 0 đầu, mã định danh, dấu thập phân/ngày tháng, Unicode; chưa suy công thức từ ảnh. Nội dung OCR bắt đầu bằng `=`, `+`, `-`, `@` không tự trở thành công thức. Ô khó xác định cần cho sửa/xác nhận.
- Nghiên cứu writer OOXML giới hạn dùng ZIP/XML có sẵn để tránh bộ SDK lớn. Đây là module cần kiểm thử tương thích thực tế, không phải chỉ đổi đuôi file. Nếu chi phí đúng chuẩn quá cao, so sánh thư viện writer nhỏ bằng APK thực đo trước khi chọn.
- Trình xem dùng mô hình chung là preview trước xuất. Không gọi đó là đọc lại file DOCX/XLSX nếu chưa parse file. Giai đoạn đọc file ngoài phải có parser riêng và nghiệm thu reopen file xuất ra.
- Đọc file ngoài ban đầu chỉ cân nhắc DOCX/XLSX phổ biến, không mặc định bao gồm DOC/XLS nhị phân, file mật khẩu, macro, biểu đồ, pivot hoặc tính toán công thức.

## 6. Ngân sách dung lượng

Mục tiêu đề xuất, chưa phải số đo: phần viewer + geometry tăng không quá 2 MB tải nén trên cùng cấu hình thiết bị; toàn bộ viewer và exporter giới hạn tăng không quá 5 MB. Nếu vượt, xem lại dependency/phạm vi trước khi thêm tính năng. Chốt lại ngân sách sau prototype.

Đo baseline và candidate cùng build type, ABI, signing, minification và dependency lock; dùng APK Analyzer/bundletool để so tải theo thiết bị, đồng thời ghi APK phổ thông khi phát hành ngoài Play. Báo riêng kích thước tải, cài đặt, model tải lần đầu, cache ảnh/OCR và RAM peak. Không bù phần tăng bằng một thay đổi shrink khác rồi báo là tính năng không tăng dung lượng.

Ưu tiên tái sử dụng engine/thư viện đã có; không nhúng Chromium hoặc bộ font lớn. R8/resource shrink là hạng mục tối ưu riêng có kiểm thử ONNX, Tesseract, reflection/JNI và luồng xuất. Model tùy chọn có thể tải khi cần, nhưng tổng dung lượng sau tải vẫn tăng và lần đầu cần mạng.

Play Feature Delivery có thể tách module đọc Office nâng cao tải theo yêu cầu khi phát hành qua Google Play/AAB. Không giả định cơ chế này tự hoạt động với APK cài ngoài; bản sideload cần chiến lược đóng gói riêng. Không cần dynamic feature cho viewer nhỏ ở bản đầu.

## 7. Các bước triển khai sau phê duyệt

| Bước | File/phần ảnh hưởng | Thay đổi | Kiểm thử và điều kiện hoàn thành | Trạng thái |
|---|---|---|---|:---:|
| P0 — baseline và mẫu chuẩn | build hiện tại; corpus và báo cáo mới | Thu 30–50 trang có quyền sử dụng: văn bản Việt/Anh, hai cột, biểu mẫu, bảng gộp ô, ảnh xoay/mờ, nhiều trang; tạo ground truth; đo dung lượng | Có baseline tải/cài/RAM và quy tắc chấm; không dùng dữ liệu thật nhạy cảm trong repo | **HOÀN THÀNH (S00, S01)** |
| P1 — schema và lưu trữ | `OcrModels.kt`; mới `OcrDocumentRepository.kt` | Giữ text tương thích; thêm trang/hình học/metadata/version, lưu nguyên tử | Roundtrip Unicode/polygon; lỗi ghi, thiếu nguồn, process death không mất kết quả đã commit | **HOÀN THÀNH (S02, S03)** |
| P2 — adapter engine | `TextRecognitionHelper.kt`, `TesseractOcrHelper.kt`, `PaddleOcrEngine.kt`, aggregator trong `OcrModels.kt` | Truyền hình học xuyên suốt, chuẩn hóa scale/rotation, giữ page order và fallback | Unit test phép biến đổi; kiểm chứng thiết bị từng engine; không lấy tọa độ engine A ghép tùy tiện với text engine B | **HOÀN THÀNH (S04, S05, S06, S07, S08)** |
| P3 — tự mở trình đọc | `OcrResultActivity.kt`, `activity_ocr_result.xml`; viewer mới; các caller Home/Files/PDF | Đọc hồ sơ bằng ID, mặc định bản quét, tab text, zoom/select/search | OCR xong tự hiển thị; xoay máy/Back/khôi phục hoạt động; copy/tìm kiếm đúng trang; nguồn bị xóa có lỗi rõ | **HOÀN THÀNH (S09, S10, S11, S12, S13, S14)** |
| P4 — cấu trúc bố cục/bảng | mới `DocumentLayoutAnalyzer.kt`, `TableStructureAnalyzer.kt` | Gom đoạn/cột, nhận bảng đường kẻ trước, bảng không kẻ sau; cho sửa ô | Đánh giá trên corpus có ground truth; không bịa bảng khi thiếu bằng chứng; giữ đúng thứ tự đọc | **HOÀN THÀNH (S15, S16, S17, S18)** |
| P5 — xuất chuẩn | writer DOCX/XLSX mới; `PdfConverterHelper.kt`, `ExportDocDialog.kt`, `OcrResultActivity.kt` | Xuất từ mô hình cấu trúc, MIME/đuôi đúng, ghi nguyên tử, giữ chính sách watermark | Mở trong Word/Excel và LibreOffice không yêu cầu sửa file; Unicode, ô gộp, text-number, lỗi IO/hủy tác vụ được kiểm thử | **HOÀN THÀNH (S19, S20, S21)** |
| P6 — nghiệm thu | tests hiện có và tests mới; thiết bị ARM | Build/lint, test hồi quy OCR, bộ ảnh thực và đo delta APK/RAM | Đạt ngân sách và chất lượng đã chốt; báo riêng giới hạn chưa hỗ trợ | **HOÀN THÀNH (S22, S23)** |
| P7 — tùy chọn đọc file ngoài | module reader độc lập, WebView/assets nếu chọn | PoC docx-preview/SheetJS hoặc SDK, rà license/version/dependency | Parse lại file xuất và mẫu ngoài; giới hạn ZIP/XML, không tải tài nguyên ngoài, không chạy macro; chỉ đưa vào sản phẩm khi đạt size gate | Độc lập sau P6 |

Thứ tự: P0 → P1 → P2 → P3 là bản đầu dùng được; P4 → P5 → P6 hoàn thiện Word/Excel. P7 là quyết định độc lập sau đó. Không cần thêm model nhận bảng lớn trước khi có bằng chứng heuristic không đạt corpus.

## 8. Tiêu chí nghiệm thu đề xuất

- Trên cùng ảnh trang và cùng zoom, lớp hiển thị bản quét không làm đổi bố cục/cắt mất nội dung; kiểm tra full-resolution và ROI chữ nhỏ. Độ giống ảnh và độ đúng OCR là hai phép đo riêng.
- Phép chiếu hình học trên fixture có sai số tối đa 1 pixel tọa độ trang do làm tròn; đo chất lượng box engine riêng, không coi phép chiếu đúng là OCR đúng.
- Trên tập in rõ Việt/Anh: mục tiêu CER ≤ 2%; trên tập bảng đơn giản: ≥ 95% cấu trúc ô đúng theo ground truth. Đây là ngưỡng đề xuất cần chốt ở P0; báo riêng ảnh xấu/bảng khó, không lấy trung bình che lỗi.
- Không mất/trùng/đảo trang; copy nội dung và tìm kiếm dùng đúng phiên bản kết quả; vùng chưa chắc chắn được thể hiện phù hợp khả năng engine.
- Sau khi OCR hoàn tất, trang đầu hiện trong ≤ 1 giây ở p95 trên thiết bị chuẩn được chọn; kiểm thử tài liệu 1/10/50 trang trên máy RAM 3–4 GB, không giữ toàn bộ bitmap full-res cùng lúc, không crash/ANR. Chốt ngân sách RAM cụ thể từ baseline P0.
- Đánh giá Word/Excel sửa được bằng cấu trúc, nội dung và tương thích; không cam kết pixel-perfect cho font/chữ ký/con dấu tái tạo.
- Chạy test tập trung, sau đó `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; kiểm tra release và thiết bị thật riêng. JVM/build pass không thay cho kiểm thử lựa chọn chữ, WebView, IO, process death và độ giống ảnh.

## 9. Nguồn nghiên cứu trực tiếp

- ML Kit text recognition Android: https://developers.google.com/ml-kit/vision/text-recognition/v2/android — API có block/line/element và tọa độ; tài liệu nêu bản unbundled khoảng 260 KB mỗi script/architecture, bundled khoảng 4 MB. Không phải ước lượng tổng APK của dự án.
- docx-preview: https://github.com/VolodymyrBaydalka/docxjs — render DOCX thành HTML, giới hạn bởi khả năng HTML và các tính năng thư viện hỗ trợ.
- SheetJS HTML utilities: https://docs.sheetjs.com/docs/api/utilities/html/ — chuyển worksheet sang HTML; không phải cam kết dựng trang in giống Excel.
- Mammoth: https://github.com/mwilliamson/mammoth.js — tập trung HTML ngữ nghĩa, bỏ qua một số định dạng, không phù hợp làm giải pháp bảo toàn bố cục chính.
- Play Feature Delivery: https://developer.android.com/guide/playcore/feature-delivery và https://developer.android.com/guide/playcore/feature-delivery/on-demand — phân phối module theo yêu cầu.

Các lựa chọn thư viện là ứng viên nghiên cứu, chưa được cài hay benchmark trong T-Scanner. Khi triển khai phải pin phiên bản, kiểm tra license/transitive dependencies và đo artifact Android thực tế.
