# Kế hoạch chi tiết sửa 6 vấn đề P1 — Post-Scan Editor

**Trạng thái: đề xuất để phê duyệt, chưa triển khai mã nguồn.**

Tham chiếu: [Báo cáo kiểm tra](<E:/DU AN AI/T-Scanner/CODE_REAUDIT_POST_SCAN_2026-09-15.md>).

## 1. Mục tiêu và phạm vi

Khắc phục R01–R06 để bảo đảm:

1. Cắt/xoay giữ đúng nội dung đã chọn; preview và kết quả xuất thống nhất.
2. Trang đang hiển thị luôn khớp trạng thái chỉnh sửa, không dùng bitmap đã giải phóng.
3. Chỉ báo xuất thành công khi tất cả các trang hợp lệ.
4. Ghi file thất bại không phá bản tài liệu tốt trước đó.
5. Xử lý ảnh tuân thủ giới hạn bộ nhớ và báo rõ khi vượt khả năng.
6. Draft được ghi theo đúng thứ tự, không hồi sinh sau khi hủy.

Phạm vi gồm Editor, xử lý ảnh, lưu draft, tạo/cập nhật PDF và những điểm bàn giao sang Viewer cần thiết cho sáu mục tiêu này. Chỉ bổ sung xử lý lỗi, tiến độ và trạng thái cần thiết; thiết kế lại toolbar, lịch sử undo, danh sách draft, Drive, billing và các P2 độc lập để đợt sau.

**Ranh giới phụ thuộc:** việc xác nhận metadata đã lưu trước khi xóa draft có liên quan R15; bảo vệ nguồn trang trước cleanup có liên quan R10. Sẽ sửa tối thiểu hai điểm bàn giao này nếu cần để hoàn tất R03/R06, không mở rộng thành toàn bộ luồng khôi phục Viewer hoặc đồng bộ.

## 2. Thứ tự triển khai

| Bước | Vấn đề | Kết quả phải có trước bước kế tiếp |
|---|---|---|
| 0 | Chuẩn bị | Bộ tình huống tái hiện, fixture, giao diện kết quả/lỗi và baseline tài nguyên |
| 1 | R04 — ghi file | Cơ chế ghi/kiểm tra/thay thế file an toàn, kiểm thử lỗi IO |
| 2 | R06 — draft | Snapshot có revision, writer tuần tự, flush và đóng session |
| 3 | R01 — hình học | Một phép biến đổi tọa độ dùng chung cho preview/export |
| 4 | R02 — bất đồng bộ | Job theo trang/revision, quyền sở hữu bitmap rõ ràng |
| 5 | R05 — bộ nhớ | Giới hạn render, tính ngân sách ảnh, xử lý theo vùng khi phù hợp |
| 6 | R03 — xuất toàn bộ | Bàn giao bộ trang thành công cùng revision, lỗi thì giữ draft |
| 7 | Tích hợp | Regression và kiểm tra thiết bị; tổng hợp bằng chứng nghiệm thu |

R03 được chốt sau cùng vì cần sử dụng writer, hình học và xử lý ảnh đã sửa. Trong bước chuẩn bị có thể viết test để bắt lỗi dùng ảnh gốc ngay, nhưng không coi R03 hoàn tất chỉ bằng xóa nhánh fallback.

## 3. Bước 0 — Chuẩn bị trước khi sửa

- Ghi nhận trạng thái Git, giữ các thay đổi hiện có của người dùng; không reset hoặc ghi đè chúng.
- Chạy baseline unit test/lint/build khi bắt đầu thực hiện; kết quả kiểm tra gần nhất là 32 test đạt, lint 0 error/882 warning.
- Tạo fixture nhỏ có bốn góc đánh dấu A/B/C/D, lưới tọa độ và chữ Việt có dấu. Thêm ảnh EXIF các hướng, crop lệch tâm, nguồn hỏng và tài liệu ba trang phân biệt rõ.
- Tạo điểm thay thế giả lập cho đọc ảnh, ghi file và render: có thể trì hoãn một trang, ép lỗi trang thứ hai, ép lỗi ghi metadata và ngắt trước/sau commit.
- Dùng unit test cho hình học, revision, hàng đợi và điều phối lỗi. Dùng Android instrumentation cho Bitmap/PdfRenderer, hiển thị và lifecycle; không dùng Android stub trả giá trị mặc định để chứng minh xử lý ảnh đúng.
- Đo peak memory và thời gian với một vài ảnh đại diện trước/sau. Các mức dung lượng tính trong báo cáo là ước lượng, chưa phải baseline đo được.

## 4. R04 — Ghi file không làm hỏng bản cũ

### Thay đổi dự kiến

Các điểm chính: [PdfConverterHelper](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt:23>), [ImageProcessingEngine](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:317>), nơi gọi cập nhật PDF trong [PdfViewerActivity](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:55>).

1. Tách một thành phần ghi file dùng chung, tên dự kiến `SafeFileWriter`; nhận tác vụ tạo file tạm và hàm xác minh nội dung.
2. File tạm có tên duy nhất, ở cùng thư mục/file system với đích. Không xóa hoặc truncate đích trong lúc tạo file mới.
3. Ghi xong, flush/sync và đóng stream trước khi xác minh. Kiểm tra Boolean của encoder.
4. PDF phải mở được và có đúng số trang dự kiến. JPEG phải đọc được header/kích thước và giải mã kiểm tra có giới hạn; không chỉ kiểm tra `length > 0` và không decode toàn ảnh thêm một lần để xác minh.
5. Thay thế bằng thao tác rename có tính nguyên tử được hỗ trợ trên Android/file system mục tiêu; kiểm tra lỗi, không fallback sang `copyTo(overwrite=true)`. Kiểm thử phương án này trên API tối thiểu của app trước khi sử dụng chung.
6. Mỗi đường dẫn đích chỉ có một writer. Trả kết quả rõ: thành công, tạo file lỗi, xác minh lỗi, commit lỗi hoặc bị hủy.
7. Cleanup trong finally chỉ xóa file tạm thuộc lần ghi đó. Không được xóa file đích đã commit. Phục hồi/dọn file tạm sau process death phải phân biệt với file đang dùng.

### Kiểm thử và nghiệm thu

- PDF đích đã có nội dung A; ép lỗi khi tạo nội dung B ở từng giai đoạn: hash và khả năng mở bản A không đổi nếu commit chưa thành công.
- Tạo mới thất bại: không xuất hiện file đích giả thành công.
- Commit thành công: PDF mở được, đủ trang, không còn file tạm của lần ghi.
- Hai yêu cầu cùng đích được tuần tự hóa hoặc từ chối rõ ràng.
- Dừng process trước/sau commit: tồn tại bản A hoàn chỉnh hoặc bản B hoàn chỉnh, không có bản pha trộn. Kiểm tra mất điện đột ngột là bài kiểm tra riêng; không hứa độ bền vượt cơ chế lưu trữ đã xác minh.

## 5. R06 — Bản nháp có revision và một writer

### Thay đổi dự kiến

Các điểm chính: [PostScanSessionDraft](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/model/PostScanSessionDraft.kt>), [PostScanSessionRepository](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/data/repository/PostScanSessionRepository.kt:122>), [PostScanEditorViewModel](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:301>).

1. Thêm `schemaVersion` và `revision` vào draft; đọc JSON cũ với mặc định tương thích. Revision tăng khi nội dung chỉnh sửa hoặc tiêu đề thay đổi.
2. Mọi thay đổi trạng thái đi qua một điểm trong ViewModel, tạo snapshot bất biến ngay lúc sửa. Không đợi render thành công mới đánh dấu draft cần lưu.
3. Repository quản lý writer tuần tự theo session bằng hàng đợi và khóa theo phiên. Snapshot chưa ghi có thể gộp để giữ bản mới nhất; không cho revision nhỏ ghi sau revision lớn.
4. `AtomicFile` vẫn bảo vệ metadata, nhưng toàn bộ read/write/discard phải dùng cùng cơ chế đồng bộ. Đọc bằng đường phục hồi backup hợp lệ, không bỏ qua chỉ vì file chính vắng mặt.
5. API dự kiến: yêu cầu lưu snapshot; `flush(sessionId, revision)` chờ ghi ít nhất revision đó; `closeSession()` ngăn nhận thêm việc rồi chờ writer kết thúc; `discardSession()` xóa sau khi đã đóng.
6. Trạng thái session: **Đang sửa → Đang xuất → Đang sửa hoặc Đã hoàn tất**; Hủy là trạng thái kết thúc. Tác vụ thuộc phiên đã đóng không được gọi getter tạo lại thư mục.
7. UI phân biệt “Đang lưu”, “Đã lưu” và “Lưu nháp thất bại”. Chỉ báo “Đã lưu” cho revision đã ghi bền vững. Nếu process bị kill trước khi flush, chỉ bảo đảm khôi phục bản đã xác nhận lưu gần nhất.
8. Export dùng snapshot đã flush. Sau lưu PDF, chỉ complete session khi điểm bàn giao đã xác nhận tài liệu/metadata thành công và không còn tác vụ dùng nguồn.

### Kiểm thử và nghiệm thu

- Gửi revision 10, 11, 12 với trì hoãn IO: cuối cùng trên đĩa phải là 12; gọi ghi lại 10 không làm lùi dữ liệu.
- Đổi slider rồi đổi tên: khôi phục đúng cả hai từ snapshot đã flush.
- Ép lỗi metadata: UI không báo đã lưu, bản cũ vẫn đọc được, có thể thử lại.
- Hủy khi còn 20 yêu cầu lưu: sau khi hủy thành công, không còn metadata/ảnh thuộc phiên và không có job tạo lại thư mục.
- Đọc draft JSON cũ và phục hồi từ backup; không mất trang/tiêu đề vì thêm field mới.
- Không deadlock khi export/flush/discard xảy ra gần nhau; mỗi đường kết thúc phải có kết quả.

## 6. R01 — Thống nhất hình học cắt và xoay

### Quy tắc bắt buộc

- `cropRect` luôn thuộc ảnh nguồn đã chuẩn hóa EXIF, trong khoảng 0..1.
- Pipeline hình học thống nhất: **nguồn → chuẩn hóa EXIF → cắt theo nguồn → xoay người dùng → scale hiển thị/đầu ra**.
- Xoay 90° chỉ cập nhật góc xoay; không làm đổi phần nội dung nguồn đang được giữ.
- Một vùng chọn trên màn hình phải được biến đổi ngược về nguồn trước khi lưu.

### Thay đổi dự kiến

Các điểm chính: [NormalizedCropRect](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/model/NormalizedCropRect.kt>), [ViewModel rotatePage](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:183>), [Activity crop](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt:247>), [CropOverlayView](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropOverlayView.kt:83>), [ImageProcessingEngine](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt>).

1. Tách phép biến đổi dùng chung, tên dự kiến `PageGeometry`, có ánh xạ nguồn↔ảnh đã chỉnh↔màn hình. Preview và export dùng cùng quy tắc làm tròn/clamp.
2. Ánh xạ bốn góc vùng chọn qua phép biến đổi ngược; lấy bounds hợp lệ trong vùng nguồn hiện tại. Cắt lần hai phải ghép với crop trước.
3. Ví dụ: crop hiện tại là nửa trái nguồn; chọn nửa trái của preview đó thì crop mới là một phần tư bên trái nguồn, không phải tiếp tục giữ nửa trái.
4. Xử lý đủ hướng EXIF, gồm hướng phản chiếu; thống nhất cách đọc ảnh giữa preview và export. Không chuẩn hóa hai lần một file đã được chuẩn hóa.
5. Crop overlay giữ lựa chọn khi chỉ đổi trạng thái loading/render; chỉ reset khi người dùng yêu cầu hoặc đổi trang. Cập nhật bounds sau khi matrix hiển thị đã ổn định.
6. Với draft cũ, giữ rect/góc đã lưu làm trạng thái đầu vào và giữ hình ảnh mà engine cũ thực tế tạo ra; không tự đoán ý định crop trước đây hoặc xoay ngược rect hàng loạt. Các thao tác tiếp theo tuân theo quy tắc mới.
7. Góc được chuẩn hóa về 0/90/180/270; vùng cắt không tạo kích thước 0 pixel. Giới hạn nhỏ nhất phải được kiểm tra trên kích thước ảnh thực, không chỉ số thập phân.

### Kiểm thử và nghiệm thu

- Ảnh bốn góc: crop trái/phải/lệch tâm, xoay mỗi góc, xoay bốn lần và crop sau xoay.
- Hai/ba lần crop nối tiếp; reset crop; áp dụng style cho nhiều trang vẫn giữ hình học riêng từng trang.
- EXIF 8 hướng; ảnh vuông, ngang, dọc, kích thước lẻ, crop sát mép.
- Test unit cho ánh xạ và round-trip; test Android bằng pixel fixture để bắt lỗi tích hợp.
- So preview và full-res theo landmark/vùng nguồn và sai số resampling xác định; không đòi JPEG khác kích thước phải giống byte.
- Khi cùng trạng thái, PDF phải giữ đúng nội dung preview; độ lệch biên chỉ trong dung sai làm tròn đã quy định, không mất cả vùng chữ.

## 7. R02 — Điều phối đọc/render và quản lý bitmap

### Thay đổi dự kiến

Các điểm chính: [ViewModel loadCurrentPage](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:109>), [renderPreview](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:250>), [ImageProcessingEngine](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt>), [ZoomableImageView](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/view/ZoomableImageView.kt>).

1. Mỗi yêu cầu mang session ID, định danh trang, revision chỉnh sửa và request ID. Request ID được cấp trước decode, không chờ đến render.
2. Một pipeline quản lý decode→render→publish. Khi chọn trang/đổi chỉnh sửa: vô hiệu hóa request cũ ngay, hủy job; kiểm tra token sau decode, sau render và trước publish.
3. Không đọc lại `currentPageIndex` để ghép một bitmap vừa giải mã với trạng thái khác. Request giữ snapshot hình học/style của chính trang đó.
4. Chỉ request hiện hành được cập nhật ảnh/loading/error. Khi đổi trang, dùng placeholder hoặc trạng thái đang tải gắn đúng trang, không để ảnh trang cũ bị hiểu thành trang mới.
5. Quyền sở hữu bitmap:
   - Bitmap đã publish cho UI được xem là bất biến; không gọi recycle thủ công khi UI/collector còn có thể giữ nó. Giới hạn tham chiếu/cache và để hệ thống thu hồi khi hết dùng.
   - Bitmap trung gian chưa publish thuộc riêng job; giải phóng trong finally khi thành công, lỗi hoặc cancel.
   - Không trả bitmap alias rồi recycle dưới tên biến khác. Nếu dùng lại nguồn làm kết quả, quyền sở hữu phải được ghi rõ.
6. Vòng lặp thuật toán kiểm tra cancellation ở các đoạn xử lý hợp lý. Decode không hủy tức thì vẫn phải bị loại kết quả khi token đã cũ.
7. ROI phải chịu cùng cơ chế hủy và giới hạn; trong phạm vi P1, ngăn job ROI thừa hoặc bitmap bỏ quên. Hoàn thiện trải nghiệm hiển thị ROI của R09 là công việc riêng nếu chưa cần cho tính an toàn.

### Kiểm thử và nghiệm thu

- Giả lập A decode 500ms, B 50ms, C 100ms; chọn A→B→C: chỉ C được publish cuối cùng, bất kể thứ tự hoàn tất.
- Chuyển trang trong khi kéo slider/so sánh; không áp dụng style của B lên bitmap A.
- Lặp chuyển trang 100 lần và kéo slider 200 lần; không crash “recycled bitmap”, không có job cũ thay loading của job mới.
- Lỗi decode/cancel có thông báo hoặc trạng thái ổn định; không spinner vô hạn.
- Sau chuỗi thao tác, số job/cache/tham chiếu sống nằm trong giới hạn; bộ nhớ không tăng tuyến tính theo số lần chuyển trang.

## 8. R05 — Kiểm soát bộ nhớ từ ảnh đến PDF

### Thay đổi dự kiến

Các điểm chính: [PdfConverterHelper](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/PdfConverterHelper.kt:74>), [ImageProcessingEngine](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt:330>), [ImageProcessingAlgorithms](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/ImageProcessingAlgorithms.kt:278>).

1. **Preview PDF:** bỏ render cố định 2.5× kích thước page; tính scale từ viewport/độ phân giải đích và trần pixel. Một bitmap preview không được vượt giới hạn chỉ vì PDF khai báo trang rất lớn.
2. **Kích thước trang PDF:** tách đơn vị trang vật lý khỏi pixel ảnh. Giữ tỷ lệ ảnh, tôn trọng khổ A4 của ID card và kích thước trang đã có khi cập nhật; không tự biến mọi tài liệu thành A4 hoặc phóng lớn trang sau mỗi vòng sửa/lưu.
3. **Preview ảnh:** đặt trần cạnh/pixel rõ ràng sau sampling. Giới hạn cache theo số byte, không theo số trang đơn thuần.
4. **Ngân sách:** đọc kích thước trước decode; dùng phép tính Long có kiểm tra tràn để ước lượng bitmap đầu vào/đầu ra, mảng thuật toán, vùng đệm và ảnh UI đang giữ. Chỉ bắt đầu khi nằm trong ngân sách dành cho tác vụ, dựa trên heap khả dụng có phần dự phòng.
5. **Thuật toán:** xử lý theo dải/vùng và dùng lại buffer để giảm mảng toàn ảnh. Unsharp/adaptive threshold cần vùng biên mở rộng theo bán kính cửa sổ; chỉ ghi phần lõi để không tạo đường nối. Tính bán kính trên kích thước ảnh đầy đủ, không riêng kích thước tile.
6. **Giới hạn thực tế:** chia thuật toán thành tile chưa loại bỏ bộ nhớ của bitmap JPEG cuối cùng. Bản sửa này giữ full resolution khi đủ ngân sách. Nếu không đủ, dừng trước cấp phát và cho người dùng chọn mức xuất nhỏ hơn hoặc quay lại; không âm thầm giảm chất lượng hay quay về ảnh gốc.
7. Nếu yêu cầu bắt buộc xuất full-res 48MP trên máy ít RAM, cần đánh giá thêm encoder streaming hoặc đường xuất PDF theo vùng. Đây là quyết định kiến trúc bổ sung; không coi là đã giải quyết chỉ bằng BitmapRegionDecoder.
8. Tại một thời điểm chỉ chạy một tác vụ full-res nặng; tạm dừng render/ROI không cần thiết trong khi export. Export từng trang, giải phóng trước trang kế tiếp.
9. Không dùng `largeHeap` hoặc catch OOM làm giải pháp chính. Các lỗi tài nguyên có thể dự đoán phải trở thành kết quả lỗi có thể xử lý.

### Kiểm thử và nghiệm thu

- Ảnh 12/24/48MP, xoay EXIF, crop nhỏ/lớn, đen trắng và tăng nét; thử cả ảnh đơn và bộ 10 trang.
- PDF có page dimension lớn, tài liệu ID A4, PDF được crop/lưu lại nhiều vòng: preview không tăng kích thước vô hạn, khổ trang không đổi ngoài ý định chỉnh sửa.
- So đầu ra xử lý theo vùng với bản tham chiếu ảnh nhỏ: không có đường nối, không thay đổi radius theo tile.
- Đo peak memory, thời gian và dung lượng output trên thiết bị ít RAM và thiết bị phổ thông; ghi cấu hình máy trong biên bản.
- Với ảnh nằm trong ngân sách: hoàn tất, giữ mức chất lượng đã chọn. Với ảnh vượt ngân sách: báo trước, giữ draft và cho lựa chọn rõ; không crash, không tự nhận là full-res thành công.
- Ngưỡng byte/pixel cụ thể được chốt sau baseline và đo thiết bị ở bước 0; không đặt một con số MB tùy ý rồi coi là phù hợp mọi máy.

## 9. R03 — Xuất toàn bộ trang như một lần hoàn tất có kiểm tra

### Thay đổi dự kiến

Các điểm chính: [ViewModel exportFullResolutionPages](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt:317>), [Activity exportAndOpenViewer](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt:461>), [PdfViewerActivity](<E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:513>).

1. Chờ khởi tạo session hoàn tất; không cho xuất khi đang loading hoặc đã có export đang chạy.
2. Flush draft, lấy snapshot bất biến gồm session/revision, thứ tự trang, hình học, style và cấu hình chất lượng. Trong khi xuất, khóa thay đổi nội dung; một nút bấm không tạo nhiều export.
3. Thay `List<String>` đơn thuần bằng kết quả có kiểu, tên dự kiến `ExportResult`:
   - Thành công: export ID, session/revision, bộ trang đã xác minh.
   - Thất bại: trang lỗi, giai đoạn lỗi, lý do có thể hiển thị và khả năng thử lại.
   - Bị hủy: kết thúc rõ ràng và giữ draft.
4. Mỗi lần xuất có thư mục riêng; không ghi đè bộ output mà Viewer đang dùng. Ghi từng trang qua cơ chế R04.
5. Chỉ công bố bộ trang sau khi đúng số lượng/thứ tự, không có file thiếu/hỏng và tất cả cùng snapshot. Có manifest hoàn tất hoặc dấu commit tương đương để bỏ qua bộ dở dang sau process death.
6. Xóa nhánh fallback `resultPaths.add(state.inputImagePath)`. Mọi lỗi giữ nguyên draft, dọn riêng file tạm của lần xuất thất bại và cho thử lại.
7. UI hiển thị tiến độ theo trang. Thông báo ví dụ: “Không thể xử lý trang 2. Bản nháp vẫn được giữ. Thử lại.” Với thiếu tài nguyên, đưa lựa chọn chất lượng từ R05.
8. Export do ViewModel điều phối, Activity quan sát trạng thái thay vì giữ toàn bộ công việc trong dialog/lifecycleScope. Khi Activity được tạo lại, không tự chạy thêm một export. Khi process chết, lần khởi động sau nhận diện output dở dang và cho thử lại.
9. Viewer chỉ nhận bộ trang đã commit. Đánh dấu session hoàn tất và xóa bản gốc sau khi PDF, metadata và việc bàn giao nguồn đã được xác nhận; thất bại ở điểm lưu cuối vẫn giữ đường phục hồi.

### Kiểm thử và nghiệm thu

- Tài liệu ba trang: ép lỗi trang 2 khi decode, xử lý, encode hoặc commit; không mở Viewer với trang 1 chỉnh sửa + trang 2 gốc.
- Thử lại thành công: đúng ba trang cùng revision, không dùng file còn sót từ lần trước.
- Bấm Xem trước hai lần nhanh: chỉ có một lần export/điều hướng.
- Hủy hoặc recreate Activity lúc xuất: không dialog treo, không xuất trùng, không xóa draft.
- Lỗi lưu PDF/metadata sau khi đã xuất ảnh: không báo hoàn tất hoặc xóa bản gốc sớm.
- Trường hợp bình thường: crop/xoay/style của mỗi trang xuất đúng preview; PDF đủ số trang, giữ chất lượng đã chọn.

## 10. Tổ chức thay đổi và hồi quy

Chia thành sáu nhóm thay đổi có thể review riêng theo thứ tự R04 → R06 → R01 → R02 → R05 → R03. Mỗi nhóm gồm thay đổi production và test bắt lỗi tương ứng; không gom dọn cảnh báo lint không liên quan vào cùng nhóm.

| Nhóm kiểm tra cuối | Điều kiện đạt |
|---|---|
| Unit test hiện có và mới | Tất cả đạt; test mới phải bắt được hành vi lỗi cũ |
| Bitmap/PDF instrumentation | Hình học, số trang, giải mã và vòng đời bitmap đúng |
| IO failure injection | Không hỏng bản cũ, không báo thành công sai, retry được |
| Concurrency | Không nhầm trang/revision, không write sau discard, không export trùng |
| Lifecycle | Khôi phục bản đã flush, xử lý rõ output dở dang |
| Memory/device | Không vượt chính sách tài nguyên; ảnh vượt khả năng bị từ chối có kiểm soát |
| Build/lint | Debug và release build đạt; lint không phát sinh error mới |
| Hồi quy luồng chính | Quét ML Kit, Camera nhanh, nhiều trang, ID card A4, mở/sửa PDF vẫn hoạt động |

Không coi JVM test đạt là thay thế cho kiểm tra Android. Nếu chưa có thiết bị/emulator để đo hoặc kiểm tra lifecycle, ghi rõ mục chưa nghiệm thu và chưa kết luận P1 tương ứng đã đóng.

## 11. Sản phẩm bàn giao sau khi được phê duyệt

1. Mã sửa sáu nhóm P1, không lẫn các thay đổi tính năng ngoài phạm vi.
2. Bộ test hồi quy kèm fixture và các điểm giả lập lỗi.
3. Biên bản kết quả trước/sau, thông số thiết bị, peak memory và các giới hạn chất lượng xuất.
4. Bảng R01–R06: đã sửa / đã kiểm thử / còn hạn chế, liên kết tới phần code và kết quả kiểm tra.
5. Danh sách P2 còn lại để lên kế hoạch riêng.

**Phê duyệt kế hoạch này mới là cơ sở bắt đầu sửa. Việc tạo tài liệu kế hoạch không thay đổi mã nguồn ứng dụng.**
