# Kiểm tra lại T-Scanner và Post-Scan Editor — 15/09/2026

## Kết luận

**Chưa nên coi phiên bản hiện tại đã hết lỗi.** Ghi nhận 15 vấn đề cần xử lý: 6 vấn đề P1 và 9 vấn đề P2. Ưu tiên tính đúng của ảnh sau cắt/xoay, vòng đời bitmap, tính an toàn khi lưu, bản nháp và giới hạn bộ nhớ trước khi tinh chỉnh giao diện.

- P1: có thể làm sai nội dung tài liệu, mất chỉnh sửa, hỏng file hoặc gây crash trong luồng chính.
- P2: lỗi trong tình huống cụ thể, phục hồi phiên, đồng bộ hoặc trải nghiệm sử dụng.
- Các tình huống dưới đây được suy ra từ đường chạy trong mã nguồn; chưa được tái hiện trên điện thoại. Không đánh đồng build/test đạt với ứng dụng không còn lỗi.
- **Chưa sửa mã nguồn.** Chỉ tạo báo cáo và kế hoạch này; Gradle tạo/cập nhật các kết quả kiểm tra trong thư mục build.

## Phạm vi và kiểm tra đã thực hiện

Project Gradle gốc chỉ khai báo module `:app`, hiện có 66 file Kotlin production. Đợt này tập trung đọc và lần theo luồng Post-Scan Editor mới, Camera → Editor → Viewer → lưu PDF, repository, Drive, tài khoản/VIP, OCR, xuất file và đối chiếu các sửa đổi liên quan lỗi cũ. Không tuyên bố đã kiểm thử mọi nhánh của 66 file. Các project mẫu độc lập trong `android/`, `ios/`, `tutorials/` không thuộc build `:app`, chưa được build/kiểm thử riêng trong đợt này.

| Kiểm tra | Kết quả |
|---|---|
| `:app:assembleDebug --offline` | Thành công; các task build được Gradle xác nhận up-to-date |
| `:app:lintDebug --offline` | Chạy thành công, **0 error, 882 warning** |
| `:app:testDebugUnitTest --rerun --offline` | Chạy lại thực tế task test, **32 test, 0 failure, 0 error, 0 skipped** |
| Release, instrumentation, điện thoại/emulator, Drive thật | Chưa chạy |

8 lớp test hiện tại kiểm tra model, JSON, phép tính crop/ảnh dài/VIP và một số thuật toán pixel. Chưa có kiểm tra tích hợp cho bitmap Android, chuyển trang đồng thời, ghi file thất bại, phục hồi process hoặc đường đi Editor → Viewer. Test kiểm tra kênh màu bằng `and 0xFF` rồi khẳng định nằm trong 0..255 chưa đủ phát hiện lỗi chất lượng tăng nét.

Kết quả chi tiết: [Unit test](<E:/DU AN AI/T-Scanner/app/build/reports/tests/testDebugUnitTest/index.html>), [Lint](<E:/DU AN AI/T-Scanner/app/build/reports/lint-results-debug.html>).

## Các lỗi cần sửa

### R01 — P1: Cắt/xoay dùng không thống nhất hệ tọa độ

**Bằng chứng:** [Activity:247](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt:247>), [ViewModel:183](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:183>), [Engine:104](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:104>), [Engine full-res:345](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:345>).

- `cropRect` được định nghĩa theo ảnh nguồn đã chuẩn hóa EXIF; engine cắt ảnh nguồn trước rồi mới xoay. Nhưng `rotatePage()` lại xoay cả `cropRect`, khiến vùng nguồn được giữ thay đổi theo thao tác xoay.
- Ví dụ giữ nửa trái ảnh `(0,0,0.5,1)`, sau xoay 90° vùng cắt thành `(0,0,1,0.5)`: engine lấy nửa trên ảnh nguồn rồi xoay, thay vì xoay phần bên trái đã chọn.
- Khi cắt lần hai hoặc cắt sau xoay, Activity chuyển tọa độ trên ảnh đang hiển thị thẳng vào nguồn. Không có phép biến đổi ngược hoặc ghép với vùng cắt cũ.
- **Kế hoạch:** dùng một hệ tọa độ nguồn duy nhất; biến đổi vùng chọn trên màn hình về nguồn; chỉ đổi hướng hiển thị khi xoay. Bổ sung test chuỗi thao tác bằng ảnh có bốn góc khác nhau, crop lệch tâm, 90/180/270°, crop lặp và EXIF.
- **Nghiệm thu:** preview và ảnh xuất giữ đúng cùng một phần nội dung sau mọi chuỗi trên.

### R02 — P1: Chuyển trang có thể hiển thị nhầm ảnh và dùng bitmap đã recycle

**Bằng chứng:** [ViewModel:109](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:109>), [renderPreview:258](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:258>).

- Mỗi lần chọn trang tạo một coroutine đọc ảnh không được lưu/cancel. Nếu giải mã trang A kết thúc sau B, nó vẫn gán vào `baseInputBitmap`; `renderPreview()` sau đó lấy trạng thái của trang đang chọn B.
- `renderSequence` chỉ tăng sau khi đọc ảnh nên không chặn kết quả đọc cũ. Bitmap cũ còn có thể bị recycle khi ImageView hoặc một tác vụ render khác đang sử dụng.
- **Kế hoạch:** quản lý chung job đọc/render theo session + page ID + revision; hủy job cũ và loại kết quả cũ ở từng bước; quy định quyền sở hữu bitmap, không recycle bitmap còn được UI/job khác giữ.
- **Nghiệm thu:** chuyển nhanh A→B→C khi A giải mã chậm, kéo slider đồng thời, giữ/nhả so sánh; không nhầm ảnh, không crash và bộ nhớ ổn định.

### R03 — P1: Xuất ảnh thất bại nhưng âm thầm dùng ảnh gốc

**Bằng chứng:** [ViewModel:317](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:317>), đặc biệt dòng 329.

- Khi `processAndSaveFullResolution()` trả false, danh sách kết quả vẫn nhận `state.inputImagePath`. Activity chỉ kiểm tra danh sách không rỗng rồi cho tiếp tục lưu PDF.
- Khi ghi ảnh hết dung lượng hoặc xử lý thất bại, một số trang có thể mất crop/xoay/bộ lọc/tăng nét trong PDF mà người dùng không được báo.
- **Kế hoạch:** xuất từ snapshot bất biến; trả kết quả thành công/thất bại có số trang; chỉ chuyển Viewer khi mọi trang thành công. Giữ draft và cho thử lại khi lỗi, dọn output dở dang.
- **Nghiệm thu:** giả lập lỗi ở trang giữa; không mở Viewer với bộ trang trộn ảnh gốc/ảnh chỉnh sửa.

### R04 — P1: Ghi đè PDF chưa phải thao tác thay thế an toàn

**Bằng chứng:** [PdfConverterHelper:106](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt:106>), [ImageProcessingEngine:405](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:405>).

- Đã tạo file tạm và kiểm tra đủ trang, nhưng bước cuối vẫn `copyTo(outputFile, overwrite = true)`. Nó có thể cắt ngắn file đích rồi thất bại giữa chừng; không có khôi phục bản cũ.
- Viewer dùng đường này để cập nhật chính PDF đang lưu. Vì vậy sửa lỗi xóa PDF trước khi render mới chỉ giải quyết một phần.
- **Kế hoạch:** commit bằng cơ chế thay thế file trong cùng thư mục có khả năng bảo toàn bản cũ; kiểm tra kết quả và phục hồi khi commit lỗi; áp dụng thống nhất cho PDF và ảnh xuất. Không chỉ đổi comment thành “atomic”.
- **Nghiệm thu:** lỗi ghi, hết dung lượng hoặc dừng tiến trình ở bước commit không làm hỏng bản PDF tốt trước đó.

### R05 — P1: Đường ảnh lớn có mức cấp phát bộ nhớ quá cao

**Bằng chứng:** [PDF page size:74](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt:74>), [PDF render:136](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt:136>), [full-res:330](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:330>), [mảng thuật toán:278](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingAlgorithms.kt:278>).

- PDF được tạo với kích thước trang bằng số pixel ảnh, rồi render lại ở 2.5 lần mỗi chiều. Ảnh 4000×3000 có thể tạo bitmap preview 10000×7500, riêng ARGB_8888 cần **300 MB**; chưa tính renderer và các ảnh khác. Đây là tính toán dung lượng, chưa phải đo trên thiết bị.
- Xử lý đen trắng ảnh 12MP giữ bitmap, mảng pixel, mảng độ sáng và integral LongArray: xấp xỉ **240 MB** trước một số cấp phát đầu ra/phụ. Bản xuất full-res chưa có ngân sách bộ nhớ hoặc xử lý chia vùng.
- Catch `Exception` không xử lý được `OutOfMemoryError`. Tăng heap hoặc catch OOM đơn thuần không giải quyết nguyên nhân.
- **Kế hoạch:** quy định kích thước trang PDF theo kích thước vật lý phù hợp; render preview theo kích thước đích có giới hạn; dùng sampling/chia vùng có phần biên cho thuật toán; giới hạn số tác vụ ảnh lớn đồng thời và giải phóng ở finally.
- **Nghiệm thu:** ảnh 12/24/48MP, nhiều trang và PDF trang lớn trên thiết bị ít RAM; đo peak memory, không crash và không tăng độ phân giải vô ích sau mỗi lần sửa PDF.

### R06 — P1: Nhiều lần lưu draft có thể ghi đè nhau hoặc khôi phục trạng thái cũ

**Bằng chứng:** [ViewModel:143](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:143>), [persistDraft:301](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:301>), [repository:122](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/PostScanSessionRepository.kt:122>).

- Mỗi lần persist tạo coroutine IO riêng. Repository tạo `AtomicFile` mới nhưng không có khóa hoặc hàng đợi theo session. AtomicFile không tự sắp thứ tự các snapshot; bản cũ có thể ghi sau bản mới, các lần ghi có thể đụng cùng file tạm/backup.
- Discard/complete cũng không chờ các lần lưu đang chạy; tác vụ lưu muộn có thể tạo lại thư mục đã xóa. Kết quả Boolean lưu chưa được xử lý tại các nơi gọi.
- **Kế hoạch:** một writer tuần tự theo session, revision tăng dần, bỏ snapshot lỗi thời; flush có xác nhận trước chuyển trạng thái; đóng session và hủy/chờ mọi job trước discard/complete. Báo lỗi lưu cho UI.
- **Nghiệm thu:** kéo nhiều slider rồi đổi tên, đảo thứ tự hoàn thành IO, thoát/kill process, discard khi đang lưu; mở lại đúng revision cuối, không tái sinh draft đã hủy.

### R07 — P2: Thoát Editor chỉ xét trang hiện tại; draft không có đường mở lại rõ ràng

**Bằng chứng:** [Activity:443](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt:443>), [repository cleanup:168](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/PostScanSessionRepository.kt:168>).

- Sửa trang 1, chuyển sang trang 2 chưa sửa rồi Back: `anyModified` là false, Activity đóng ngay. Đổi tên cũng không được tính.
- Draft được lưu dưới filesDir nhưng chưa có luồng danh sách/tiếp tục draft sau khi người dùng đóng task. `cleanOrphanedDrafts()` chưa có nơi gọi trong production source đã tìm kiếm. Các phiên đóng trực tiếp có thể tích lũy.
- **Kế hoạch:** xét trạng thái cả tài liệu; định nghĩa rõ Tiếp tục sửa/Lưu nháp/Hủy; bổ sung đường mở lại draft và chính sách dọn theo metadata, bảo vệ phiên đang dùng.
- **Nghiệm thu:** sửa trang khác trang đang chọn vẫn được xử lý đúng khi thoát; bản nháp có thể tiếp tục sau đóng/mở ứng dụng; hủy thực sự giải phóng dữ liệu.

### R08 — P2: Nhập ảnh vào draft báo thành công ngay cả khi sao chép/lưu lỗi

**Bằng chứng:** [repository:76](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/PostScanSessionRepository.kt:76>), dòng 81 và 97.

- Copy lỗi chỉ ghi log. Chỉ cần file đích tồn tại là được chọn, kể cả file mới ghi dở; nếu không tồn tại thì quay lại đường dẫn nguồn tạm. Kết quả `saveDraft()` đầu tiên cũng bị bỏ qua.
- Người dùng có thể chỉnh sửa một phiên không có bản gốc bền vững hoặc metadata hợp lệ.
- **Kế hoạch:** nhập từng trang qua file tạm, xác minh đọc được và đầy đủ, commit toàn bộ session khi mọi trang/metadata thành công; trả lỗi có thể thử lại. Không sử dụng cache như bản gốc bền vững một cách im lặng.
- **Nghiệm thu:** nguồn thiếu/hỏng, copy bị ngắt, hết dung lượng; không mở một draft giả thành công.

### R09 — P2: Preview chi tiết khi zoom chưa hoạt động, chỉnh slider làm mất mức zoom

**Bằng chứng:** [ViewModel:290](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:290>), [ROI:205](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:205>), [ZoomableImageView:99](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/view/ZoomableImageView.kt:99>).

- Bitmap ROI được tạo nhưng không đưa lên màn hình và không được quản lý giải phóng. `targetWidth/Height` chỉ được kiểm tra >0, chưa dùng để hạn chế kích thước decode. Tọa độ ROI chưa biến đổi ngược theo crop/xoay/EXIF.
- Mỗi `setImageBitmap()` lại gọi fit-to-view. Đang zoom đọc dấu tiếng Việt mà kéo Tăng nét hoặc nhấn so sánh sẽ trở về toàn trang.
- **Kế hoạch:** hoàn thiện đường hiển thị ROI cùng phép ánh xạ tọa độ, giới hạn kích thước và vòng đời; giữ viewport khi chỉ đổi bộ lọc/cường độ/so sánh. Hủy ROI khi zoom out hoặc chuyển trang.
- **Nghiệm thu:** zoom 2–4× nhìn thấy dữ liệu chi tiết thực, giữ nguyên vị trí khi chỉnh nét; không tiếp tục giải mã ROI không còn dùng.

### R10 — P2: Viewer có thể mất nguồn trang sau lưu hoặc phục hồi Activity

**Bằng chứng:** [đọc Intent:137](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:137>), [dùng initialPages:213](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:213>), [cleanup:590](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:590>).

- Sau lưu, chỉ thay biến trong Activity; Intent vẫn ghi `isNewScan=true` và các đường dẫn draft đã xóa. Khi process bị hủy rồi Activity được khôi phục, onCreate có thể dùng lại các đường dẫn đó. Manifest tránh recreate ở một số thay đổi orientation nhưng không bảo vệ process death.
- Nếu render preview PDF mới thất bại và trả rỗng, code vẫn completeSession/xóa nguồn, dù adapter chưa chuyển sang nguồn mới. Hàm convertPdfToImages cũng có thể trả danh sách một phần khi lỗi giữa chừng.
- **Kế hoạch:** lưu trạng thái tài liệu đã commit bằng ID/path bền vững và phục hồi qua đó; chỉ cleanup sau khi mọi consumer chuyển nguồn an toàn. Preview thất bại phải có retry từ PDF bền vững, không giữ đường dẫn đã xóa.
- **Nghiệm thu:** lưu PDF → background → kill process → mở lại; giả lập render lỗi giữa trang; vẫn mở đúng tài liệu và đủ trang.

### R11 — P2: Bấm Hoàn tất trong lúc Camera chưa trả ảnh có thể thiếu trang cuối

**Bằng chứng:** [takePicture callback:257](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt:257>), [finishScanningSession:443](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt:443>).

- Finish chỉ chờ `activeCropJobs`, trong khi job chỉ được thêm sau `onImageSaved`. Nó không chờ `isCapturing`/yêu cầu chụp chưa callback.
- Khi đã có ít nhất một trang, chụp thêm rồi nhấn Hoàn tất ngay: có thể chuyển Editor bằng danh sách cũ trước khi ảnh cuối được thêm.
- **Kế hoạch:** tính cả capture đang chờ và crop đang chạy vào trạng thái phiên; khóa thao tác kết thúc cho tới khi nhận đầy đủ kết quả hoặc báo rõ capture thất bại.
- **Nghiệm thu:** làm chậm callback Camera, nhấn Hoàn tất ngay sau shutter; số trang và thứ tự vẫn đúng.

### R12 — P2: Dọn dữ liệu Drive giả chưa xóa được trạng thái giả

**Bằng chứng:** [cleanMockDriveBackups:95](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt:95>), [updateSyncStatus:394](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/DocumentRepo.kt:394>).

- Cleanup truyền null để xóa driveFileId nhưng setter dùng `driveFileId ?: doc.driveFileId`, nên ID giả vẫn còn. `isSynced = isSynced || doc.isSynced` giữ true cho bản ghi đã synced.
- Kết quả: tài liệu cũ có thể không được batch backup lại vì `getUnsyncedDocuments()` lọc `!isSynced`.
- **Kế hoạch:** tách “giữ giá trị” và “xóa giá trị” trong API; đặt lại cả trạng thái, isSynced, driveFileId và mốc đồng bộ trong migration.
- **Nghiệm thu:** nạp bản ghi `drive_mock_*`, chạy migration, kiểm tra ID trống và tài liệu được lên lịch backup thật.

### R13 — P2: Xóa tài liệu vẫn có thể báo thành công khi không xóa được file

**Bằng chứng:** [DocumentRepo:275](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/DocumentRepo.kt:275>).

- `deleteDocument()` loại bản ghi khỏi danh sách trước, bỏ qua kết quả `File.delete()`, rồi luôn trả true. `deleteManagedFile()` đã có kiểm tra nhưng luồng xóa tài liệu chính chưa đồng nhất.
- **Kế hoạch:** xử lý kết quả xóa từng file và metadata có chủ đích; nếu xóa thất bại, giữ khả năng theo dõi/thử lại và báo đúng. Giữ kiểm tra file được nhiều tài liệu tham chiếu.
- **Nghiệm thu:** giả lập delete thất bại; UI không báo đã xóa sạch và không để file rác mất dấu.

### R14 — P2: Đồng bộ danh mục Drive bỏ qua thay đổi của tài liệu đã tồn tại

**Bằng chứng:** [CloudBackupManager:126](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt:126>), [downloadDocument](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt:161>).

- Sync chỉ thêm ID chưa có; không đối chiếu modifiedTime/revision của ID đã tồn tại. Download trả ngay file local nếu có.
- Thiết bị A sửa nội dung một PDF đã đồng bộ, thiết bị B đã tải bản cũ: đồng bộ danh mục rồi mở lại trên B vẫn dùng bản cũ.
- **Kế hoạch:** đối chiếu phiên bản remote, invalidation cache và tải lại khi remote mới hơn; có quy tắc xung đột khi local cũng đang sửa. Đồng thời gắn revision cho upload và kiểm tra việc hủy/thay thế worker để tránh bản upload cũ thắng bản mới.
- **Nghiệm thu:** hai thiết bị sửa/tải cùng tài liệu, mất mạng rồi kết nối lại; không âm thầm dùng bản cũ hoặc mất bản mới.

### R15 — P2: Lỗi lưu danh mục không được truyền tới luồng hoàn tất tài liệu

**Bằng chứng:** [saveData:180](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/DocumentRepo.kt:180>), [addDocument:259](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/DocumentRepo.kt:259>), [Viewer cleanup:603](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:603>).

- `saveData()` bắt lỗi rồi chỉ log; `addDocument()` vẫn publish danh sách và không trả trạng thái commit. Viewer tiếp tục báo lưu thành công, xóa draft.
- Nếu ghi PDF thành công nhưng ghi JSON lỗi, tài liệu có thể hiện trong phiên hiện tại rồi biến mất khỏi danh mục sau khởi động lại; PDF có thể còn trên đĩa nhưng thành file không được quản lý đúng.
- **Kế hoạch:** repository trả kết quả commit; chỉ đánh dấu hoàn tất và cleanup sau khi metadata bền vững. Định nghĩa rollback hoặc phục hồi orphan khi PDF đã có nhưng metadata chưa ghi được.
- **Nghiệm thu:** giả lập metadata write failure sau khi PDF đã tạo; giữ khả năng thử lại và khôi phục tài liệu sau restart.

## Các điểm tính năng/giao diện cần hoàn thiện, chưa tính thêm vào 15 lỗi

1. **Crop overlay tự reset:** [setImageBounds:83](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropOverlayView.kt:83>) luôn tạo lại vùng 90%. Activity gọi lại khi cập nhật uiState/render. Cần giữ selection trong lúc người dùng thao tác, cập nhật bounds đúng sau khi matrix ảnh đã fit; xử lý cùng R01/R09.
2. **“Giảm bóng” hiện là chỉnh độ sáng theo từng pixel:** [thuật toán:223](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingAlgorithms.kt:223>) dùng cùng một công thức với “Làm sáng nền”, khác hệ số; vùng có độ sáng ≤90 không đổi. Chưa có ước lượng nền theo không gian. Cần thử ảnh bóng đổ thật để xác định mức đáp ứng; nếu giữ tên “Giảm bóng” nên triển khai xử lý nền phù hợp, bảo vệ chữ mảnh và dấu tiếng Việt.
3. **Thumbnail vẫn là ảnh đầu vào**, chưa phản ánh crop/xoay/bộ lọc. Cần cập nhật preview thu nhỏ theo revision sau khi render thành công.
4. **“Hoàn tác” hiện reset toàn bộ trang**, chưa có lịch sử từng bước. Nếu chỉ cần reset, đổi nhãn thành “Đặt lại trang”; nếu giữ yêu cầu undo thì thêm lịch sử thao tác có giới hạn.
5. **Bốn công cụ đã nằm cùng hàng**, nhưng label phụ 9sp khá nhỏ; chỉ có layout dọc và chưa có phương án thích ứng riêng cho màn hình thấp/chữ lớn. Cần kiểm thử 320/360dp, landscape, font 1.3×/2×; giữ icon + tên rõ ràng, chuyển mô tả phụ vào panel, tăng vùng chạm các nút nhỏ, cho panel cuộn khi thiếu chỗ.
6. **So sánh ảnh chỉ xử lý touch giữ/nhả**, thiếu hành vi click tương đương cho TalkBack/bàn phím. Cần bổ sung thao tác truy cập được và không làm mất zoom.
7. **VIP vẫn là dùng thử cục bộ 365 ngày**, có thể kích hoạt lại; UI đã ghi rõ thử nghiệm. Đây chưa phải hệ thống thanh toán/quyền lợi production; cần quyết định riêng trước phát hành thương mại, không coi việc đổi nhãn là đã có billing.
8. **882 cảnh báo lint:** nổi bật TypographyEllipsis 380, HardcodedText 102, SetTextI18n 83, ContentDescription 59, UnusedResources 56, SmallSp 17. Ưu tiên khả năng truy cập, chuỗi giao diện và tính đúng; không cần gộp toàn bộ cảnh báo hình thức vào đợt sửa P1.

## Những sửa đổi cũ đã được xác nhận trong code

| Hạng mục | Trạng thái hiện tại |
|---|---|
| File PDF trùng tên | Luồng lưu Viewer/ID card dùng UUID; download dùng document ID; có migration đường dẫn trùng |
| PDF thiếu trang đầu vào | Có kiểm tra đủ file và dừng nếu decode trang lỗi; commit cuối vẫn cần R04 |
| Camera trùng tên/thứ tự | Có bộ đếm tăng và map thứ tự; kết thúc lúc capture đang chờ vẫn cần R11 |
| JSON danh mục | Có AtomicFile và synchronized; cần truyền lỗi commit theo R15 |
| Tài liệu cloud-only bị mất khi load | Có giữ bản ghi có driveFileId |
| Mutable document làm DiffUtil bỏ sót | Repository dùng copy khi rename/move/update |
| Drive chỉ đọc trang đầu | Đã lặp nextPageToken |
| Backup nhầm chủ tài khoản | Đã thêm owner vào work và kiểm tra owner; chưa kiểm thử đổi tài khoản/Drive thật |
| ID card mất bố cục A4 khi mở | Lưu pagePaths rỗng và mở từ PDF đã ghép |
| Stream đích null khi lưu file | Helper đã kiểm tra null và trả thất bại |
| OCR text lớn chỉ giữ trong static cache | Đã chuyển text dài sang file, chỉ xóa khi Activity finishing; vẫn cần kiểm tra process death/cache eviction thực tế |
| VIP mất kỳ hạn khi gia hạn | Có lưu theo user ID và cộng từ max(now, expiry); vẫn là trial local |

Đây là xác nhận sửa đổi mã nguồn, không phải chứng nhận toàn bộ hành vi trên thiết bị hoặc dịch vụ bên ngoài.

## Kế hoạch sửa đề xuất — chỉ triển khai sau phê duyệt

### Đợt 1: Bảo toàn tài liệu và bản nháp

- R03, R04, R06, R08, R15: thống nhất kết quả thao tác có lỗi; writer có revision; commit file/metadata; quy tắc cleanup và retry.
- Viết kiểm tra có chủ đích cho IO lỗi, file thiếu/hỏng, metadata lỗi và các tác vụ ghi đảo thứ tự.
- Hoàn tất khi không còn trường hợp báo thành công nhưng dùng nội dung khác preview hoặc mất bản tốt trước đó.

### Đợt 2: Tính đúng của Editor và giới hạn tài nguyên

- R01, R02, R05, R09: tọa độ nguồn chuẩn, job theo revision, quản lý bitmap, preview có giới hạn, hiển thị ROI thực sự.
- Kiểm tra tích hợp bằng ảnh bốn góc, chữ Việt nhỏ, tài liệu nhiều trang, ảnh lớn; đo bộ nhớ và độ trễ trên máy thật.
- Hoàn tất khi preview/export thống nhất, chuyển trang không nhầm, zoom ổn định và không vượt ngân sách bộ nhớ đã đo.

### Đợt 3: Vòng đời phiên và Camera

- R07, R10, R11: điều kiện hoàn tất capture, lưu trạng thái tài liệu đã commit, khôi phục draft và xử lý thoát trên toàn tài liệu.
- Kiểm tra background/kill process, quay lại từ Viewer, discard khi có job đang chạy và thiếu trang preview.

### Đợt 4: Repository và Drive

- R12, R13, R14: migration mock đúng, kết quả xóa chính xác, đồng bộ phiên bản/tải lại cache và xử lý xung đột.
- Kiểm tra trên tài khoản thử nghiệm và hai thiết bị; thay đổi tài khoản trong lúc upload; xác nhận không lẫn dữ liệu/chủ sở hữu.

### Đợt 5: Chất lượng tính năng và giao diện

- Quyết định phạm vi giảm bóng, hoàn tác và cập nhật thumbnail.
- Hoàn thiện toolbar/panel trên màn nhỏ, landscape, chữ lớn và TalkBack.
- Dọn lint có chọn lọc; chạy lại unit test, lint và build debug/release cùng bộ kiểm tra thiết bị.

**Điều kiện chốt:** xử lý toàn bộ P1; kiểm thử các tình huống R01–R15 liên quan phạm vi phát hành; ghi rõ phần còn hoãn. Không tự triển khai kế hoạch này khi chưa có phê duyệt của người dùng.
