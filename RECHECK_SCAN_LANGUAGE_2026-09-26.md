# Kiểm tra lại sửa ngôn ngữ Quét — 2026-09-26

Phạm vi: kế hoạch PLAN_SCAN_UI_LANGUAGE_SMALL_MODEL_2026-09-26.md và implementation hiện tại L00-L04. Chỉ kiểm tra, không sửa production. Báo cáo L04 ghi triển khai Hướng B (giữ Google mặc định); đánh giá theo hướng này, không coi giữ Google là regression mới.

## Kết luận từ mã

### R01 — P2: Ghi chú và lựa chọn mới cho Quét tài liệu không tới được từ UI

- MainActivity.kt:177-193: FAB và startDocumentScan mở trực tiếp đích policy; default là Google (ScanUiPolicy.kt:75).
- HomeFragment.kt:254-262 và FilesFragment.kt:146 cũng mở trực tiếp; nhấn giữ FAB/Home chỉ mở camera ngay.
- MainActivity.kt:206-226 có showScanOptionsDialog(), nơi inflate dialog_scan_options chứa ghi chú ngôn ngữ mới. Tìm toàn cây main chỉ thấy định nghĩa, không có nơi gọi. Hai nơi dùng ghi chú là layout dialog_scan_options và dialog_id_card_options; ghi chú thẻ có đường mở, không khắc phục được đường quét tài liệu.
- Hậu quả: bấm Quét vẫn gặp UI Google khác ngôn ngữ mà không thấy giải thích/đường chọn hiển thị rõ như L02 dự định. Người dùng chỉ biết camera thay thế nếu tự biết thao tác nhấn giữ.
- Cách sửa đề nghị: nối dialog vào một thao tác hiển thị rõ hoặc hiển thị thông tin/chọn camera tại cửa vào phù hợp, giữ default Google theo hợp đồng B. Không tự đổi default sang camera.
- Regression cần có: thao tác UI thật mở được ghi chú/chọn camera từ luồng tài liệu; test chỉ tìm chuỗi trong XML không bắt được lỗi này.

### R02 — P2: Kiểm thử mới chưa kiểm chứng hành vi mà báo cáo mô tả

- androidTest/ScanEntryLanguageTest.kt:20-33 chỉ gọi policy enum và assertNotNull hai resources. Không bấm FAB/Home/Files, không launch/check Activity đích, không đổi locale, không kiểm tra chữ hiển thị.
- test/ScannerErrorLanguageTest.kt:43-135 chỉ đọc XML và đối chiếu chuỗi; không gọi DocumentScannerHelper dù có import. Không ép nhánh lỗi thực. Đưa hardcode cũ về các callback trong helper vẫn có thể giữ các test này xanh nếu giữ resources.
- test/ScanEntryLanguageRegressionTest.kt kiểm tra tài nguyên/layout và policy; không chứng minh dialog tới được từ UI. Vì thế R01 lọt qua 13 focused tests.
- Sửa đề nghị: thêm kiểm tra entry point thực và resource theo locale; kiểm tra callback/mapper production ở các nhánh start/parse/empty/import failure. Tránh fake chép lại implementation.
- Báo cáo L04 nên bỏ khẳng định instrumentation đã xác thực thao tác cửa vào; hiện chỉ có smoke test policy/resources. Đây là thiếu sót kiểm chứng, không phải bằng chứng lỗi mất dữ liệu.

## Phần đã cải thiện

- DocumentScannerHelper.kt:72,77,91,96,112,118,130,166 đã dùng helper gọi getString; 8 vị trí hardcode/exception nguyên văn trước đó đã được thay. Các helper dùng đúng nhóm resource và placeholder import.
- 8 khóa mới (2 ghi chú, 6 lỗi) có ở 8 bộ ngôn ngữ. Không thấy thiếu khóa mới qua validator.
- Không phát hiện trong phần sửa này việc dùng private API/đổi locale hệ thống để ép Google UI.

## Lỗi cũ còn nguyên, không phải regression mới

Chạy lại `python scripts/audit_localization.py`: 726 strings, 7 plurals, FAIL 12 nhóm: de/es/fr/in/ja/pt thiếu 48 khóa mỗi locale; vi thiếu 6; 5 nhãn OCR +H/+C/B/I/12pt. Các ký hiệu định dạng cần rà ý nghĩa trước khi kết luận phải dịch. Các nhóm này nằm ngoài phạm vi bản sửa scanner và báo cáo L04 cũng đã ghi còn tồn tại.

## Hạn chế/chức năng chưa nối, không nâng thành lỗi sản phẩm chắc chắn

- ScanUiPolicy.setPreferredScanner chỉ có định nghĩa trong production, không có callsite UI. Các lựa chọn camera hiện là một lần, không có cách lưu ưu tiên từ UI. Chỉ cần sửa nếu sản phẩm yêu cầu ghi nhớ; không coi là bắt buộc của Hướng B.
- Home thẻ gọi startIdCardScan theo preference nhưng Tools thẻ gọi startGoogleIdCardScan trực tiếp. Khi tương lai nối preference, cần phân biệt lựa chọn Google tường minh với default để tránh lệch nghĩa giữa các cửa vào.
- Các ghi chú khẳng định Google theo ngôn ngữ hệ thống, nhưng lần kiểm tra này không có thiết bị để xác nhận hành vi theo phiên bản Play services. Nên diễn đạt giới hạn ngôn ngữ do Google quản lý, có thể khác app, thay vì hứa hành vi tuyệt đối.
- Không có thiết bị/emulator trong `adb devices`; chưa nghiệm thu 8 locale, Android trước/sau 33, đổi ngôn ngữ và vòng đời camera.

## Kiểm tra host

- Cache mặc định bị Access denied; chạy bằng cache C:\Users\nguye\.gradle với quyền phù hợp đã vượt wrapper và compile.
- Lệnh: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline`.
- Tổng lệnh BUILD SUCCESSFUL in 3m 51s: lintDebug, assembleDebug và assembleDebugAndroidTest thành công. Đây không phải chạy instrumentation trên thiết bị.
- Lượt đầu unit test UP-TO-DATE; đã chạy lại thực sự bằng `:app:testDebugUnitTest --rerun --offline`, BUILD SUCCESSFUL in 1m 23s. XML sau lượt chạy: 906 tests, 0 failures, 0 errors, 0 skipped.
