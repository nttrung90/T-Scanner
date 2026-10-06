# Kế hoạch sửa ngôn ngữ màn hình Quét — 2026-09-26

## 1. Phạm vi và trạng thái

Yêu cầu: T-Scanner chọn ngôn ngữ khác tiếng Việt nhưng mở Quét vẫn thấy tiếng Việt như ảnh. Chỉ điều tra và lập kế hoạch; chưa sửa production. Checkout có nhiều thay đổi staged/unstaged/untracked; không reset, checkout hay ghi đè chúng. Các số dòng dưới đây là checkout tại ngày kiểm tra.

Mục tiêu nghiệm thu: đường Quét mặc định có giao diện theo ngôn ngữ UI đã chọn, không tự đổi ngôn ngữ thiết bị, không nhầm ngôn ngữ OCR với ngôn ngữ UI. Nếu vẫn sử dụng giao diện Google không điều khiển được locale, phải báo rõ giới hạn; không đánh dấu đã sửa hoàn toàn.

## 2. Kết quả điều tra

### F01 — P2: Đường Quét mặc định đi qua giao diện SDK ngoài quyền quản lý tài nguyên của ứng dụng

- `app/src/main/java/com/tscanner/app/MainActivity.kt:175-198`: bấm FAB gọi `startGoogleAiScan()`, `startDocumentScan()` cũng gọi hàm này. Nhấn giữ mới mở `CameraScanActivity` qua `startFastDocumentScan()`.
- `app/src/main/java/com/tscanner/app/utils/DocumentScannerHelper.kt:32-90`: dùng `GmsDocumentScanning.getClient`, `getStartScanIntent(activity)` và `IntentSenderRequest`; cả quét tài liệu và thẻ đều dùng SDK.
- `app/src/main/java/com/tscanner/app/utils/AppLanguageManager.kt:655-709`: áp dụng application locales qua LocaleManager/AppCompat. Không có hợp đồng đồng bộ ngôn ngữ với UI bên ngoài.
- `app/build.gradle:77`: SDK `play-services-mlkit-document-scanner:16.0.0`.
- Không tìm thấy các câu “Đang quét”, “Chụp thủ công”, “Tự động chụp”, “sẽ chỉ truy cập” trong cây `app`. Layout `activity_camera_scan.xml` có Auto Crop/AI Scan/Done, khác giao diện ảnh. Bằng chứng ảnh và đường gọi khớp mạnh với UI Google.
- Các khóa camera tiêu biểu `camera_auto_crop`, `camera_ai_scan`, `camera_hint_default` đã có trong cả 8 bộ ngôn ngữ. Thêm bản dịch các câu trong ảnh vào resources T-Scanner không thay đổi UI do SDK cung cấp.

Tài liệu chính thức được kiểm tra ngày 2026-09-26:

- [ML Kit document scanner](https://developers.google.com/ml-kit/vision/doc-scanner/android).
- [Builder API](https://developers.google.com/android/reference/com/google/mlkit/vision/documentscanner/GmsDocumentScannerOptions.Builder): các tùy chọn công khai gallery/page limit/result format/scanner mode, không có setter locale/language.
- [Google mô tả UI được phân phối qua Play services](https://android-developers.googleblog.com/2024/02/ml-kit-document-scanner-api.html).

**Mức độ chắc chắn:** đã xác nhận cấu trúc tích hợp và giới hạn API công khai. Giả thuyết mạnh là UI SDK theo ngôn ngữ hệ thống/Play services thay vì lựa chọn riêng trong app. Chưa đo locale thật, package Activity đang hiển thị hay phiên bản Play services trên thiết bị chụp ảnh; không khẳng định đã tái hiện hoặc lỗi xảy ra trên mọi thiết bị. Nếu cả hệ thống lẫn app cùng tiếng Anh mà SDK vẫn Việt, phải điều tra trạng thái Play services/locale thực tế trước khi chọn cách sửa.

Tái hiện cần thực hiện: hệ thống vi, app MANUAL en → Quét; đối chiếu hệ thống en/app vi và SYSTEM en/en. Ghi riêng locale hệ thống, app, SDK thực hiển thị.

### F02 — P2: Lỗi của luồng quét vẫn trả chuỗi tiếng Anh cố định/exception nguyên văn

`DocumentScannerHelper.kt:69,73,86,90,106,112,124,159` trả chuỗi hardcode hoặc `e.localizedMessage`. MainActivity dòng 115/137/193/233 và CameraScanActivity dòng 642/651/655 đưa `err` vào Toast đã dịch. Vì vậy có thể xuất hiện thông báo trộn ngôn ngữ khi mở scanner/parse/import lỗi. Đây là lỗi riêng đã thấy trong mã, không phải nguyên nhân các nhãn tiếng Việt trong ảnh.

### F03 — tồn tại trước, ngoài phạm vi sửa màn Quét

`python scripts/audit_localization.py` báo 12 nhóm lỗi: 6 locale de/es/fr/in/ja/pt thiếu 48 khóa mỗi locale; vi thiếu 6 khóa; 5 nhãn hardcode ở 2 layout OCR. Base 718 strings, 7 plurals. Không suy ra tất cả nhãn OCR là sai về sản phẩm; cần rà ý nghĩa riêng. Không gộp việc dịch toàn bộ app vào bản sửa Quét và không sửa validator để che lỗi.

## 3. Quyết định kỹ thuật cần chốt trước triển khai routing

1. Không có cơ sở hứa chỉ cần bọc Context, `Locale.setDefault`, extras tự đặt, đổi SCANNER_MODE, hay thêm strings là ép được ngôn ngữ UI SDK. Không dùng reflection/private API, không đổi locale toàn hệ thống.
2. Nếu yêu cầu bắt buộc Quét luôn theo ngôn ngữ app, hướng kiểm soát được là UI quét do T-Scanner sở hữu. Có thể tận dụng CameraScanActivity hiện hữu, nhưng phải kiểm tra chất lượng và tính năng trước khi đổi mặc định.
3. Camera hiện tại có tự cắt viền, không được coi là tương đương tự động chụp của Google. Chưa chứng minh tương đương nhập ảnh, xóa bóng/vết bẩn/ngón tay, xử lý nhiều trang. Không âm thầm thay Google rồi báo hoàn tất.
4. Hướng ít thay đổi: giữ Google, cung cấp đường Camera T-Scanner và thông tin ngắn đã dịch về ngôn ngữ giao diện Google. Đây là giảm bất tiện, **không đáp ứng hoàn toàn** mục tiêu UI Google theo ngôn ngữ app.
5. Khuyến nghị làm L00 trước, rồi trình bảng đánh đổi cụ thể. L01/L02 chỉ thực hiện sau khi người dùng duyệt hướng routing và chấp nhận khác biệt tính năng; kế hoạch hiện tại chưa phải lệnh sửa code.

## 4. Các gói dành cho mô hình nhỏ — thực hiện tuần tự

Không chạy nhiều tác nhân cùng sửa file. Các đường dẫn trong bảng là tương đối với `E:\DU AN AI\T-Scanner`. Mỗi gói đọc lại mã hiện tại, không dựa mù quáng vào số dòng.

### L00 — Tái hiện và chốt hợp đồng (chỉ kiểm tra)

- Đọc MainActivity, DocumentScannerHelper, AppLanguageManager, CameraScanActivity, HomeFragment, FilesFragment và layout camera.
- Chỉ ghi `REPORT_SCAN_LANGUAGE_L00.md`. Ghi snapshot git, version app/Android/Play services, package Activity thực mở, effective app locale, system locale, ảnh trước/sau mở Quét. Không ghi dữ liệu tài liệu cá nhân.
- Ma trận tối thiểu Android <=32 và >=33; SYSTEM vi/en, MANUAL en trên máy vi, MANUAL vi trên máy en; thêm ja để kiểm tra font; đổi en→ja khi app còn chạy và mở lại.
- Kiểm tra mọi cửa vào: FAB, Home Quét, Files Quét, lựa chọn AI, nút AI trong Camera, ID card.
- Regression baseline phải thể hiện màn T-Scanner đổi ngôn ngữ nhưng SDK không đổi nếu tái hiện được. Thiếu thiết bị thì đánh dấu NOT RUN, không tự suy ra PASS.
- So sánh tính năng camera hiện hữu với Google: chụp tay/tự chụp, gallery, nhiều trang, crop, chất lượng ảnh, luồng thẻ. Đề xuất route cụ thể và phần mất tính năng.
- Nghiệm thu: xác định được nơi sở hữu UI và tái hiện locale hoặc công khai khoảng trống; hướng triển khai có đánh đổi rõ. Dừng trước sửa code.

### L01 — Chính sách chọn đường Quét (sau duyệt hướng)

- File sở hữu mới: `app/src/main/java/com/tscanner/app/utils/ScanUiPolicy.kt`, `app/src/test/java/com/tscanner/app/ScanUiPolicyTest.kt`.
- Chỉ biểu diễn chính sách đã được duyệt giữa camera nội bộ và lựa chọn Google rõ ràng. Không hardcode “khác vi thì camera”; so khớp app/system cũng không chứng minh locale SDK.
- Nếu yêu cầu đồng nhất tuyệt đối: default dùng UI nội bộ cho mọi locale; Google chỉ là lựa chọn có giải thích giới hạn. Nếu người dùng giữ default Google, ghi mục tiêu còn giới hạn.
- Regression gọi chính production policy cho 8 locale, SYSTEM/MANUAL, fallback ngôn ngữ chưa hỗ trợ; kiểm tra không phụ thuộc locale OCR, không can thiệp locale thiết bị.
- Nghiệm thu: bảng route đúng hợp đồng; focused test pass. Dừng, bàn giao API policy, chưa sửa Activity.

### L02 — Nối chính sách vào các cửa vào và tài nguyên UI

- Phụ thuộc L01. Sở hữu: MainActivity.kt, ui/home/HomeFragment.kt, ui/files/FilesFragment.kt, ui/camera/CameraScanActivity.kt, res/layout/dialog_scan_options.xml; chỉ thêm layout khác nếu L00 chỉ rõ nhu cầu và báo phạm vi.
- Sở hữu 8 strings.xml: values, values-vi, values-es, values-pt, values-fr, values-in, values-de, values-ja; chỉ khóa liên quan Quét. Khi L03 tiếp quản tài nguyên, L02 phải đã kết thúc.
- Đường default dùng policy thật; lựa chọn Google phải thể hiện đúng đó là trình quét riêng. Không tự đổi hành vi VIP/thẻ, page limit=2 hoặc pipeline lưu/export; nếu thẻ cần route riêng, nêu rõ trong L00 và chỉ làm trong phạm vi được duyệt.
- Test mới `app/src/androidTest/java/com/tscanner/app/ScanEntryLanguageTest.kt`: thao tác cửa vào thực và kiểm tra Activity/view text theo locale; chỉ dùng hạ tầng instrumentation hiện có. Nếu thiếu dependencies, tách đề xuất bổ sung, không mở rộng build tùy tiện.
- Regression: cấu hình app en/system vi, default không vô tình gọi lại Google; kiểm tra các đường vòng Home/Files/Camera AI; đổi locale và reopen không dùng text cũ. Không chỉ grep tên hàm làm bằng chứng routing đúng.
- Nghiệm thu: chụp một/nhiều trang đến editor, cancel/back, quyền camera bị từ chối, không mất trang. Chưa đủ tính năng đã thỏa thuận thì dừng, không coi native camera là thay thế hoàn chỉnh.

### L03 — Bản địa hóa thông báo lỗi thuộc T-Scanner

- Làm sau L02 để tránh ghi đè strings. Sở hữu DocumentScannerHelper.kt, 8 strings.xml; test mới `app/src/androidTest/java/com/tscanner/app/ScannerErrorLanguageTest.kt` hoặc seam/test JVM tối thiểu gọi đúng logic production.
- Giữ chữ ký callback nếu có thể. Thay 8 chỗ lỗi nêu ở F02 bằng getString và placeholder đúng kiểu; lỗi SDK hiển thị thông báo thân thiện của app, chi tiết kỹ thuật chỉ log phù hợp. Không dùng exception nguyên văn như nội dung đã bản địa hóa.
- Không đổi copy ảnh, scope coroutine, cancel/result semantics hay xóa file trong gói dịch.
- Regression ép các nhánh mở thất bại, parse lỗi, không có trang, import trang lỗi; kiểm tra text/placeholder qua resources thực en/vi/ja và kiểm tra đủ khóa cả 8 locale. Bản cũ phải fail ở ít nhất một lỗi hardcode.
- Nghiệm thu: các thông báo thuộc app theo locale; rõ ràng gói này không dịch được UI SDK. Dừng và ghi báo cáo.

### L04 — Tích hợp và nghiệm thu

- Chỉ ghi `REPORT_SCAN_LANGUAGE_L04.md`; không sửa production âm thầm để làm test xanh.
- Chạy focused tests L01-L03, `python scripts/audit_localization.py`, `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline` với cache ghi được.
- So baseline F03: không thêm lỗi dịch; lỗi cũ phải liệt kê riêng, không báo localization toàn app PASS. Không bỏ qua lỗi build mới liên quan thay đổi.
- Thiết bị: 8 locale en/vi/es/pt/fr/id/de/ja; ưu tiên cross-locale máy vi/app các ngôn ngữ khác; Android trước/sau 33; đổi ngôn ngữ khi chạy, đóng mở lại; toàn bộ cửa vào L00. Lưu ảnh và kết quả từng case.
- Quét tay/tự chụp nếu thuộc hợp đồng, nhiều trang, gallery nếu thuộc hợp đồng, hoàn tất sang editor, cancel, rotation/background, mở Google tùy chọn, thẻ hai mặt. Kiểm tra ảnh đầu ra đủ độ phân giải, thứ tự và không mất trang.
- Nghiệm thu: không chỉ JVM xanh; phải có bằng chứng thiết bị cho ngôn ngữ UI. Nếu chưa chạy thiết bị, kết luận HOST ONLY / DEVICE PENDING.

## 5. Prompt giao việc dùng lại

> Đọc PLAN_SCAN_UI_LANGUAGE_SMALL_MODEL_2026-09-26.md, thực hiện duy nhất gói [L00/L01/L02/L03/L04]. Xác nhận phụ thuộc và phê duyệt hướng routing trước phần triển khai. Bảo toàn toàn bộ thay đổi có sẵn. Chỉ sửa file gói sở hữu; không đổi luồng OCR/VIP/Drive/release ngoài phạm vi. Kiểm tra lại chứng cứ mã hiện tại. Test phải gọi production logic/resources; nêu cách test bắt lỗi cũ. Báo cáo file thay đổi, lệnh và kết quả, test chưa chạy, rủi ro và điểm dừng. Không tự làm gói kế tiếp. Nếu SDK không hỗ trợ locale, không bịa API hoặc hứa wrap Context giải quyết được.

## 6. Kiểm tra đã thực hiện khi lập kế hoạch

- Tìm nguồn câu chữ, đối chiếu layout và call graph, đọc locale policy, kiểm tra tài liệu API chính thức: hoàn tất.
- Localization validator: FAIL 12 nhóm như F03; không phải bằng chứng UI SDK.
- Gradle offline: chưa chạy được test/build/lint; wrapper không tạo được lock dưới `C:\.gradle\wrapper\dists\gradle-9.7.1-bin\...`. Đã thấy cache khác tại `C:\Users\nguye\.gradle`; cần cấu hình cache/quyền ghi phù hợp khi triển khai. Đây là lỗi môi trường trước compile, không kết luận lỗi ứng dụng.
- ADB: daemon không khởi động, không kết nối được; chưa có kiểm chứng runtime/instrumentation.
- Không sửa mã ứng dụng, resources, cấu hình phát hành hay các báo cáo cũ.
