# Kế hoạch hỗ trợ đầy đủ 8 ngôn ngữ, tự động theo máy

Ngày: 18/09/2026. Chỉ lập kế hoạch; chưa sửa code ứng dụng.

## 1. Hành vi sản phẩm cần đạt

Ngôn ngữ giao diện tự động theo **ngôn ngữ chính, đứng đầu danh sách ngôn ngữ máy**. Nếu ngôn ngữ chính ngoài danh sách hỗ trợ, dùng tiếng Anh; không tìm ngôn ngữ thứ hai. Đây là cách diễn giải yêu cầu được sử dụng xuyên suốt kế hoạch.

| Ngôn ngữ chính của máy | UI | Tài nguyên đề xuất |
|---|---|---|
| en, en-US, en-GB… | English | `values` làm nguồn chuẩn |
| vi, vi-VN… | Tiếng Việt | `values-vi` |
| es, es-ES, es-MX… | Español | `values-es`, cách diễn đạt phổ thông |
| pt, pt-BR, pt-PT… | Português, bản dịch theo Brazil | `values-pt` dùng nội dung pt-BR |
| fr, fr-FR, fr-CA… | Français | `values-fr` |
| id, id-ID; alias Android in/in-ID | Bahasa Indonesia | `values-in`, canonical logic là id |
| de, de-DE, de-AT… | Deutsch | `values-de` |
| ja, ja-JP… | 日本語 | `values-ja` |
| ko, zh, ar, th, ru và mọi ngôn ngữ khác | English | `values` |
| Không đọc được locale hợp lệ | English | `values` |

Chọn `pt` làm nhóm hỗ trợ để mọi máy tiếng Bồ Đào Nha đều nhận cùng một bản dịch Brazil; không duy trì thêm bản Portugal. Tám ngôn ngữ không đồng nghĩa tám quốc gia.

- Không có bộ chọn ngôn ngữ UI thủ công. Mục ngôn ngữ có thể giữ dạng thông tin chỉ đọc: “Theo ngôn ngữ thiết bị · …”, được dịch đủ tám ngôn ngữ.
- Nâng cấp từ bản cũ: bỏ lựa chọn UI thủ công cũ, kể cả ngôn ngữ vẫn còn hỗ trợ. Ví dụ trước chọn Việt, máy đặt Nhật → UI Nhật.
- Đổi ngôn ngữ máy khi app đang ở nền → cập nhật khi quay lại; khởi động lại vẫn đúng. Không mất bản nháp, trang đang sửa hay công việc đang chạy do recreate.
- Không thay đổi lựa chọn ngôn ngữ tài liệu OCR, model OCR hoặc nội dung tài liệu khi đổi UI.
- Ví dụ quyết định: máy `[ko-KR, vi-VN]` → Anh; `[fr-CA, en-US]` → Pháp.

Kế hoạch này thay thế hướng duy trì 37 UI và đề xuất chỉ Anh/Việt trong các tài liệu trước. Không thay thế kế hoạch sửa lỗi engine OCR.

## 2. Bằng chứng từ code hiện tại

Các đường dẫn Kotlin dưới đây tương đối với `app/src/main/java/com/tscanner/app/`. Kiểm tra lại trước mỗi gói vì workspace có nhiều thay đổi chưa commit.

| Vị trí | Hiện trạng | Khoảng cách với mục tiêu |
|---|---|---|
| `utils/AppLanguageManager.kt:56`, `res/xml/locales_config.xml` | Catalog/cấu hình còn 37 UI | Phải thu hẹp nguồn tài nguyên và chính sách |
| `utils/AppLanguageManager.kt:185,244` | Tìm ngôn ngữ được hỗ trợ đầu tiên trong cả danh sách máy | `[ko,vi]` có thể chọn Việt; khác hợp đồng mới |
| `utils/AppLanguageManager.kt:53,373` | Migration v2 giữ lựa chọn framework hiện hữu | Không tự chuyển người dùng cũ sang chính sách mới |
| `ui/more/MoreFragment.kt:316` | Mở dialog chọn UI | Còn đường tạo override thủ công |
| `TScannerApplication.kt:21,26` | Migration ở Application và callback Activity | Phải tránh chạy cơ chế cũ sau cơ chế mới |
| `utils/TextRecognitionHelper.kt:56`, `ui/tools/ToolsFragment.kt:350,429` | Tra metadata OCR qua catalog UI | Thu hẹp UI dễ mất tên ngôn ngữ tài liệu |
| `utils/OcrModels.kt:155` | Tên OCR có nhánh Anh/Việt, còn lại trả nativeName | Chưa đáp ứng dịch đầy đủ UI ở sáu ngôn ngữ còn lại |
| `scripts/audit_localization.py` | Kiểm tra tập bản dịch và catalog/config | Cần đổi hợp đồng kiểm tra theo kiến trúc tự động mới |

Đây là kết quả đọc code, không phải kết quả kiểm thử trên thiết bị. Chưa xác nhận chất lượng bản dịch hiện có chỉ bằng việc có file resource.

## 3. Quyết định kỹ thuật và các bẫy cần tránh

### Nguồn locale duy nhất

Viết hàm thuần nhận locale chính thật của hệ thống, chuẩn hóa alias và trả một trong `en,vi,es,pt,fr,id,de,ja`. Không dùng `Locale.getDefault()` hoặc context đã bị app override làm nguồn quyết định, vì có thể đọc lại chính locale app.

Trên API 33+ nghiên cứu dùng `LocaleManager.systemLocales`; trên API 26–32 dùng cấu hình hệ thống độc lập và kiểm thử thực tế. Tách rõ locale hệ thống đầu vào với locale UI hiệu lực đầu ra.

### Áp dụng locale và vòng đời

Đề xuất dùng cơ chế AppCompat/platform hiện hữu để áp dụng **một locale hiệu lực do máy quyết định**, thay cho lựa chọn người dùng. Không chỉ trả mã đúng trong helper: context thật của Activity, dialog, thông báo và tác vụ nền cũng phải hiển thị đúng.

Không chỉ xóa app locale và trông chờ Android tự chọn: Android có cơ chế so khớp cả danh sách ưu tiên, khác yêu cầu primary-only. Cũng không gọi setter mỗi lần resume bất kể có thay đổi; chỉ áp dụng khi locale hiệu lực khác để tránh vòng lặp recreate.

Do sản phẩm không cho chọn UI độc lập, bỏ quảng bá bộ chọn per-app qua `android:localeConfig` và không bật generateLocaleConfig. Cập nhật validator để không còn đòi manifest công khai bộ chọn. Giữ một catalog nội bộ tám ngôn ngữ để kiểm tra tài nguyên/build. Kiểm chứng hành vi nâng cấp và Android Settings sau khi bỏ khai báo; việc bỏ khai báo không thay cho xử lý override đã lưu.

Thiết kế migration mới idempotent: thay override cũ bằng locale hệ thống đã phân giải; vô hiệu hóa đường migration cũ; không dựa riêng cờ v2 hoặc cờ một lần để xử lý backup khôi phục. Locale được tính lại ở cold start và khi hệ thống đổi, kể cả khi cờ migration đã hoàn tất. Đánh dấu hoàn tất sau khi xác minh áp dụng, có retry đúng lifecycle trên API dưới 33.

### Tài nguyên và đóng gói

Nguồn tiếng Anh đầy đủ trong `values`; bảy bản dịch còn lại như bảng trên. Xóa bản sao `values-en` chỉ sau khi đối chiếu nội dung. Đưa các bản dịch ngoài tám ngôn ngữ ra ngoài `res` để chúng không còn được build/validator yêu cầu cập nhật. Bảo tồn thay đổi chưa commit trước khi loại bỏ; không xóa cả `values-*` vì có thể chứa theme, kích thước hoặc tài nguyên không liên quan ngôn ngữ.

Lọc tài nguyên ngôn ngữ của dependency theo tám ngôn ngữ; kiểm tra alias `id/in` trên artifact thật. Không lọc nhầm tài nguyên không có qualifier. Đề xuất đóng gói cả tám bộ resource UI trong bản cài (tắt language splitting của AAB nếu dùng cơ chế này), để đổi ngôn ngữ offline không phụ thuộc tải split. Đo chênh lệch dung lượng, kiểm chứng bằng bản cài từ AAB chứ không chỉ APK debug.

Không xóa OCR assets hoặc hỗ trợ Unicode/RTL của nội dung tài liệu. Các UI ngoài tiến trình như camera/scanner của dịch vụ Google, permission dialog, chooser hệ thống có thể theo chính sách locale riêng: thử và ghi rõ giới hạn; không hứa ép được giao diện do hệ thống sở hữu.

## 4. Các gói cho model nhỏ, thực hiện tuần tự

### E01 — Chốt resolver tám ngôn ngữ và kiểm thử hợp đồng

**Phạm vi:** helper locale thuần, `AppLanguageManagerTest.kt`; chưa chuyển UI đang chạy.

**Làm:** tạo catalog mới và resolver primary-only; xử lý alias, region và locale rỗng. Không dùng hàm chuẩn hóa UI để chuẩn hóa ngôn ngữ OCR.

**Kiểm thử/nghiệm thu:** toàn bộ bảng mục 1; đặc biệt `[ko,vi]→en`, `pt-PT→pt`, `in-ID→id`, `zh→en`. Test phải bắt được hành vi duyệt locale thứ hai của code cũ. Resolver luôn trả đúng một trong tám mã.

### E02 — Tách tên ngôn ngữ OCR khỏi catalog UI

**Phạm vi:** `utils/OcrModels.kt`, `utils/TextRecognitionHelper.kt`, các nhãn OCR trong `ui/tools/ToolsFragment.kt`, adapter/dialog OCR và test liên quan.

**Làm:** tra metadata tài liệu từ OCR catalog; đưa tên ngôn ngữ hiển thị vào resource ổn định để dịch được tám UI. Có thể thêm nativeName như thông tin phụ, không lấy nativeName thay bản dịch chính ở sáu UI. Không sửa routing engine.

**Nghiệm thu:** UI Đức/Nhật hiển thị tên ngôn ngữ OCR theo UI; OCR Trung/Hàn và lựa chọn đã lưu vẫn tồn tại dù UI tương ứng bị loại. Không thay `key_ocr_document_language`, danh sách engine hay model tải về.

### E03 — Tự động theo máy, chuyển đổi người dùng cũ

**Phụ thuộc:** E01, E02.

**Phạm vi:** `AppLanguageManager.kt`, `TScannerApplication.kt`, điểm khởi tạo Activity cần thiết, `MoreFragment.kt`, dialog UI cũ và manifest; test migration/lifecycle.

**Làm:** tích hợp mục 3; bỏ thao tác chọn UI; mục ngôn ngữ chỉ đọc; không còn đường migration cũ ghi đè kết quả mới. Đảm bảo context nhận đúng locale hiệu lực, không chỉ badge đúng.

**Nghiệm thu:** nâng cấp từ cả 37 lựa chọn cũ đều theo máy; cờ v2 true/false, framework locale tồn tại/rỗng, preference và backup cũ đều xử lý được. Đổi máy Việt→Hàn→Nhật cho UI Việt→Anh→Nhật; không recreation loop, không mất bản nháp. Chạy cả API 26–32 và 33+.

### E04 — Thu hẹp resource và cập nhật công cụ kiểm tra

**Phụ thuộc:** E03.

**Phạm vi:** tài nguyên localization, `app/build.gradle`, config locale, `scripts/audit_localization.py`, `scripts/test_audit_localization.py`, `scripts/batch_translate_locales.py`.

**Làm:** chỉ giữ tám ngôn ngữ hoạt động; lưu các bản dịch khác ngoài `res`; điều chỉnh script dịch để không tái sinh 37 ngôn ngữ. Validator kiểm tra catalog/resource/build và việc không quảng bá bộ chọn thủ công.

**Nghiệm thu:** key thiếu/thừa, duplicate key/quantity, sai placeholder type/index, thiếu `other`, thiếu cả thư mục ngôn ngữ đều làm kiểm tra thất bại. Dùng fixture xấu để chứng minh validator bắt lỗi. Cho phép ngoại lệ tên thương hiệu/non-translatable có danh sách rõ ràng, không allowlist nguyên màn hình. Không coi alias `in/id` là hai ngôn ngữ.

### E05 — Hoàn thiện nguồn Anh và bản Việt

**Phạm vi:** `values`, `values-vi`, callsite còn hardcode/hợp câu/preformatted text liên quan.

**Làm:** khóa bộ key sau E02–E04. Rà toàn bộ chuỗi người dùng thấy: màn hình chính, scan/import, editor, file/folder, OCR, PDF/export/share, QR, tài khoản, Drive, VIP, dialog lỗi/quyền, loading/empty state, notification, accessibility/contentDescription, string-array và plurals. Format ngày/số theo locale UI; dữ liệu/mã máy dùng định dạng ổn định. Không biến dữ liệu người dùng thành bản dịch.

**Nghiệm thu:** không còn câu UI hardcode hoặc lỗi nội bộ thô trong luồng đã rà; không hiển thị key/raw enum; kiểm tra 0/1/2/nhiều, placeholder và thông báo lỗi. Giữ nguyên tính năng, không mở rộng sửa engine.

### E06a–E06f — Sáu gói dịch riêng

**Phụ thuộc:** E05. Mỗi lượt chỉ một ngôn ngữ; không giao model nhỏ dịch cả sáu cùng lúc.

| Gói | Bộ tài nguyên | Chú ý kiểm duyệt |
|---|---|---|
| E06a | `values-es` | Cách diễn đạt phổ thông, thuật ngữ scan/export |
| E06b | `values-pt` | Tiếng Bồ Đào Nha Brazil, không trộn Portugal |
| E06c | `values-fr` | Giống/số, dấu câu và câu có biến |
| E06d | `values-in` | Indonesia, thuật ngữ nhất quán |
| E06e | `values-de` | Từ dài, nút và dialog không cắt chữ |
| E06f | `values-ja` | Thuật ngữ Nhật, xuống dòng, font và plurals phù hợp |

**Mỗi gói:** đối chiếu từng key nguồn; dịch cả string-array/plurals/accessibility và tên ngôn ngữ OCR; không thay key/placeholder hoặc copy tiếng Anh để đạt đủ key. Tạo bảng thuật ngữ ngắn, ghi câu chưa chắc để người thông thạo ngôn ngữ kiểm duyệt.

**Nghiệm thu từng gói:** validator và resource compilation pass; tất cả chuỗi dịch có nội dung; rà ảnh chụp các luồng chính; người kiểm duyệt xác nhận hoặc trạng thái phải ghi “chưa nghiệm thu ngôn ngữ”. Đủ key không đồng nghĩa dịch đúng.

### E07 — Kiểm thử tích hợp và nghiệm thu phát hành

**Phụ thuộc:** tất cả gói trên.

**Phạm vi:** test, báo cáo QA, tài liệu hỗ trợ và artifact kiểm thử; chỉ sửa lỗi thuộc phạm vi kế hoạch nếu phát hiện.

1. Chạy validator và unittest Python, sau đó `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` bằng Gradle wrapper. Dùng `--offline` khi cache đủ; ghi lỗi môi trường riêng với lỗi app.
2. Trên mỗi nhóm API 26–32 và 33+, kiểm tra cả tám locale và các fallback `ko,zh,ar,th,ru`; có ít nhất một máy/emulator mỗi nhóm. Kiểm tra thêm `fr-CA`, `es-MX`, `pt-PT`, alias Indonesia và danh sách `[ko,vi]`.
3. Với mỗi UI, kiểm tra scan/import→edit→save→open→export/share; OCR, More, dialog/notification lỗi và nội dung dài; font lớn và màn hình nhỏ. Chụp kết quả và ghi locale UI thật, không chỉ badge.
4. Nâng cấp không xóa dữ liệu; đổi ngôn ngữ khi đang có bản nháp, app ở nền và sau process death; backup restore thiết lập cũ. OCR đã chọn Nhật/Trung/Hàn không đổi theo UI.
5. Cài từ AAB bằng luồng kiểm thử nội bộ hoặc bundletool phù hợp. Ngắt mạng, đổi lần lượt tám ngôn ngữ và fallback; xác nhận không thiếu split hoặc trộn ngôn ngữ dependency. Không ghi đè artifact release hiện hữu của người dùng khi chỉ kiểm thử.
6. Cập nhật `LANGUAGE_ROLLOUT_STATUS.md` và tài liệu cửa hàng dự kiến: đúng tám UI tự động, OCR riêng. Không tự đăng Google Play.

**Điều kiện hoàn thành:** tám UI đủ key và đã kiểm duyệt; fallback và migration đã chạy trên thiết bị; validator/unit/build/lint không có lỗi liên quan còn mở; bản cài AAB đúng hành vi offline. Nếu thiếu thiết bị hoặc người duyệt ngôn ngữ, ghi rõ phần chưa nghiệm thu, không công bố “hỗ trợ đầy đủ” chỉ từ build pass.

## 5. Prompt dùng cho mỗi lượt

> Đọc PLAN_8_LANGUAGES_AUTO_SYSTEM_2026-09-18.md và chỉ thực hiện gói [E01]. Kiểm tra lại code hiện tại, bảo toàn các thay đổi chưa commit và không mở rộng phạm vi. Làm đúng phần Phạm vi/Làm/Nghiệm thu của gói; viết và chạy kiểm thử hồi quy cần thiết. Không chuyển sang gói kế tiếp. Báo cáo file đã sửa, kết quả kiểm thử thực tế, điểm chưa xác minh và hướng dẫn nghiệm thu. Chính sách bắt buộc: ngôn ngữ chính máy thuộc tám nhóm thì dùng nhóm đó, ngoài tám nhóm dùng Anh; không chọn locale thứ hai; OCR độc lập.

Thay ID trong dấu ngoặc cho từng lượt theo thứ tự E01→E02→E03→E04→E05→E06a→E06b→E06c→E06d→E06e→E06f→E07. Nếu gói quá lớn, chia theo màn hình nhưng giữ cùng điều kiện nghiệm thu.

## 6. Nguồn và giới hạn kiểm chứng

- [Android per-app languages](https://developer.android.com/guide/topics/resources/app-languages): API locale, khác biệt API trước/sau 33, khai báo bộ chọn hệ thống và lọc tài nguyên dependency.
- [Android language resolution](https://developer.android.com/guide/topics/resources/multilingual-support): so khớp locale theo danh sách, lý do phải phân biệt với chính sách primary-only.

Trong lượt lập kế hoạch đã đọc code/cấu hình và tài liệu Android; chỉ tạo tài liệu này. Chưa chạy build, dịch lại resource hoặc thực hiện migration trên thiết bị.
