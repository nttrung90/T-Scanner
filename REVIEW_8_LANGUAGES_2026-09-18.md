# Kiểm tra sau triển khai 8 ngôn ngữ — 18/09/2026

Phạm vi: đối chiếu PLAN_8_LANGUAGES_AUTO_SYSTEM_2026-09-18.md với code hiện tại; không sửa code sản phẩm. Các lỗi runtime dưới đây có bằng chứng từ code và kịch bản tái hiện, chưa được xác nhận trực tiếp trên thiết bị.

## Kết quả xác minh

- Catalog tám UI, resolver primary-only, fallback Anh, bỏ manifest localeConfig và mục chọn thủ công đã được triển khai.
- Giữ bảy thư mục dịch + nguồn Anh; có lọc resource trong Gradle và `bundle.language.enableSplit = false`.
- Tên ngôn ngữ OCR đã chuyển sang resource và catalog OCR riêng.
- `python scripts/audit_localization.py`: PASS, 625 strings + 7 plurals, bảy thư mục dịch, không thiếu/thừa key hoặc sai placeholder theo validator.
- `python -m unittest discover -s scripts -p test_audit_localization.py`: 12/12 PASS.
- Gradle `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`: BUILD SUCCESSFUL. Chạy lại riêng `:app:testDebugUnitTest --rerun --offline --console=plain`: PASS; báo cáo 129 tests, 0 failures/errors.
- Lint: 0 errors, 681 warnings. Trong đó 21 MissingQuantity, 59 ContentDescription, 22 SetTextI18n; không coi toàn bộ warning là lỗi thực tế.
- Gradle cần quyền truy cập cache ngoài workspace; lần đầu bị Access denied, chạy lại với quyền phù hợp thành công. Đây không phải lỗi app.
- `adb devices`: không có thiết bị. Chưa kiểm thử migration, recreate, ngôn ngữ offline trên bản cài AAB hoặc kiểm duyệt bản dịch bởi người bản ngữ. Chưa xác nhận release artifact hiện hữu chứa code mới.

## R01 — P1: Đổi ngôn ngữ có thể làm mất chỉnh sửa phiên ghép thẻ

**Bằng chứng:** `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt:50–56` giữ đường dẫn ảnh và config trong field Activity. `onCreate` ở dòng 131–152 đọc lại ảnh từ Intent và khởi tạo config; không có onSaveInstanceState/ViewModel để phục hồi các giá trị này. Thay ảnh tại dòng 163/181, đổi bố cục tại 263–296 và đảo mặt tại 309–311 chỉ cập nhật field. `TScannerApplication.kt:29–30` áp dụng locale khi resume; thay locale có thể recreate Activity, ngoài việc hệ thống cũng có thể recreate vì thay configuration.

**Tái hiện cần chạy:** mở ghép hai mặt → đổi ngang, đổi tỉ lệ, đảo mặt hoặc thay ảnh → ra Settings đổi Việt sang Nhật → quay lại. Activity mới quay về config và ảnh Intent ban đầu thay vì phiên đang chỉnh. Tác động là mất trạng thái chỉnh sửa, không khẳng định xóa file ảnh gốc.

**Sửa trước:** lưu config và đường dẫn ảnh hiện hành bằng saved state/draft phù hợp; khôi phục trước loadImages và init UI, không ghi đè trạng thái đã phục hồi. Không lưu bitmap lớn vào Bundle.

**Nghiệm thu:** kiểm thử Activity recreate và process recreation giữ bố cục/tỉ lệ/viền/thứ tự/ảnh mới; đổi ngôn ngữ vẫn giữ preview và nội dung xuất đúng. Giao model nhỏ riêng gói này.

## R02 — P2: Thông báo lỗi OCR còn lộ chuỗi kỹ thuật và báo tải model sai tình huống

**Bằng chứng:** `utils/TextRecognitionHelper.kt:74–81` trả trực tiếp `result.error` với Failure và ánh xạ mọi ModelUnavailable thành `ocr_model_downloading`. Các engine tạo Failure từ exception.message; `ui/tools/ToolsFragment.kt:414–416` cũng hiển thị trực tiếp error. Tesseract/Paddle có thể báo model unavailable dù model local bị lỗi, không phải đang tải.

**Tái hiện:** UI Nhật/Pháp → OCR lỗi đọc file hoặc engine ném lỗi tiếng Anh → toast vẫn tiếng Anh/raw exception; model local không khả dụng → thông báo đang tải dù không có tác vụ tải tương ứng.

**Sửa:** mã lỗi có cấu trúc → resource tám ngôn ngữ; chi tiết kỹ thuật chỉ ghi log. Phân biệt model đang tải, chưa có model và lỗi khởi tạo model local. Dùng một mapper chung cho tất cả callsite.

**Nghiệm thu:** inject mỗi loại lỗi, kiểm tra thông báo theo UI và không khẳng định có download khi không có. Không thay routing/thuật toán OCR trong gói này.

## R03 — P2: Màn hình kết quả OCR giữ nhãn ngôn ngữ cũ

**Bằng chứng:** `ui/ocr/OcrResultActivity.kt:285–301` lưu engine đã format thành chuỗi trong EXTRA_ENGINE; dòng 85–88 đọc lại nguyên chuỗi sau recreate. `TextRecognitionHelper.formatEngineMetadata` đưa tên ngôn ngữ tài liệu và nhãn fallback đã dịch vào chuỗi đó.

**Tái hiện:** OCR tài liệu Nhật khi UI Việt → mở kết quả có nhãn “Tiếng Nhật” → đổi máy sang Đức → quay lại. Nhãn bao ngoài có thể đổi Đức nhưng phần metadata vẫn tiếng Việt.

**Sửa:** truyền engineId/documentLanguage/fallbackUsed dạng dữ liệu; format lại trong context UI hiện tại. Có phương án tương thích với Intent cũ đang tồn tại.

**Nghiệm thu:** đổi UI khi ở màn hình kết quả; cả nhãn engine và tên ngôn ngữ thay đổi đúng, nội dung nhận dạng giữ nguyên.

## R04 — P3: Hộp Giới thiệu hiển thị phiên bản cũ

**Bằng chứng:** `app/build.gradle` là 0.7.0 / code 13 nhưng `res/values/strings.xml:177` ghi cứng 0.6.1 / Build 12. `res/layout/dialog_about.xml:38` dùng resource này; `AboutAppDialog` không thay bằng phiên bản package thực tế.

**Tái hiện:** mở More → About trên bản build hiện tại.

**Sửa:** resource chỉ chứa nhãn và placeholder; lấy versionName/versionCode từ package/build metadata. Dịch nhãn một lần, không phải sửa tám bản dịch mỗi lần tăng version.

## Việc nghiệm thu còn thiếu, không nên nâng thành lỗi nghiêm trọng

- 21 cảnh báo MissingQuantity: bảy plurals ở mỗi bộ es/fr/pt thiếu `many` (dòng 579–603). Android có thể dùng `other`, nên chưa có bằng chứng crash; cần rà ngữ pháp số lớn và test ICU/resource thực tế, không chỉ test XML. Ưu tiên thấp hơn R01–R03.
- `LANGUAGE_ROLLOUT_STATUS.md` ghi E07 hoàn thành, nhưng manual QA đang là checklist, không có kết quả thiết bị. Unit tests của enforcement kiểm tra hàm quyết định, không chứng minh framework thực sự áp dụng locale hoặc không mất state.
- Không phát hiện câu dài copy nguyên tiếng Anh trong sáu bản dịch ngoài các trường hợp tên thương hiệu/thuật ngữ/chuỗi có thể giống hợp lệ. Điều này không thay cho kiểm duyệt ngữ nghĩa và bố cục toàn bộ tám UI.

## Thứ tự đề xuất

R01 trước → R02 → R03 → kiểm thử thiết bị theo kế hoạch. R04 và ngữ pháp plurals có thể xử lý đợt nhỏ tiếp theo. Chưa đủ bằng chứng để tuyên bố “hỗ trợ đầy đủ đã nghiệm thu” dù các kiểm tra tự động đều pass.
