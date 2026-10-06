# Kiểm tra T-Scanner sau khi chuyển Tạo file PDF

Ngày: 2026-09-21. Phạm vi: module Android `:app`, luồng Trang chủ → Công cụ → Tạo file PDF và các đường lưu/nháp liên quan. Đây là kiểm tra mã và bước tự động; chưa có thiết bị để chạy kiểm thử tương tác. Không sửa mã ứng dụng.

## Kết quả kiểm tra tự động

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`: **BUILD SUCCESSFUL**. Có 200 unit test, 0 lỗi, 0 bỏ qua; lint: 0 lỗi, 711 cảnh báo.
- Lần gọi Gradle đầu thất bại do wrapper cố ghi khóa ở `C:\.gradle`; chạy lại bằng cache người dùng `C:\Users\nguye\.gradle` với quyền cần thiết thì hoàn tất. Đây là lỗi môi trường của lần gọi đầu, không phải lỗi biên dịch ứng dụng.
- `adb devices`: không có thiết bị/emulator kết nối, nên chưa kiểm tra hành vi thực tế, giao diện màn hình hẹp, ảnh lớn hoặc gián đoạn I/O.
- `git diff --check` nêu một dòng trống thừa cuối `TextRecognitionHelper.kt`; đây là lỗi định dạng nhỏ, không phải lỗi chạy ứng dụng.

## Phần chuyển nút đã khớp yêu cầu

- `fragment_home.xml:354–383` đổi ô thứ 8 thành `btn_action_all_tools`, nhãn **Tất cả**. `HomeFragment.kt:256–258` gọi `selectTab(R.id.nav_tools)`.
- `fragment_tools.xml:266–299` thêm ô **Tạo file PDF** trong nhóm Chuyển đổi. `ToolsFragment.kt:89–93,259–262,290–294` mở bộ chọn nhiều ảnh và gọi luồng tạo PDF.
- Chuỗi `action_all_tools` có ở cả tám ngôn ngữ đang khai báo. Chuỗi `action_create_pdf` vẫn dành cho nút mới và trình xem PDF.

## Lỗi có bằng chứng trong mã

### F1 — Ưu tiên cao — Báo tạo PDF thành công khi lưu danh mục thất bại

`DocumentImportHelper.kt:88–89` gọi `repo.addDocument(item)` nhưng bỏ qua kết quả Boolean. `DocumentRepo.kt:254–278` trả `false` khi không ghi được dữ liệu. Helper vẫn trả `item`, báo thành công và mở trình xem ở dòng 96–104. Khi bộ nhớ đầy/lỗi ghi metadata, PDF có thể được tạo nhưng không xuất hiện trong danh sách sau lần mở ứng dụng sau, đồng thời tệp PDF bị bỏ mồ côi.

Kiểm thử cần có: mô phỏng `addDocument=false`; xác nhận không báo thành công, không mở trình xem theo trạng thái đã lưu, và có xử lý giữ lại hoặc dọn PDF rõ ràng.

### F2 — Ưu tiên cao — Bỏ qua ảnh lỗi và báo sai số trang

`DocumentImportHelper.kt:37–50` bỏ qua URI trả stream null, lỗi đọc hoặc tệp rỗng rồi tiếp tục với các ảnh còn lại. PDF chỉ chứa `tempPagePaths`, nhưng `DocumentItem.pageCount` tại dòng 83 và thông báo tại dòng 101 dùng `uris.size`. Nếu chọn ba ảnh mà một ảnh không đọc được, ứng dụng có thể báo ba trang dù PDF chỉ có hai, không báo thiếu ảnh cho người dùng.

Kiểm thử cần có: ba URI gồm một URI lỗi; xác nhận thao tác thất bại rõ ràng hoặc người dùng được báo thiếu ảnh và số trang luôn bằng số trang PDF thực tế. Trường hợp tất cả URI lỗi cũng phải có phản hồi.

### F3 — Ưu tiên vừa — Trạng thái tab sai sau khi Activity được hệ thống tạo lại

`MainActivity.kt:146–149` chỉ gọi `selectTab` khi `savedInstanceState == null`. `activity_main.xml:55–71,116–131` mặc định tô Trang chủ, trong khi FragmentManager có thể khôi phục tab Công cụ. Sau khi hệ thống tái tạo Activity tại tab Công cụ, nội dung Công cụ có thể hiện với thanh dưới vẫn tô Trang chủ.

Kiểm thử cần có: đang ở Công cụ, dùng tùy chọn nhà phát triển "Don't keep activities" hoặc thay đổi cấu hình gây tái tạo Activity, xác nhận nội dung và trạng thái thanh điều hướng trùng nhau.

## Rủi ro cũ còn thấy trong mã, cần thử lỗi chủ động

### R1 — Bản nháp đã hủy có thể bị writer đến muộn tạo lại

`PostScanSessionRepository.kt:215–233` đóng phiên và đợi lock, nhưng xóa `closedSessions` và `sessionLocks` ngay sau khi xóa thư mục. Một `saveDraft` bắt đầu muộn sau đó (`:159–168`) có thể thấy phiên mở lại và ghi metadata mới. `PostScanEditorViewModel.kt:397–404` tạo các job lưu không gắn với tombstone của phiên. Chưa tái hiện trên thiết bị trong lượt này.

### R2 — Ước lượng RAM khi xuất ảnh đen trắng có thể thấp

`ImageProcessingEngine.kt:350–355` cho phép theo 12 byte/pixel, nhưng nhánh full resolution giữ bitmap và mảng pixel (`:388,427–450`), còn thuật toán nhị phân tạo `IntArray` độ sáng và `LongArray` integral (`ImageProcessingAlgorithms.kt:288–301`). Tổng cấp phát đỉnh có thể vượt 12 byte/pixel, dẫn đến thiếu RAM khi xử lý ảnh lớn. Chưa đo trên thiết bị 12/24/48 MP.

## Thứ tự xử lý đề xuất

1. F1 và F2 trong helper tạo PDF, kèm test lỗi ghi và URI lỗi.
2. F3 khôi phục tab cùng trạng thái thanh dưới.
3. R1 bằng test race có kiểm soát; R2 bằng tính ngân sách đúng nhánh xử lý và đo RAM trên thiết bị.

Working tree đang có nhiều thay đổi chưa commit; không sửa hoặc ghi đè các tệp ứng dụng trong lượt kiểm tra này.
