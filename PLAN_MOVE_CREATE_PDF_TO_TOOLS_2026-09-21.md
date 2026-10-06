# Kế hoạch chuyển "Tạo file PDF" sang Công cụ

Ngày khảo sát: 2026-09-21. Trạng thái: chỉ lập kế hoạch, chưa sửa mã ứng dụng.

## Mục tiêu

- Ô thứ 8 trên Trang chủ đổi từ **Tạo file PDF** thành **Tất cả**. Chạm ô này mở tab **Công cụ** và đánh dấu tab đó đang được chọn.
- Thêm **Tạo file PDF** vào nhóm **Chuyển đổi** trong tab Công cụ. Chạm mục này chọn nhiều ảnh và tạo tài liệu PDF với kết quả như lối vào cũ trên Trang chủ.
- Giữ các mục **Nhập ảnh**, **Công cụ PDF**, **Xem tất cả** ở phần tài liệu gần đây và **Tạo PDF** trong trình xem tài liệu hoạt động theo chức năng hiện có.

## Hiện trạng đã xác nhận

1. `app/src/main/res/layout/fragment_home.xml` có ô thứ 8 `btn_action_create_pdf` tại dòng 352–387; nhãn dùng `action_create_pdf`.
2. `HomeFragment.kt` dòng 254–258 mở bộ chọn nhiều ảnh của `imagePickerLauncher`; callback ở dòng 75–80 gọi `handleImportImages`. Hàm này (dòng 520–570) tạo PDF, thêm tài liệu và mở trình xem. Nút **Nhập ảnh** trên Trang chủ cũng dùng cùng launcher.
3. `fragment_tools.xml` dòng 247–435 đã có nhóm **Chuyển đổi**, lưới bốn cột và năm mục. Chưa có **Tạo file PDF**.
4. `ToolsFragment.kt` dòng 79–84 và 276–311 có luồng **Nhập ảnh** riêng. Luồng này tạo PDF nhưng khác luồng Trang chủ về cách lưu ảnh, kiểm tra kết quả, watermark và mở trình xem; không thể coi nút này là thay thế tương đương cho **Tạo file PDF**.
5. `MainActivity.selectTab(R.id.nav_tools)` (dòng 229–277) là cơ chế điều hướng có sẵn. Trang chủ đã dùng cùng cơ chế cho **Xem tất cả** tài liệu gần đây (dòng 270–273).
6. Hai nhãn **Nhập** và **Chuyển đổi** ở đầu tab Công cụ hiện chỉ đổi màu chữ khi chạm (`ToolsFragment.kt` dòng 216–225); cả hai nhóm vẫn hiển thị. Vì yêu cầu chỉ mở tab Công cụ, không cần thêm trạng thái lọc hoặc cuộn tự động.
7. `action_create_pdf` còn được `activity_pdf_viewer.xml` sử dụng. Cần giữ khóa chuỗi này, không đổi nghĩa thành **Tất cả**. Ứng dụng hiện khai báo tám ngôn ngữ trong `res/xml/locales_config.xml`.

## Thứ tự triển khai đề xuất

| Bước | Tệp dự kiến | Thay đổi |
| --- | --- | --- |
| 1 | `app/src/main/res/layout/fragment_home.xml`, `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt` | Đổi ô thứ 8 sang ID có nghĩa `btn_action_all_tools`, nhãn **Tất cả**, biểu tượng lưới/công cụ phù hợp; click gọi `MainActivity.selectTab(R.id.nav_tools)`. Bỏ toast chọn ảnh khỏi ô này. |
| 2 | `app/src/main/res/layout/fragment_tools.xml`, `app/src/main/java/com/tscanner/app/ui/tools/ToolsFragment.kt` | Thêm ô **Tạo file PDF** vào nhóm **Chuyển đổi**, với bộ chọn nhiều ảnh riêng và callback tạo PDF. Kiểm tra lưới năm thành sáu ô vẫn cân đối ở màn hình hẹp. |
| 3 | `HomeFragment.kt`, `ToolsFragment.kt`, và nếu cần một lớp dùng chung cho luồng ảnh thành PDF | Chuyển phần tạo PDF hiện đang dùng ở Trang chủ thành logic dùng chung để nút **Nhập ảnh** trên Trang chủ và nút **Tạo file PDF** trong Công cụ giữ cùng hành vi: chọn nhiều ảnh, tạo PDF có quy tắc watermark hiện tại, lưu tài liệu, thông báo kết quả và mở trình xem. Không nối nút mới vào `ToolsFragment.handleImportImages` khi chưa xử lý các khác biệt ở mục hiện trạng 4. |
| 4 | `app/src/main/res/values/strings.xml`, các `values-vi`, `values-es`, `values-pt`, `values-fr`, `values-in`, `values-de`, `values-ja` | Thêm khóa riêng cho ô **Tất cả** (`action_all_tools`) và bản dịch tương ứng. Giữ `action_create_pdf` cho vị trí mới và trình xem. Không dùng khóa `filter_all` vì khóa đó thuộc bộ lọc tập tin. |
| 5 | `app/src/main/res/drawable/` nếu cần | Thêm biểu tượng lưới cho **Tất cả**, hoặc chọn biểu tượng Công cụ hiện có nếu kích thước và ý nghĩa phù hợp. Bổ sung mô tả trợ năng cho hai ô mới/đổi nghĩa. |

## Kiểm tra và tiêu chí hoàn thành

1. Build `:app:assembleDebug` và chạy `:app:testDebugUnitTest`; chạy lint hoặc kiểm tra tài nguyên nếu có cảnh báo bản dịch/ID mới.
2. Kiểm tra trực tiếp trên thiết bị hoặc emulator: Trang chủ vẫn có tám ô; ô cuối ghi **Tất cả**; chạm ô này mở tab **Công cụ**, tab dưới được tô trạng thái đang chọn, không mở thư viện ảnh.
3. Trong **Công cụ > Chuyển đổi**, ô **Tạo file PDF** xuất hiện, chọn nhiều ảnh tạo một PDF, thêm tài liệu và mở được trình xem; hủy bộ chọn không tạo tài liệu.
4. Kiểm tra lại **Nhập ảnh** ở Trang chủ và Công cụ, **Công cụ PDF**, **Xem tất cả** của danh sách gần đây và nút **Tạo PDF** ở trình xem để tránh đổi nhầm hành vi hoặc nhãn.
5. Kiểm tra ngôn ngữ Việt, Anh và các ngôn ngữ đang khai báo; nhãn không tràn ở màn hình hẹp, TalkBack đọc đúng mục đích nút.

## Lưu ý phạm vi

Working tree đang có nhiều thay đổi chưa commit, gồm `HomeFragment.kt`, `ToolsFragment.kt`, `fragment_home.xml` và các tệp chuỗi. Khi được duyệt triển khai, chỉ chạm phần liên quan, xem diff trước/sau và không ghi đè thay đổi sẵn có.
