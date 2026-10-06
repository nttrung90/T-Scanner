# Kiểm tra độc lập OCR Reader / Editor / Word / Excel

Ngày: 23/09/2026. Phạm vi: đối chiếu kế hoạch S00–S23 với code hiện tại; không sửa mã nguồn ứng dụng.

## Kết luận

**Chưa đạt nghiệm thu chức năng.** Có lỗi chặn khởi tạo màn hình, lỗi giữ nguồn ảnh, lỗi sửa/xuất và mất dữ liệu khi lưu. Báo cáo `docs/ocr-reader/final-acceptance.md` ghi PASS toàn phần không phù hợp bằng chứng hiện tại.

- 372 test có sẵn PASS khi chạy lại. Sáu test độc lập bổ sung vào source set tạm dưới `build/ocr-audit/` đều FAIL đúng assertion kiểm tra hành vi mong muốn: tổng 378 test, 6 fail, 0 error, 0 skipped.
- `assembleDebug` và `lintDebug` thành công; lint XML: 0 error, 724 warning. Không khẳng định tất cả warning là mới hoặc nghiêm trọng.
- `assembleRelease` và `bundleRelease` thành công trên checkout hiện tại.
- APK release hiện tại tăng **123.749 byte** so với artifact baseline lưu sẵn: nằm dưới trần 10.000.000 byte.
- Không có thiết bị trong `adb devices`; chưa xác minh UI/lifecycle/process death/RAM hay mở Word/Excel/LibreOffice thực tế. Các kết luận UI dưới đây dựa trên đường đi code; lỗi contract constructor và năm lỗi logic khác có test JVM tái hiện.

## Các lỗi cần sửa

### R01 — P1: Factory mặc định không tạo được OcrReaderViewModel

- Vị trí: `app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt:70`; `app/src/main/java/com/tscanner/app/ui/ocr/reader/OcrReaderViewModel.kt:78`.
- Activity dùng `by viewModels()` và Fragment dùng `activityViewModels()`, không có factory riêng. Constructor có 4 tham số; hai tham số có default của Kotlin không tạo overload JVM `(Application, SavedStateHandle)`.
- Test reflection xác nhận chỉ tồn tại constructor 4 tham số và constructor synthetic 6 tham số. Default factory không có signature hợp lệ để tạo ViewModel: luồng mở màn hình OCR sẽ lỗi khởi tạo trước khi dùng reader/editor.
- Sửa: cung cấp factory đúng hoặc constructor JVM phù hợp; giữ một ViewModel chung cho Activity và hai Fragment. Nghiệm thu bằng Activity thật/ActivityScenario, không chỉ new ViewModel trong unit test.
- Test tái hiện: `readerViewModelMustExposeDefaultFactoryConstructor` FAIL.

### R02 — P1: Trang OCR có imageInfo nhưng URI rỗng, bản quét không hiển thị

- Vị trí: `utils/TextRecognitionHelper.kt:361` và `:730`; `ocr/engine/MlKitLayoutMapper.kt:185`; `utils/TesseractOcrHelper.kt:189`; `paddleocr/PaddleOcrEngine.kt:316`; `ui/ocr/OcrResultActivity.kt:1030` và `:1124` (đều dưới package app/src/main/java/com/tscanner/app).
- Các engine trả pageDocument có `imageInfo.localUri = ""`. Hàm OCR từ file không gắn lại filePath; start một trang dùng pageDocument nguyên trạng. Nhánh nhiều trang chỉ bổ sung khi imageInfo == null nên bỏ qua URI rỗng.
- `OcrReaderViewModel.kt:591` coi URI rỗng là ảnh thiếu. Sau khi sửa R01, OCR thành công vẫn có thể mở tab Bản quét trắng/báo thiếu ảnh.
- `importPageImage` có định nghĩa nhưng không có caller production: quyền sở hữu ảnh lâu dài cũng chưa được nối.
- Sửa: gắn đúng nguồn ảnh/dimensions/revision cả khi URI blank; nhập ảnh vào vùng lưu bền vững trước commit và chỉ mở khi save thành công. Test engine → start → repository → reader trên một và nhiều trang, sau dọn cache.

### R03 — P1: Đổi trang không cập nhật editor; có thể ghi chữ trang trước vào trang sau

- Vị trí: `ui/ocr/editor/OcrTextEditorFragment.kt:215`, `:87–93`, `:329`; `ui/ocr/editor/OcrTableEditorFragment.kt:100`; `ui/ocr/reader/OcrReaderViewModel.kt` hàm setPageIndex/updateCurrentPageText.
- Hai Fragment chỉ collect document, không collect/combine currentPageIndex. Chuyển trang chỉ đổi page index/ảnh, document không đổi nên editor còn nội dung trang trước.
- Text debounce giữ chuỗi cũ nhưng khi commit lại dùng currentPageIndex lúc đó. Flush onPause cũng ghi nội dung đang hiển thị vào trang hiện tại.
- Tái hiện dự kiến: tài liệu A/B → tab Văn bản ở trang A → gõ rồi bấm trang kế trong 300 ms, hoặc chuyển trang rồi rời màn hình → dữ liệu A có thể ghi vào B.
- Sửa: kết hợp document + page ID; pending edit phải gắn page ID/revision tại thời điểm gõ; flush trước chuyển trang. Kiểm instrumentation gồm cả trang có/không có bảng.

### R04 — P1: Sửa chữ lần hai nhưng DOCX vẫn xuất lần sửa đầu

- Vị trí: `ocr/edit/OcrEditReducer.kt:40–53`; `ocr/export/DocxWriter.kt:155`.
- ReplacePageText cập nhật editedContent.text nhưng chỉ tạo paragraphs ở lần đầu; lần sau giữ paragraphs/runs cũ. Writer ưu tiên paragraphs, vì vậy nội dung xuất khác màn hình.
- Tái hiện đã chạy: original → first edit → latest edit. resolvedText là latest edit, XML DOCX không chứa latest edit.
- Sửa: có một nguồn nội dung chuẩn, cập nhật text/runs đồng bộ và bảo toàn style theo quy tắc rõ. Test nhiều lần sửa, xóa, xuống dòng, undo/redo rồi export.
- Test: `secondTextEditMustReachDocx` FAIL.

### R05 — P1: Xóa hết chữ làm chữ OCR gốc xuất hiện lại

- Vị trí: `ocr/model/OcrDocument.kt:440–447`.
- resolvedText chỉ dùng editedContent khi text không rỗng; chỉnh sửa có chủ ý thành chuỗi rỗng bị coi như chưa sửa và fallback về nguồn OCR.
- Tái hiện đã chạy: ReplacePageText(original, "") → resolvedText vẫn bằng original.
- Sửa: phân biệt chưa có bản sửa và bản sửa rỗng; kiểm save/load/copy/export đều giữ trạng thái xóa, đồng thời sửa R04 để không xuất paragraphs cũ.
- Test: `deletingAllTextMustRemainEmpty` FAIL.

### R06 — P1: Bảng tạo thủ công hiện trên UI nhưng không vào snapshot xuất, có thể biến mất khi sửa ô

- Vị trí: `ui/ocr/reader/OcrReaderViewModel.kt:305–340`, `:executeCommand`, `:createExportSnapshot`; `ocr/edit/OcrEditHistory.kt`.
- createManualTable chỉ thay `_document`, không thay editHistory.currentDocument. Export dùng snapshot từ history, còn sửa ô cũng chạy reducer trên history cũ không có bảng.
- Test đã chạy: UI document có 1 bảng nhưng snapshot xuất có 0 bảng. Sửa ô tiếp theo có thể đưa document trở về bản history không có bảng.
- Sửa: tạo bảng là command có undo/redo trong cùng history; không cập nhật document ngoài nguồn chuẩn. Kiểm tạo → sửa ô → autosave → xuất → mở lại.
- Test: `manualTableMustReachExportSnapshot` FAIL.

### R07 — P1: Save cũ có thể ghi đè thay đổi mới và báo đã lưu

- Vị trí: `ui/ocr/reader/OcrReaderViewModel.kt:233–247`.
- saveDocumentInternal lấy snapshot rồi chờ IO; khi hoàn tất, thay `_document` bằng committedDoc và markCommitted toàn bộ history, không kiểm edit generation. CAS trên đĩa không bảo vệ sửa mới trong RAM.
- Test dùng preCommitFaultHook production seam để chèn một edit sau khi chụp snapshot: kết thúc flush làm văn bản mới bị thay bằng bản đang lưu. Dirty cũng bị xóa.
- Sửa: serialize các save, gắn edit-generation vào snapshot; completion chỉ cập nhật revision và đánh dấu sạch nếu không có sửa mới. Nếu có sửa mới phải giữ nội dung và lịch lưu tiếp. Không gọi runBlocking để flush trên Main khi onCleared.
- Test: `savingOlderSnapshotMustNotEraseNewEdit` FAIL. Đây là tái hiện interleaving có kiểm soát, chưa phải phép đo thời gian trên thiết bị.

### R08 — P1: Nhận diện lại không cập nhật nguồn dữ liệu reader/editor/export

- Vị trí: `ui/ocr/OcrResultActivity.kt:806–846`, `:891–916` và hàm performReRecognize.
- Kết quả mới được gán vào field của Activity/repository, nhưng không load/replace document trong ViewModel và editHistory; editor và exporter vẫn giữ bản cũ. Nhánh nhiều trang lấy aggResult.document có ID mới rồi save với expectedRevision của tài liệu cũ, dẫn đến conflict khi tài liệu mới chưa tồn tại.
- Cả nhánh conflict vẫn tới Toast convert_success. hasUserEdits của Activity cũng không được đồng bộ trực tiếp với lịch sử sửa của ViewModel.
- Sửa: giữ ID ổn định khi rerun, phối hợp flush/generation với ViewModel, chỉ publish revision sau save thành công; kiểm lựa chọn giữ/thay bản sửa và lỗi CAS không báo thành công.
- Cần integration/device test một/nhiều trang, có sửa và không sửa.

### R09 — P2: Analyzer bố cục và bảng chưa được nối vào luồng production

- Vị trí: `ocr/layout/DocumentLayoutAnalyzer.kt:40`, `ocr/table/TableStructureAnalyzer.kt:24`.
- Search toàn `app/src/main/java` chỉ thấy định nghĩa hai object, không có lời gọi từ pipeline OCR, Activity hay ViewModel. Logic có unit test nhưng quét tài liệu thật không chạy phân tích đó.
- Sửa: nối phân tích sau geometry và trước lưu hồ sơ; chuyển kết quả thành paragraphs/tables có provenance, tránh đè edits. Test từ OcrResult đến document được mở, không gọi trực tiếp analyzer rồi coi là end-to-end.
- Chưa thể công nhận S15/S16 hoàn thành theo luồng người dùng.

### R10 — P2: Định dạng bỏ qua vùng chọn và UI không cập nhật khi chỉ đổi style

- Vị trí: `ui/ocr/editor/OcrTextEditorFragment.kt:162–184`, `:222`.
- Có đọc selectionStart/End nhưng sau đó map mọi paragraph/run để đổi bold/italic/cỡ chữ. Observer chỉ buildSpannable khi text khác; style-only giữ nguyên text nên không hiển thị thay đổi ngay.
- Sửa: tách runs theo range, chỉ áp dụng vùng chọn/đoạn hiện hành; cập nhật spans dựa trên revision/style, giữ IME và selection. Test chọn một từ trong hai đoạn, format/undo không ảnh hưởng chữ khác.

### R11 — P2: DOCX không giữ gộp ô theo chiều dọc

- Vị trí: `ocr/export/DocxWriter.kt:252–271`.
- Writer chỉ xử lý colSpan, bỏ qua rowSpan và continuation cells. Bảng gộp dọc mất cấu trúc/có thể lệch cột trong Word.
- Test dùng một ô rowSpan=2: XML không có w:vMerge.
- Sửa: xuất đầy đủ grid/vertical merge và các ô tiếp diễn; test XML rồi mở bằng Word/LibreOffice.
- Test: `verticalMergeMustBeRepresentedInDocx` FAIL.

## Khoảng trống nghiệm thu khác

- UI bảng chưa có caller cho mergeCells, chỉ thấy định nghĩa trong ViewModel; editor hiện lấy firstOrNull nên bảng thứ hai trên cùng trang không có lựa chọn sửa. Chưa đủ phạm vi S18.
- Viewer decode tối đa 4096 và giữ cache theo số bitmap (3), chưa tải ROI theo zoom hay giới hạn cache theo byte. Không có căn cứ để khẳng định full-resolution/không OOM trên mọi tài liệu.
- DOCX writer không ghi ảnh/media nên phần ảnh trong phạm vi S19 chưa hoàn thành. Không nên mô tả đã bảo toàn mọi bố cục scan.
- Một số nhãn sửa bảng hardcode tiếng Việt; chưa đạt tuyên bố đa ngôn ngữ toàn diện.
- Các start overload dùng runBlocking và vẫn mở theo ID khi save lỗi bằng cách fallback về baseDoc. Cần đưa IO khỏi Main và không mở ID chưa lưu; kiểm disk full thay vì chỉ happy path.

## Vì sao báo cáo PASS toàn phần cần sửa lại

1. `OcrUserJourneyE2ETest.kt` mô phỏng đổi tab bằng gán biến enum và tự filter token, không khởi chạy Activity/Fragment. Các test ViewModel khởi tạo trực tiếp với repository/scope nên bỏ lọt R01.
2. `OcrReaderContractValidationTest.kt:95–110` so sánh groundTruthText với ocrHypothesisText trong JSON fixture. Không chạy engine OCR trên ảnh thật, nên không chứng minh CER 0% cho ứng dụng.
3. Test F1 bảng dùng tọa độ tổng hợp; analyzer chưa được gọi trong production. Không chứng minh nhận bảng đạt 95% sau quét.
4. Test exporter chủ yếu kiểm ZIP/XML/string/file size; chưa có bằng chứng mở thực trong các ứng dụng Office mà báo cáo liệt kê.
5. AAB trước audit vẫn có thời gian 22/09 17:31 và hash giống baseline. Giá trị delta AAB=0 trong báo cáo là artifact cũ; sau bundleRelease mới, delta là +130.442 byte.

## Đo dung lượng hiện tại

| Artifact | Baseline lưu sẵn | Candidate sau kiểm tra build | Delta |
|---|---:|---:|---:|
| APK release unsigned, 2 ABI | 75.347.472 | 75.471.221 | +123.749 byte |
| AAB | 44.641.170 | 44.771.612 | +130.442 byte |

APK candidate SHA256: `DF244CD4BE1E726D9362C7A12623B4ABB13C74B69AB4A003F651CDE24F6F91B5`.
AAB candidate SHA256: `68EAC594CCB6588B36BE165FF4422AEC7CAD07ECD0B07AEC04A73356FECA2BFB`.

Phép so APK với baseline đã lưu đạt trần 10 MB. Baseline manifest chỉ có HEAD và hash artifact, thiếu hash/diff working tree như kế hoạch yêu cầu, nên không tái dựng chắc chắn trạng thái source baseline. Bundletool không có: kích thước download theo thiết bị **NOT RUN**, không dùng kích thước AAB để thay thế. RAM, cache, thời gian trang đầu và corpus thật **NOT RUN**.

## Lệnh và bằng chứng tái hiện

Cache Gradle mặc định C:\.gradle không ghi được; đã chạy với C:\Users\nguye\.gradle bằng quyền công cụ được duyệt. Không sửa cấu hình Gradle của dự án.

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat -I build/ocr-audit/audit.init.gradle :app:testDebugUnitTest --offline --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure_ocr_size.ps1 -Json
```

- Tests audit: `build/ocr-audit/tests/com/tscanner/app/OcrIndependentAuditTest.kt`.
- Init script chỉ thêm source set khi truyền `-I`: `build/ocr-audit/audit.init.gradle`.
- Kết quả: `app/build/test-results/testDebugUnitTest/TEST-com.tscanner.app.OcrIndependentAuditTest.xml`.
- Đây là test chẩn đoán đúng hành vi mong muốn và đang FAIL; không sửa test để chấp nhận hành vi lỗi. File dưới build có thể bị xóa khi clean, cần chuyển thành regression tests chính thức khi giao sửa.

## Thứ tự khắc phục đề xuất

R01 → R02 → R03 → thống nhất nội dung/history để sửa R04/R05/R06 → bảo vệ generation khi lưu R07 → rerun R08 → nối analyzer R09 → style R10 và DOCX R11 → kiểm UI bảng/phạm vi còn thiếu → nghiệm thu thiết bị/Office/corpus và đo lại size.

Không sửa `final-acceptance.md` hay mã ứng dụng trong lượt audit; giữ báo cáo mới này làm kết quả đối chiếu độc lập. Trần dung lượng hiện chưa phải vấn đề chính; các lỗi chức năng và tính đúng đắn dữ liệu cần ưu tiên.
