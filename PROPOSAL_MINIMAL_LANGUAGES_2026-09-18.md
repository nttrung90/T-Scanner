# Phương án tối giản ngôn ngữ để phân phối quốc tế

Ngày nghiên cứu: 18/09/2026. Trạng thái: đề xuất, chưa sửa code ứng dụng hoặc cấu hình Google Play.

## Quyết định đề xuất

Chỉ duy trì **tiếng Anh và tiếng Việt cho giao diện**. Tiếng Anh là nguồn chuẩn và ngôn ngữ dự phòng; tiếng Việt là bản dịch duy nhất. Nếu mục tiêu tuyệt đối là ít ngôn ngữ nhất, có thể chỉ giữ tiếng Anh, nhưng sẽ bỏ trải nghiệm tiếng Việt hiện có.

| Phương án | Số ngôn ngữ giao diện | Công việc khi thêm 20 chuỗi mới |
|---|---:|---|
| Chỉ tiếng Anh | 1 | 20 chuỗi nguồn, không thêm bản dịch |
| Anh + Việt — đề xuất | 2 | 20 chuỗi nguồn + 20 chuỗi dịch |
| Duy trì 37 ngôn ngữ | 37 | 20 chuỗi nguồn + 720 chuỗi dịch nếu dịch đầy đủ |

Từ 36 bản dịch xuống 1 giảm khoảng 97% số mục cần dịch cho mỗi chuỗi mới; đây không phải ước tính giảm 97% toàn bộ chi phí phát triển. Không có dữ liệu người dùng/doanh thu theo ngôn ngữ trong nghiên cứu này, nên chưa thể định lượng ảnh hưởng đến lượt cài và giữ chân người dùng. Người không đọc được tiếng Anh hoặc Việt sẽ gặp rào cản sử dụng.

## Căn cứ phân phối

Google Play cho phép chọn quốc gia/vùng phát hành riêng trong Play Console. Tài liệu bản địa hóa trình bày bản dịch trang cửa hàng và nội dung ứng dụng như các khả năng bổ sung. Từ hai cơ chế này, có thể chọn phân phối ở nhiều quốc gia với giao diện tiếng Anh; không cần một bản dịch UI cho mỗi quốc gia. Đây không phải cam kết ứng dụng được duyệt ở mọi thị trường.

- [Google Play: quốc gia phát hành](https://support.google.com/googleplay/android-developer/answer/7550024?hl=en).
- [Google Play: dịch và bản địa hóa](https://support.google.com/googleplay/android-developer/answer/9844778?hl=en).
- [Android: tài nguyên mặc định và fallback](https://developer.android.com/guide/topics/resources/localization).

Đề xuất trang cửa hàng lấy tiếng Anh làm mặc định, duy trì thêm tiếng Việt nếu cần. Bản dịch tự động của trang cửa hàng là cơ chế riêng, không dịch giao diện bên trong app. Mô tả và ảnh chụp cần thể hiện đúng rằng UI có Anh/Việt, còn OCR có danh sách ngôn ngữ riêng. Chỉ bổ sung ngôn ngữ cửa hàng hoặc UI sau khi có nhu cầu thị trường rõ ràng và người chịu trách nhiệm kiểm duyệt.

## Điều cần thay đổi trong T-Scanner

Hiện `app/src/main/res/xml/locales_config.xml` và `AppLanguageManager.SUPPORTED_LANGUAGES` vẫn khai báo 37 ngôn ngữ UI. Chỉ ưu tiên dịch Anh/Việt nhưng giữ toàn bộ danh sách sẽ không đạt mục tiêu giảm nghĩa vụ bảo trì.

`OcrRoutingResolver.SUPPORTED_OCR_DOCUMENT_LANGUAGES` đã là danh sách riêng. Tuy nhiên, `TextRecognitionHelper.kt` và `ToolsFragment.kt` vẫn gọi `AppLanguageManager.getLanguage(...)` với ngôn ngữ tài liệu OCR. Cần chuyển các chỗ này sang metadata OCR trước khi thu hẹp UI. Việc giảm UI không phải lý do xóa model, dependency hoặc ngôn ngữ OCR.

Giữ nội dung tài liệu, tên file và kết quả nhận dạng Unicode. Không tự đổi ngôn ngữ OCR đã lưu khi chuyển UI. Không tắt hỗ trợ hiển thị chữ từ phải sang trái chỉ vì bỏ giao diện tiếng Ả Rập.

## Các gói thực hiện cho model nhỏ

Thực hiện từng gói, đọc lại code hiện tại trước khi sửa. Không sửa đồng thời lỗi engine OCR hoặc chức năng không liên quan trong đợt thu hẹp UI.

### M01 — Tách các phụ thuộc UI còn sót khỏi metadata OCR

- Phạm vi: `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt`, `ui/tools/ToolsFragment.kt`, `utils/OcrModels.kt` và kiểm thử liên quan.
- Thay các lookup tên tài liệu qua danh sách UI bằng `OcrRoutingResolver.getLocalizedDocumentLanguageName` hoặc metadata tương ứng theo nhu cầu callsite.
- Rà toàn bộ callsite `AppLanguageManager.getLanguage`, không đổi những callsite thực sự phục vụ chọn UI.
- Nghiệm thu: trong UI Anh/Việt, tên OCR Nhật/Trung/Hàn vẫn đúng; catalog và lựa chọn OCR lưu trước đó không bị thu hẹp; routing engine không đổi.

### M02 — Chuyển cấu hình và dữ liệu ngôn ngữ người dùng

- Phạm vi: `utils/AppLanguageManager.kt`, dialog chọn UI, `res/xml/locales_config.xml`, lifecycle khởi tạo nếu cần, kiểm thử migration.
- UI chỉ có English, Tiếng Việt và tùy chọn Theo hệ thống. Theo hệ thống là chế độ, không phải ngôn ngữ thứ ba.
- Ngôn ngữ hệ thống được giải quyết theo danh sách hỗ trợ Anh/Việt, không có ngôn ngữ khớp thì dùng Anh. Tài nguyên và metadata UI phải thống nhất.
- Giữ lựa chọn Anh/Việt và chế độ Theo hệ thống của người dùng hiện hữu. Lựa chọn rõ ràng thuộc ngôn ngữ bị loại chuyển sang Anh.
- Không dựa duy nhất vào cờ migration cũ `key_locales_migrated_v2`: người đã migrate vẫn cần được xử lý. Thiết kế chuyển đổi idempotent, xét cả locale lưu bởi AppCompat/Android và dữ liệu preference cũ được khôi phục từ backup.
- Không sửa `key_ocr_document_language` khi chuyển UI.
- Nghiệm thu trên Android dưới 33 và từ 33: cài mới, nâng cấp từ UI Nhật/Ả Rập, giữ UI Việt/Anh, khởi động lại, đổi locale hệ thống, khôi phục thiết lập cũ; không vòng lặp recreate, không reset OCR.

### M03 — Loại tài nguyên dịch khỏi phạm vi bảo trì đang hoạt động

- Phạm vi: tài nguyên dịch trong `app/src/main/res`, `app/build.gradle`, validator localization và kiểm thử.
- Giữ nguồn tiếng Anh đầy đủ tại `values/strings.xml`; giữ bản dịch Việt tại `values-vi`. Kiểm tra nội dung trước khi loại bản sao `values-en`.
- Lưu bản dịch bị loại ngoài cây `res` nếu cần bảo tồn, nhất là thay đổi chưa commit. Không xóa cả thư mục `values-*` một cách máy móc: phải bảo toàn style, theme, cấu hình màn hình và tài nguyên không thuộc dịch ngôn ngữ.
- Kiểm tra cấu hình lọc locale khi đóng gói để tránh tài nguyên bản dịch của dependency làm UI ngoài danh sách xuất hiện. Chỉ lọc APK/AAB không đủ: mã nguồn và validator cũng phải phản ánh chính sách hai ngôn ngữ.
- Validator kiểm tra Anh/Việt, placeholder, plurals, key thiếu/thừa và tính thống nhất catalog/config; không vô hiệu hóa kiểm tra chất lượng để đạt pass.
- Nghiệm thu: thêm một chuỗi tính năng chỉ cần nguồn Anh + bản Việt; không cần cập nhật 35 ngôn ngữ đã loại; app trên hệ thống Nhật/Ả Rập/Pháp vẫn có giao diện fallback hoàn chỉnh. Kiểm tra cả tài nguyên màn hình scanner do thư viện cung cấp.

### M04 — Kiểm chứng và cập nhật hướng dẫn phát hành

- Chạy validator và kiểm thử của nó; chạy `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`. Khi chuẩn bị phát hành, kiểm tra artifact release thực tế và danh sách locale đóng gói.
- Thử thiết bị/emulator: cài mới, nâng cấp giữ dữ liệu, đổi UI và khởi động lại; scan/import/edit/save/export/chia sẻ bằng Anh/Việt; OCR một tài liệu ngoài hai ngôn ngữ UI theo engine đang hỗ trợ.
- Không coi build hoặc JVM tests là bằng chứng đã kiểm chứng migration trên thiết bị.
- Cập nhật tài liệu dự án và nội dung cửa hàng dự kiến: UI Anh/Việt; OCR riêng. Không tiếp tục quảng bá 37 ngôn ngữ giao diện.
- Báo cáo rõ kiểm thử đã chạy, chưa chạy và kết quả. Nghiên cứu này chưa chạy build hoặc kiểm thử thiết bị vì chưa thay đổi code.

## Quy tắc cho những tính năng tiếp theo

Mỗi tính năng mới chỉ thêm/sửa nguồn Anh và bản dịch Việt, ưu tiên resource theo ngữ cảnh thay vì ghép câu. CI kiểm tra hai ngôn ngữ này. Không tái tạo các bản dịch cũ bằng script. Không dùng dịch mạng lúc chạy làm giải pháp mặc định: nó bổ sung phụ thuộc kết nối và khâu kiểm duyệt mà mục tiêu hiện tại đang muốn giảm.

Chỉ mở thêm một ngôn ngữ UI khi có dữ liệu nhu cầu, người kiểm duyệt và ngân sách bảo trì lâu dài. Có thể thử bản dịch trang cửa hàng trước; việc đó không buộc mở thêm ngôn ngữ UI.

## Prompt giao việc

> Đọc PROPOSAL_MINIMAL_LANGUAGES_2026-09-18.md. Chỉ thực hiện gói M01 trước; giữ nguyên thay đổi hiện có của người dùng. Kiểm tra lại từng callsite trong code hiện tại. Tách lookup metadata OCR khỏi catalog UI, giữ nguyên routing engine và lựa chọn OCR đã lưu. Viết/chạy kiểm thử hồi quy phù hợp. Báo cáo file thay đổi, kết quả và giới hạn kiểm chứng; không làm M02–M04 hoặc sửa engine OCR trong cùng lượt.

Sau khi nghiệm thu từng gói, giao riêng gói kế tiếp với cùng phạm vi và tiêu chí ở trên. Nếu chọn chỉ tiếng Anh, điều chỉnh M02–M04 tương ứng; migration vẫn bắt buộc và lựa chọn OCR vẫn độc lập.
