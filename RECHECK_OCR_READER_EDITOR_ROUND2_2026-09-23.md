# Kiểm tra lại OCR Reader/Editor — vòng 2

Ngày: 23/09/2026. Chỉ kiểm tra, không sửa code ứng dụng. Đối chiếu với RECHECK_OCR_READER_EDITOR_2026-09-23.md và kế hoạch S00–S23.

## Kết luận

**Chưa đạt nghiệm thu chức năng.** Sáu test audit cũ đã pass, nhưng bảy ca mở rộng mới tái hiện sáu nhóm lỗi logic, trong đó có lỗi sửa nhầm nhiều trang và lỗi lưu dữ liệu. Một số luồng UI vẫn có rủi ro mất sửa theo đường đi code.

### Kết quả chạy thực tế

- Bộ test hiện tại: **379 tests, 0 failure, 0 error**, gồm sáu test audit cũ đã được chuyển vào app/src/test.
- Debug build/lint: thành công; lint **0 error, 724 warning**.
- Bảy ca mới trong `build/ocr-reaudit2/tests/com/tscanner/app/OcrSecondReauditTest.kt`: **7/7 FAIL tại assertion hành vi**, không phải lỗi biên dịch.
- Release APK/AAB build thành công. APK **75.487.605 byte**, tăng **140.133 byte** so với baseline lưu sẵn 75.347.472 byte; dưới 10 MB.
- AAB mới **44.781.022 byte**, tăng **139.852 byte**; kích thước file AAB không thay cho download theo thiết bị. Bundletool không có, phần đó NOT RUN.
- `adb devices` không có thiết bị. UI, IME, process death, RAM, corpus OCR thật và mở trong Office thực tế vẫn chưa được xác minh.

## Đối chiếu lỗi cũ

| Lỗi cũ | Kết quả vòng này |
|---|---|
| R01 constructor ViewModel | Đã có @JvmOverloads; test signature PASS. Chưa mở Activity trên thiết bị |
| R02 URI ảnh rỗng | Đã gắn URI/import ảnh trong happy path; lỗi save/import vẫn bị fallback im lặng, xem phần còn thiếu |
| R03 chuyển trang | Đã combine document/page và gắn pageIndex vào pending text; chưa hoàn tất an toàn vì N01 và N09 |
| R04 DOCX xuất lần sửa đầu | Ca cũ PASS; cách sửa tạo regression mất style/undo N05 |
| R05 xóa hết chữ | Model giữ chuỗi rỗng, test cũ PASS; copy/share của Activity vẫn giữ text cũ N08 |
| R06 bảng thủ công mất khỏi history/export | Ca cũ PASS, đã dùng AddTable command |
| R07 save ghi đè edit mới | Ca executeCommand cũ PASS; Undo/Redo và cancellation sau commit còn lỗi N02/N03 |
| R08 nhận diện lại | Đã giữ document ID, cập nhật ViewModel và không Toast thành công khi CAS lỗi; bảo vệ bản sửa đã autosave còn thiếu N07 |
| R09 analyzer chưa nối | Đã được gọi qua enrichPageWithLayoutAndTables; có lỗi ghép text/runs N04 |
| R10 vùng chọn/style | Đã chia run và cập nhật style signature; style mất khi gõ tiếp N05, pending UI cần instrumentation |
| R11 gộp dọc DOCX | Đã có vMerge restart/continuation; test cũ PASS, chưa xác minh Office thật |

Không diễn giải test cũ PASS thành mọi biến thể của lỗi đó đã được xử lý.

## Những lỗi còn lại, theo mức ưu tiên

Các đường dẫn code dưới đây tính từ `app/src/main/java/com/tscanner/app/`.

### N01 — P1: Mọi trang trả từ engine giữ pageIndex=1; sửa trang đầu ghi đè các trang khác

- **Vị trí:** `utils/OcrModels.kt:549–567`; đầu ra engine tại `utils/TextRecognitionHelper.kt:754`, `utils/TesseractOcrHelper.kt:187/:300`, `paddleocr/PaddleOcrEngine.kt:314`.
- Mỗi engine nhận một ảnh trả pageIndex=1. Aggregator dùng nguyên `p.pageDocument`, chỉ tự đặt pNum ở nhánh pageDocument null. Vì vậy đầu ra nhiều ảnh thật là [1,1,...].
- Reducer sửa bằng điều kiện page.pageIndex == command.pageIndex. Sửa trang 1 áp dụng lên tất cả các trang có index 1; sửa trang 2 không trúng trang nào.
- **Đã tái hiện:** expected [1,2], actual [1,1]; sau sửa first → edited first, trang second cũng thành edited first.
- Tests: `enginePagesMustBeRenumberedBeforeEditing`, `editFirstPageMustNotOverwriteSecondPage` đều FAIL.
- **Sửa cần thiết:** chuẩn hóa index theo vị trí khi aggregate, validate trước persist, ưu tiên pageId ổn định trong edit command. Test phải truyền pageDocument như engine thật, không chỉ dùng fallback text-only.

### N02 — P1: Undo/Redo trong khi lưu vẫn bị bản save cũ ghi đè

- **Vị trí:** `ui/ocr/reader/OcrReaderViewModel.kt:212`, `:243–262`, `:530–549`.
- executeCommand tăng editGeneration nhưng undo/redo không tăng. Save kết thúc vẫn thấy cùng generation nên publish committedDoc cũ và xóa dirty.
- **Đã tái hiện:** bắt đầu lưu text "edit", gọi undo tại preCommitFaultHook; sau flush text trở lại "edit" thay vì "original". History và UI/đĩa có thể lệch nhau.
- Test: `undoDuringSaveMustRemainVisibleAndDirty` FAIL.
- **Sửa:** mọi mutation, gồm undo/redo và thay document, phải tham gia cùng cơ chế generation/serialization. Commit chỉ đánh dấu sạch đúng snapshot; bổ sung test redo và replace-document trong lúc IO.

### N03 — P1: Hủy autosave sau commit làm revision trong RAM bị cũ, các lần lưu sau conflict

- **Vị trí:** `ui/ocr/reader/OcrReaderViewModel.kt:221–239`, `:245–269`; `ocr/data/OcrDocumentRepository.kt` phần commit → trả Success.
- Mỗi edit mới cancel autosaveJob. Nếu file đã commit nhưng coroutine chưa trả kết quả/cập nhật lastPersistedRevision, cancellation để đĩa ở revision mới còn ViewModel dùng expectedRevision cũ. Lần flush tiếp trả Conflict, không có bước reconcile.
- **Đã tái hiện bằng barrier:** postCommitFaultHook báo commit xong rồi tạm chờ; gõ edit mới hủy job; bỏ hook và flush. Flush trả false thay vì lưu latest edit.
- Test: `canceledAutosaveAfterCommitMustRecoverRevision` FAIL. Đây là mô phỏng có kiểm soát đúng cửa sổ commit/cancellation, không phải báo lỗi theo thời gian ngẫu nhiên trên thiết bị.
- **Sửa:** chỉ cancel debounce trước IO, không hủy giao dịch đang commit; hoặc bảo đảm handoff revision sau commit và reconcile khi trạng thái commit không chắc chắn. Không bỏ CAS bằng save vô điều kiện.

### N04 — P1: Ghép bố cục mới làm dính chữ giữa các dòng

- **Vị trí:** `utils/OcrModels.kt:661–676`; `ui/ocr/editor/OcrTextEditorFragment.kt` buildSpannable; `ocr/export/DocxWriter.kt` buildParagraphXml.
- aPara.text có dấu xuống dòng, nhưng runs chỉ là danh sách l.text không có separator. UI và DOCX nối runs trực tiếp.
- **Đã tái hiện:** paragraph text là "Hello world\nNext line", runs dựng ra "Hello worldNext line". Khi editor flush, bản hiển thị dính chữ có thể được lưu trở lại dữ liệu.
- Test: `layoutParagraphRunsMustPreserveWordBoundaries` FAIL.
- **Sửa:** thống nhất text với runs; biểu diễn khoảng trắng/ngắt dòng có chủ đích và export bằng markup ngắt dòng đúng; test đối chiếu paragraph, text nhìn thấy và XML output. Không chỉ kiểm fullText.

### N05 — P2: Gõ thêm chữ xóa định dạng; Undo không khôi phục được định dạng cũ

- **Vị trí:** `ocr/edit/OcrEditReducer.kt:43–54`; `ocr/edit/OcrEditCommand.kt` ReplacePageText/invert.
- Bản sửa R04 dựng lại mọi run thành OcrTextRun mặc định sau mỗi ReplacePageText. Bold/italic/cỡ chữ mất, kể cả đoạn không đổi; command chỉ lưu oldText nên undo không lấy lại style.
- **Đã tái hiện:** text bold 18pt → gõ thêm ! → undo: chữ phục hồi nhưng thành normal 11pt.
- Test: `undoTextEditMustRestorePriorFormatting` FAIL.
- **Sửa:** lưu snapshot rich content trước/sau hoặc edit theo range giữ style; undo/redo phải hoàn nguyên toàn bộ nội dung và định dạng, không chỉ chuỗi.

### N06 — P2: Xóa hàng/cột cắt qua ô gộp tạo bảng không hợp lệ

- **Vị trí:** `ocr/edit/OcrEditReducer.kt:202–227`, `:269–291`; ViewModel deleteTableRow/deleteTableColumn.
- Delete chỉ loại cell có hàng/cột bắt đầu trùng vị trí xóa, rồi dịch origin. Không giảm rowSpan/colSpan của ô bắt đầu ở trước vùng xóa.
- **Đã tái hiện:** bảng 2 hàng có ô rowSpan=2; xóa hàng 2 → rowCount=1 nhưng rowSpan vẫn 2; validateGrid báo out of bounds.
- Test: `deletingRowThroughMergedCellMustKeepValidGrid` FAIL.
- **Sửa:** cập nhật topology khi insert/delete xuyên merge, giữ nội dung và undo; validate grid trước commit/export. Cần kiểm cả xóa hàng chứa origin, cột và undo.

### N07 — P1, đọc code: Nhận diện lại có thể xóa bản sửa đã được autosave mà không hỏi

- **Vị trí:** `ui/ocr/OcrResultActivity.kt:690–701` và mọi assignment của hasUserEdits.
- Guard chỉ xét hasUserEdits hoặc isDirty. hasUserEdits không được set true khi sửa; autosave thành công đặt isDirty=false. Vì vậy người dùng sửa → đợi tự lưu → nhận diện lại sẽ đi thẳng vào overwrite.
- **Sửa:** tách "có bản sửa người dùng" khỏi "có sửa chưa lưu", persist dấu này và hỏi giữ/thay trước rerun; flush pending IME trước quyết định. Kiểm sau autosave, reopen và process death.
- Chưa chạy UI thật; đây là kết luận từ guard và đường publish hiện tại. Rerun vẫn dùng currentImagePath/currentImagePaths cũ thay vì nguồn ảnh bền vững trong document, cần kiểm trường hợp cache nguồn đã dọn.

### N08 — P2, đọc code: Xóa sạch tài liệu nhưng Copy/Share vẫn gửi nội dung cũ

- **Vị trí:** `ui/ocr/OcrResultActivity.kt:377–380`, `:239–265`.
- Observer chỉ cập nhật extractedText khi fullText.isNotBlank(). Sau khi toàn bộ nội dung thành rỗng, biến này giữ bản trước. Nút Copy không chọn vùng và Share text dùng biến cũ.
- **Sửa:** luôn đồng bộ cả chuỗi rỗng, lấy snapshot chuẩn và flush pending editor trước action. Test xóa sạch → copy/share/export, tránh hồi sinh nội dung đã xóa.
- Model-level R05 đã sửa nhưng chưa đủ cho luồng UI này.

### N09 — P1, đọc code cần instrumentation: Khôi phục màn hình đang ở trang 2 có thể flush editor rỗng vào trang 1

- **Vị trí:** `ui/ocr/editor/OcrTextEditorFragment.kt:46`, `:286–288`, `:400–405`.
- Fragment mới luôn currentObservedPageIndex=1. Khi collect document cùng pageIdx=2 đã khôi phục, nó gọi flushPendingTextEdit trước khi bind dữ liệu lần đầu. Flush không kiểm editor đã được bind/dirty, nên chuỗi rỗng của EditText mới có thể trở thành lệnh xóa trang 1.
- **Tái hiện cần chạy:** tài liệu ≥2 trang, ở trang 2 → xoay máy hoặc recreate Activity → kiểm nguyên vẹn trang 1, cả khi tab Văn bản đang ẩn.
- **Sửa:** chỉ flush một pending edit thật đã gắn document/page ID sau khi bind; lần bind đầu không được tạo mutation. Không dùng giá trị page mặc định làm target ghi.

## Các phần vẫn chưa hoàn thiện

- start overload vẫn runBlocking để nhập ảnh/lưu JSON; save/import lỗi vẫn fallback và có thể mở documentId chưa persist (`OcrResultActivity.kt:1119–1124`, `:1209–1214`). Đây là lỗi xử lý IO của vòng trước chưa khắc phục đầy đủ.
- prepareDocumentForExport bỏ qua Boolean của flushPendingSaves. Cần định nghĩa rõ cách xử lý lỗi lưu trước xuất, không xem flush đã thành công vô điều kiện.
- initialize/loadDocument vẫn reset editHistory mà không bảo vệ lần gọi lặp hoặc mutation đang lưu. onCreate gọi initialize lại dù ViewModel có thể được giữ qua recreation; cần kiểm không mất draft/undo.
- Chọn bảng thứ hai, UI gộp ô, DOCX ảnh/media, ROI zoom, cache theo byte và nhãn dịch vẫn còn các khoảng trống đã nêu trong báo cáo vòng 1. Không có bằng chứng mới để công nhận các phần đó hoàn thành.
- Báo cáo `docs/ocr-reader/final-acceptance.md` vẫn ghi PASS toàn phần R01–R11. Cần thay bằng kết quả từng ca và NOT RUN cho device/corpus/Office; không dùng test fixture JSON để khẳng định độ chính xác OCR thực tế.

## Bằng chứng và cách chạy lại

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
# Bộ test chính thức, đã có 6 ca audit cũ:
.\gradlew.bat :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain
# Chỉ nạp source audit vòng 2; không nạp lại audit.init.gradle vòng 1:
.\gradlew.bat -I build/ocr-reaudit2/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.OcrSecondReauditTest --offline --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure_ocr_size.ps1 -Json
```

- Source test mới: `build/ocr-reaudit2/tests/com/tscanner/app/OcrSecondReauditTest.kt`.
- Bản sao XML kết quả: `build/ocr-reaudit2/results.xml`.
- Lần đầu dùng init vòng 1 gặp duplicate class vì test đã chuyển vào app/src/test; đã bỏ init đó và chạy lại bộ chuẩn thành công. Đây là lỗi cấu hình chạy audit, không phải lỗi compile của ứng dụng.
- Baseline vẫn là artifact lưu sẵn; không tạo/ghi đè baseline. Thiếu manifest diff/hash working tree baseline và thiếu download measurement như vòng trước.
- File audit nằm dưới build có thể bị clean; khi sửa nên chuyển thành regression test chính thức với tên tránh trùng.

## Ưu tiên tiếp theo

N01 → N02/N03 → N09 → N04 → N07/N08 → N05/N06 → lỗi IO và phần UI còn thiếu. Sau đó mới nghiệm thu thiết bị/Office/corpus và đo lại dung lượng. Sáu ca cũ PASS là tiến bộ thực, nhưng chưa đủ để kết luận hết lỗi.
