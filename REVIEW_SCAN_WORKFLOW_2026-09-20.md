# Rà soát luồng quét tài liệu — 20/09/2026

Phạm vi: camera thường/Google scanner → nhập trang → editor/draft → crop → PDF/Viewer/lưu danh mục → OCR/nhận dạng lại. Chỉ kiểm tra; không sửa code sản phẩm. Mức P1 dưới đây là rủi ro mất nội dung hoặc mất khả năng truy cập tài liệu trong điều kiện nêu rõ, suy ra từ code hiện tại; chưa phải sự cố đã tái hiện trên thiết bị.

## Kiểm chứng

- `:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain`: BUILD SUCCESSFUL; 173 JVM tests, 0 failures/errors; lint 0 errors, 710 warnings.
- Localization validator PASS: 641 strings, 7 plurals, bảy bộ dịch + nguồn Anh. 12/12 unittest validator PASS.
- Các callsite Home/Files đã truyền ảnh nguồn OCR; màn hình nhận dạng lại có vòng lặp nhiều trang, hủy job và saved state. Hàm tách đoạn đã dùng Regex. Đây là kiểm tra nối code, không phải chứng nhận toàn bộ lỗi OCR cũ đã hết.
- `adb devices`: không có thiết bị. Chưa chạy camera thật, fault injection IO trên Android, lifecycle/process death, OCR ảnh thật, RAM thấp hoặc release AAB. Unit/build/lint không bao phủ các lỗi dưới đây.

## S01 — P1: Crop ghi đè trực tiếp ảnh nguồn, có thể phá ảnh khi ghi lỗi

**Bằng chứng:** `app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt:157–165` mở FileOutputStream trên imagePath, gọi compress rồi trả true mà không kiểm tra giá trị Boolean. Mở stream đã truncate file cũ; lỗi sau đó không phục hồi ảnh. Luồng này được dùng từ Viewer và ghép thẻ, khác đường export editor có SafeFileWriter.

**Tái hiện cần chạy:** crop một ảnh → giả lập lỗi ghi/hết dung lượng giữa lúc nén JPEG; kiểm tra ảnh đầu vào trước/sau. Trường hợp compress=false không ném exception vẫn có thể báo lưu thành công.

**Sửa/nghiệm thu:** ghi temp cùng thư mục, kiểm tra compress + validate ảnh, commit an toàn; thất bại giữ nguyên checksum file cũ và không RESULT_OK. Kiểm thử cả lỗi ghi, compress=false và thành công.

## S02 — P1: Google scanner âm thầm bỏ trang nhập lỗi

**Bằng chứng:** `utils/DocumentScannerHelper.kt:119–142` trả null khi openInputStream/copy lỗi và `awaitAll().filterNotNull()` bỏ trang đó; dòng 172 vẫn onSuccess. Camera chuyển danh sách không rỗng sang editor, không đối chiếu số trang scanner trả về.

**Tái hiện:** scanner trả ba trang; lỗi đọc URI trang hai → editor chỉ còn trang một và ba, có thể lưu PDF hai trang mà không báo thiếu.

**Sửa/nghiệm thu:** trả kết quả có số trang và lỗi theo index; một trang nhập thất bại phải chặn hoàn tất hoặc yêu cầu người dùng xác nhận bỏ trang rõ ràng. Test lỗi giữa danh sách, stream null, copy dở và hủy; không báo thành công với danh sách bị lọc âm thầm.

## S03 — P1: Chuyển PDF sang ảnh trả danh sách dở dang như kết quả thành công

**Bằng chứng:** `utils/PdfConverterHelper.kt:115–161` tích lũy imagePaths, catch chỉ printStackTrace rồi trả danh sách đã render được. Không kiểm tra compress Boolean. Home (`ui/home/HomeFragment.kt:363–373`) và Files chỉ kiểm tra pages.isEmpty, sau đó OCR/xuất Word trên danh sách đó.

**Tái hiện:** PDF nhiều trang, render/ghi JPEG lỗi ở trang giữa → OCR hoặc Word chỉ có các trang đầu. Aggregator OCR không biết còn trang chưa render nên không phát hiện thiếu.

**Sửa/nghiệm thu:** result có expectedPageCount và failedPage; validate mọi ảnh; chỉ trả success nếu đủ trang. Giữ PDF gốc, xóa output dở của chính tác vụ; lỗi trang N phải chặn xuất và hiển thị đúng trang. Không dùng empty/nonempty thay cho trạng thái hoàn tất.

## S04 — P1: Hoàn tất camera có thể bỏ lần chụp đang diễn ra

**Bằng chứng:** `ui/camera/CameraScanActivity.kt:240–260` đặt isCapturing trước takePicture nhưng activeCropJobs chỉ có sau onImageSaved. `finishScanningSession` (dòng 441 trở đi) chỉ đợi snapshot activeCropJobs; không đợi isCapturing/in-flight capture và không khóa nhận chụp mới. Nút Done vẫn khả dụng sau khi đã có ít nhất một trang.

**Tái hiện:** chụp xong trang một → chụp trang hai → bấm Done trước onImageSaved trang hai. Danh sách xuất chỉ có trang một; finish hủy lifecycle crop hoặc bỏ kết quả đến muộn.

**Sửa/nghiệm thu:** state machine cho capture→processing→ready, khóa shutter khi finishing, đợi tất cả capture đã nhận và xử lý tương ứng. Test callback chậm, Done trong capture, liên tiếp nhiều ảnh và ID-card tự hoàn tất.

## S05 — P1: Phiên camera không được khôi phục sau recreate/process death

**Bằng chứng:** `CameraScanActivity.kt:65–69` tạo UUID/session và danh sách mới trong field. onCreate không khôi phục session/page map; không có onSaveInstanceState hoặc owner bền vững cho phiên camera. Các crop job thuộc lifecycle Activity.

**Tái hiện:** đã chụp hai trang, chưa bấm Done → ra nền đổi ngôn ngữ hoặc hệ thống thu hồi process → mở lại. Bộ đếm và phiên mới không truy cập các ảnh đã chụp của phiên cũ. Không khẳng định ảnh vật lý bị xóa ngay; mất liên kết phiên cũng làm người dùng mất công quét.

**Sửa/nghiệm thu:** giữ sessionId, danh sách thứ tự và trạng thái capture/processing trong owner + draft bền vững; reconcile file hoàn tất sau phục hồi. Test recreate, process death và callback đến muộn; không chụp lại, không trùng/đảo trang.

## S06 — P1: Báo lưu thành công và xóa draft dù danh mục chưa lưu được

**Bằng chứng:** `data/repository/DocumentRepo.kt:180–230` saveData bắt lỗi ghi và không trả trạng thái; addDocument (252) cập nhật memory, gọi saveData rồi publish/enqueue backup. `ui/viewer/PdfViewerActivity.kt:612–651` tiếp tục completeSession/xóa temp và báo thành công mà không biết catalog commit thất bại.

**Tái hiện:** PDF tạo thành công nhưng AtomicFile catalog lỗi → UI vẫn thấy tài liệu trong RAM; restart không có record mới, draft đã bị xóa. PDF có thể còn trong filesDir nhưng người dùng không tìm được trong danh mục.

**Sửa/nghiệm thu:** trả kết quả commit rõ ràng; chỉ publish thành công/đóng draft sau ghi catalog bền vững. Rollback hoặc giữ trạng thái retry nhất quán, không nhân đôi document. Inject lỗi ghi catalog sau PDF success; restart vẫn có đường phục hồi và không báo thành công giả.

## S07 — P1: Bản nháp có thể báo đã lưu dù ảnh/metadata chưa bền vững

**Bằng chứng:** `data/repository/PostScanSessionRepository.kt:92–118` bỏ qua Boolean renameTo, fallback inputImagePath về nguồn temp khi copy lỗi, và bỏ qua saveDraft Boolean. `ui/editor/viewmodel/PostScanEditorViewModel.kt:116–143` nhận draft đó và đặt DraftStatus.SAVED. Export dòng 419 cũng bỏ qua kết quả flush; flush chỉ kiểm tra revision đang có, không tự chờ writer chưa vào mutex.

**Tái hiện:** lỗi sao chép ảnh vào raw_pages hoặc lỗi metadata khi tạo phiên → UI báo saved; sau process death/cache cleanup không khôi phục đầy đủ. Khi save revision lỗi, export vẫn tiếp tục dù nhãn lỗi sau đó nói draft an toàn.

**Sửa/nghiệm thu:** initialize trả success/error có cấu trúc; xác minh đủ ảnh nội bộ và metadata trước SAVED; không dùng temp source để giả thành công. Đợi job ghi đúng revision và kiểm tra flush trước hành vi cần draft bền vững. Fault injection từng bước và khởi động lại xác nhận phục hồi đầy đủ.

## S08 — P2: Lưu PDF xong vẫn xóa nguồn preview dù tạo preview mới thất bại

**Bằng chứng:** `ui/viewer/PdfViewerActivity.kt:617–633`: chỉ đổi renderedPagePaths nếu newPreviewPages không rỗng; completeSession/deleteTempSession vẫn chạy dù danh sách rỗng. Adapter cũ còn trỏ vào ảnh draft vừa xóa.

**Tái hiện:** tạo PDF và lưu danh mục thành công nhưng bước render preview mới lỗi → Viewer hiện tại/crop/OCR dùng đường dẫn đã bị xóa. PDF gốc mới vẫn có thể mở lại; không kết luận PDF đã mất.

**Sửa/nghiệm thu:** chuyển UI sang trạng thái PDF hợp lệ và bỏ adapter cũ, hoặc giữ session cho đến khi preview thay thế sẵn sàng. Kiểm tra đủ trang, không chỉ nonempty. Test lỗi preview sau save; viewer không giữ đường dẫn đã xóa.

## S09 — P2: Back trong editor chỉ xét trang đang mở

**Bằng chứng:** `ui/editor/PostScanEditorActivity.kt:447–464` biến anyModified lấy currentPageState.isModified. Sửa trang một → chọn trang hai chưa sửa → Back thoát không hỏi. Snapshot ghi bằng viewModelScope có thể bị hủy khi finish; không có chờ flush ở đường này.

**Sửa/nghiệm thu:** tính dirty của toàn phiên (kể cả title), định nghĩa rõ Lưu nháp/Hủy và chờ lưu nếu chọn giữ. Test sửa trang không đang chọn, đổi title và Back ngay sau edit. Không chỉ kiểm thử trang hiện tại.

## Thứ tự khắc phục

1. S01, S02, S03: bảo toàn ảnh và đủ trang xuyên suốt nhập/crop/PDF.
2. S04, S05: capture và phục hồi phiên camera.
3. S06, S07: chỉ báo lưu khi commit bền vững, giữ đường phục hồi.
4. S08, S09: Viewer và thoát editor.

Mỗi gói sửa riêng với test fault injection nêu trên. Các test mới phải kiểm tra kết quả bên ngoài (checksum, số trang, khả năng mở lại, danh mục sau restart), không chỉ mô phỏng hàm quyết định. Giữ mọi thay đổi chưa commit của người dùng.

## Các giới hạn cần kiểm tra thêm

- Tạo PDF/crop vẫn decode bitmap full-size; chưa đo khả năng chịu ảnh rất lớn, RAM thấp, hoặc peak memory khi lọc/rotate.
- Viewer dùng thư mục cache pdf_preview chung và xóa toàn bộ khi finish; cần kiểm tra nhiều Viewer/tác vụ đồng thời để tránh xóa preview của tác vụ khác.
- Không xác minh toàn bộ việc ghi đè an toàn trên mọi filesystem từ unit tests Windows. Không kết luận SafeFileWriter bảo đảm tuyệt đối chỉ từ tên hàm.
- Chưa kiểm tra Google Drive thực tế, thiết bị chụp ảnh, tài liệu có EXIF mirrored, hay chất lượng OCR/crop trên ảnh thật. Đây là phạm vi còn thiếu, không phải các lỗi runtime đã chứng minh.
