# Kế hoạch cải thiện tính năng Tăng nét

Ngày 2026-09-21. Phạm vi: trình chỉnh sửa sau quét của ứng dụng Android. Đây là kế hoạch; chưa sửa mã xử lý ảnh.

## Kết luận điều tra

- Thanh Tăng nét có thang 0–100. `PostScanEditorViewModel.updateSharpness` và `PageEditState` đều chặn giá trị vào thang này; bản nháp lưu đúng giá trị đó.
- `ImageProcessingAlgorithms.applyThresholdedUnsharpMask` chỉ có một tầng tăng cạnh: làm mờ hộp, lấy chênh lệch, bỏ qua chênh lệch dưới 3, nhân tối đa 2 lần ở mức 100 rồi chặn phần bù ở ±65. Tăng trần cường độ đơn thuần sẽ nhanh chóng gặp giới hạn ±65 và dễ tạo viền trắng/đen, nhiễu, chữ dính.
- Xem trước toàn trang đọc ảnh theo mẫu giảm kích thước (giới hạn decode xấp xỉ 1280 chiều dài). Ảnh xuất được xử lý từ nguồn ở độ phân giải đầy đủ rồi lưu JPEG chất lượng 95. Vì vậy cần đánh giá bằng ảnh xuất và vùng phóng đại; cảm nhận từ bản xem toàn trang không đủ kết luận.
- Ba đường xử lý toàn trang, vùng phóng đại và ảnh xuất cùng gọi thuật toán tăng nét, nhưng vùng phóng đại tính bán kính theo kích thước ROI. Phải kiểm tra độ tương đương khi cùng một nét chữ đi qua ba đường này.
- Bài kiểm thử hiện có chỉ xác nhận không tràn kênh màu; chưa đo khả năng đọc chữ, nhiễu, quầng sáng, thời gian và RAM. Chưa có kết quả thử trên tài liệu mờ thực tế.

## Mục tiêu và giới hạn

Mục tiêu là làm chữ và nét tài liệu rõ nhất có thể **trong phạm vi thông tin còn ở ảnh nguồn**, giữ độ trung thực của chữ số, dấu và nét mảnh. Ảnh mất nét do rung, sai tiêu điểm hoặc thiếu độ phân giải không thể được bảo đảm phục hồi hoàn toàn. Không dùng OCR để vẽ lại chữ lên ảnh gốc, vì có thể tạo nội dung sai. Bảo toàn hành vi mức 0 và bản nháp cũ.

## Thứ tự triển khai

1. **Lập bộ ảnh chuẩn và mốc so sánh.** Thu thập ảnh đã được phép sử dụng: chữ nhỏ, dấu tiếng Việt, bảng, giấy màu, ảnh nhiễu thiếu sáng, sai tiêu điểm nhẹ/nặng, nhòe chuyển động và ảnh rõ. Giữ cặp nguồn–kết quả ở mức 0/50/100 hiện tại, lưu crop 100%, file xuất, thời gian và đỉnh RAM trên máy yếu và máy thường. Với ảnh có văn bản chuẩn, đo tỷ lệ lỗi ký tự OCR chỉ như thước đo phụ; ưu tiên kiểm tra thủ công ký tự dễ nhầm.
2. **Cải tiến thuật toán trong thang 0–100 hiện có.** Thử tăng nét đa tỷ lệ trên kênh độ sáng: tầng nhỏ cho nét chữ mảnh và tầng lớn hơn cho cạnh mờ, dùng làm mờ Gaussian hoặc bộ lọc có đáp ứng tương đương. Ước lượng nhiễu cục bộ để giảm tác động tại nền giấy; giới hạn halo và độ dày nét. Giữ màu sắc/chroma ổn định. Mức 100 phải là mức hữu ích mạnh nhất đã được kiểm chứng, không phải hệ số lớn tùy ý. So sánh các biến thể trên bộ ảnh chuẩn trước khi chọn tham số.
3. **Xử lý ảnh mờ mạnh như phương án có điều kiện.** Nếu đa tỷ lệ vẫn không đủ, thử khử mờ có điều chuẩn với số vòng lặp giới hạn trên vùng chữ hoặc ROI. Chỉ đưa vào ứng dụng khi chứng minh rõ chữ hơn mà không sinh nét giả và đáp ứng thời gian/RAM; nếu không, giữ thuật toán ở bước 2. Tránh bắt buộc tải mô hình hoặc xử lý qua mạng trong đợt này.
4. **Đồng bộ xem trước và xuất.** Dùng cùng một cấu hình thuật toán tại `renderPagePreview`, `renderDetailRoi` và `processAndSaveFullResolution`. Tính bán kính theo tỷ lệ ảnh nguồn/độ phân giải thực thay vì chỉ theo kích thước vùng cắt; đọc thêm viền ROI bằng bán kính lọc rồi cắt trả lại để không có đường nối ở mép. Nếu bản xem toàn trang giảm mẫu che mất khác biệt, chỉ rõ rằng phóng đại cho thấy chi tiết thật, không nâng RAM bằng cách decode toàn ảnh.
5. **Giữ an toàn lưu và tương thích.** Không đổi thang slider hoặc dạng dữ liệu bản nháp nếu không cần. Nếu cần chế độ “Rất mạnh”, thêm phiên bản tham số vào trạng thái và ánh xạ bản nháp cũ sang kết quả cũ một cách rõ ràng. Tuân thủ kiểm tra ngân sách bộ nhớ và ghi file an toàn đang dùng trong đường xuất.

## Tệp dự kiến tác động

- `app/src/main/java/com/tscanner/app/utils/ImageProcessingAlgorithms.kt`: thuật toán, tham số, xử lý cạnh và nhiễu.
- `app/src/main/java/com/tscanner/app/utils/ImageProcessingEngine.kt`: ba đường render, tỷ lệ bán kính, viền ROI, ngân sách bộ nhớ và xuất.
- `app/src/main/java/com/tscanner/app/ui/editor/model/PageEditState.kt`, `app/src/main/java/com/tscanner/app/ui/editor/viewmodel/PostScanEditorViewModel.kt`, `app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt`, `app/src/main/res/layout/activity_post_scan_editor.xml`, chuỗi giao diện: chỉ sửa nếu kết quả thử đòi thêm chế độ hoặc mô tả mức mạnh.
- `app/src/test/java/com/tscanner/app/ImageProcessingPureMathTest.kt` và bài thử pipeline mới: kiểm tra nét chữ, nhiễu, halo, bảo toàn màu, nhất quán xem trước/xuất và tương thích bản nháp.

## Kiểm thử và tiêu chí nghiệm thu

- Đối chiếu trước/sau trên bộ ảnh chuẩn tại crop 100% và PDF/ảnh xuất. Các ảnh mờ nhẹ và vừa có nhiều ký tự đọc được hơn hoặc cạnh chữ rõ hơn theo đánh giá mù; không mất dấu, không nối hai nét riêng, không xuất hiện chữ/chi tiết giả. Ảnh nguồn đã rõ không bị giảm chất lượng đáng kể.
- Ở 0%, pixel đầu ra của bước tăng nét không đổi. Ở các mức khác, kết quả tất định, không tràn kênh, không lỗi trên ảnh rất nhỏ, ảnh trắng/đen, ảnh màu hay ROI sát mép. Đường xem vùng phóng đại và xuất cho chi tiết tương ứng cùng vị trí.
- Chạy unit test `:app:testDebugUnitTest`, build `:app:assembleDebug`, lint `:app:lintDebug`; thử trên máy Android thật với tài liệu nhiều trang, chuyển trang/kéo slider liên tục, lưu rồi mở lại, và xuất PDF. Ghi thời gian xử lý/đỉnh RAM; không chấp nhận treo UI, OOM hoặc lỗi xuất.
- Quyết định cuối cùng dựa trên ảnh so sánh và số đo. Nếu thuật toán khử mờ nâng cao không vượt mốc đa tỷ lệ rõ ràng, không đưa nó vào bản sửa.

## Ghi chú triển khai

Kho làm việc hiện có nhiều thay đổi chưa commit, trong đó có `activity_post_scan_editor.xml`. Trước khi sửa, tách phạm vi diff của phần Tăng nét và giữ nguyên các thay đổi khác của người dùng.
