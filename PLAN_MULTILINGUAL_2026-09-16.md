# Kế hoạch hoàn thiện đa ngôn ngữ T-Scanner

Ngày rà soát: 2026-09-16. Mục tiêu: giao từng việc nhỏ cho mô hình nhỏ hơn, không yêu cầu mô hình đó tự thiết kế lại toàn bộ hệ thống.

## 1. Phạm vi và kết luận

Rà soát ứng dụng Android thực tế trong `app/src/main`, cấu hình Gradle/manifest và test hiện có. Các thư mục `android/`, `ios/`, `tutorials/` là mẫu ML Kit khác, không thuộc đợt sửa này. Kết quả dựa trên **working tree hiện tại**, bao gồm nhiều thay đổi chưa commit của người dùng. Không ghi đè, revert hay stage các thay đổi đó.

**Ứng dụng có bộ chọn 37 ngôn ngữ, nhưng chưa có trải nghiệm đa ngôn ngữ hoàn chỉnh.** Có ba lớp vấn đề độc lập: bản dịch/giao diện, quản lý locale, và nhận diện ngôn ngữ tài liệu OCR. Chỉ bổ sung `strings.xml` sẽ không sửa hết.

Đợt này chỉ kiểm tra và lập kế hoạch; chưa sửa hành vi ứng dụng. Tệp kèm theo:

- `LOCALIZATION_AUDIT_2026-09-16.json`: danh sách đầy đủ theo locale, key, file và dòng.
- `scripts/audit_localization.py`: công cụ kiểm kê chỉ đọc source, xuất JSON ra stdout; không phải bộ kiểm tra chất lượng dịch hay cổng CI hoàn chỉnh.

### Số liệu đo trực tiếp

| Hạng mục | Kết quả | Cách hiểu |
|---|---:|---|
| Ngôn ngữ trong manager / locale config | 37 / 37 | Cần đồng bộ tag/script, không chỉ số lượng |
| Tệp `values*/strings.xml` | 42 | Gồm mặc định và các alias, không phải 42 ngôn ngữ |
| Key mặc định tiếng Anh | 246 | `values/strings.xml` |
| Key tiếng Việt | 246 | Đủ key hiện có, nhưng UI vẫn có chuỗi ngoài resources |
| Mỗi tệp locale còn lại | 220 | Thiếu cùng 26 key editor, kể cả `values-en` |
| Chuỗi giống nguyên bản tiếng Anh ở mỗi locale ngoài vi/en | 104–110 / 220 | Bao gồm thương hiệu/từ hợp lệ; không coi tất cả là lỗi dịch |
| Thuộc tính XML chứa literal | 102 | `text`, `hint`, `contentDescription`; có cả số, emoji, dữ liệu mẫu |
| Dòng Kotlin chứa literal có ký tự ngoài ASCII | 441 | Ứng viên cần phân loại; gồm tên bản ngữ/log và có thể bỏ sót tiếng Anh |
| Key bị suppress `MissingTranslation` | 26 | Chính là nhóm editor thiếu bản dịch |
| `<plurals>` trong các tệp strings được kiểm kê | 0 | Đếm tệp, ảnh, trang đang dùng string hoặc nối chuỗi |
| Key trùng / key thừa so với mặc định | 0 / 0 | Chỉ trong các tệp strings hiện tại |

Ví dụ rõ ràng: `values-fr/strings.xml` vẫn chứa `Sign in with Google`, `Account Details`, `OCR Engine Settings`, `Choose save folder...`. Phần cuối của nhiều locale khác có cùng hiện tượng. 26 key thiếu được liệt kê trong trường `missing` của JSON.

## 2. Phát hiện và mức ưu tiên

P1 = cần sửa trước khi coi hỗ trợ đa ngôn ngữ là hoàn chỉnh; P2 = chất lượng/khả dụng cần hoàn thiện trước phát hành. Không có đánh giá P0 từ lần kiểm tra này.

### F01 — P1: văn bản ghi trực tiếp làm giao diện trộn Việt/Anh/ngôn ngữ đã chọn

Bằng chứng tiêu biểu:

- `res/layout/activity_camera_scan.xml:106`: “Tự căn viền”; cùng file có hướng dẫn chụp và nút hoàn tất.
- `res/layout/dialog_scan_options.xml:40`: tiêu đề và hầu hết mô tả tiếng Việt.
- `res/layout/dialog_id_card_options.xml:105`: nội dung hai phương thức quét chưa resource hóa.
- `res/layout/activity_pdf_viewer.xml:80`: nút “LƯU”.
- `res/layout/activity_document_management.xml:95`: hint tìm kiếm; nhiều thống kê và bộ lọc cũng ghi trực tiếp.
- `ui/editor/PostScanEditorActivity.kt:469`: tiêu đề tiến trình tiếng Việt; `:479` ghép tiến độ.
- `ui/editor/viewmodel/PostScanEditorViewModel.kt:60,190,395`: tiêu đề mặc định và lỗi là tiếng Việt.
- `ui/ocr/OcrResultActivity.kt:90`: nhãn “Động cơ”; `utils/TextRecognitionHelper.kt` giữ nhãn engine đã dịch trong biến toàn cục.
- `ui/dialogs/CheckUpdateDialog.kt:162`: thông báo tiếp tục cập nhật tiếng Việt.
- `utils/QrScannerHelper.kt:199,243`: nút SMS và nhãn dữ liệu CCCD; `QrResultDialog` còn ghép SSID/mật khẩu tiếng Việt.

Tái hiện cần làm: chọn English, mở camera → editor → lưu PDF, quản lý tài liệu, QR và VIP; chụp lại những chuỗi còn tiếng Việt. Danh sách trong JSON là đầu vào, không thay việc truy vết dữ liệu động.

### F02 — P1: tài nguyên đủ một phần nhưng nội dung dịch bị bỏ dở

`values/strings.xml:280` trở đi suppress 26 key editor. Ngoài vi, các locale khác rơi về tiếng Anh. Những key đã tồn tại cũng có nhiều đoạn chưa dịch; so sánh số key không phát hiện được. Cần tách kiểm tra coverage và kiểm tra nội dung.

### F03 — P1: chế độ Theo hệ thống và hai nguồn lưu locale xung đột

`utils/AppLanguageManager.kt:139–160,213–230` lưu preference riêng, đồng thời manifest bật AppCompat `autoStoreLocales`.

- Chọn “System” hiện ghi locale cụ thể của thiết bị bằng `setApplicationLocales`, không xóa override. Máy đổi ngôn ngữ khi app đang sống có thể không được theo dõi như nhãn hứa hẹn.
- Startup luôn đọc preference cũ và áp lại; có đường ghi đè lựa chọn trong Android 13+ Settings. Đường gọi từ `Application` và thời điểm AppCompat khởi tạo cần test thiết bị để kết luận từng phiên bản.
- `getCurrentLanguageCode(context)` không dùng locale của `context`; `getSystemLanguageCode()` chỉ lấy phần tử đầu và `.language`, bỏ script/region.
- Máy có danh sách `[ru, fr]` bị logic hiện tại coi là không hỗ trợ và ép English, dù French đứng thứ hai có thể dùng được.

Đây là rủi ro có bằng chứng từ luồng code, **chưa xác nhận bằng runtime**. Thiết kế sửa ở L01/L02 bên dưới phải có migration một lần, không tạo thêm kho lưu trạng thái thứ ba.

### F04 — P1: tag ngôn ngữ/script không nhất quán

`AppLanguageManager.normalizeCode:100` rút toàn bộ `zh-*` về `zh`, `sr-*` về `sr`. Danh sách ghi “Srpski (Latin)” nhưng áp tag `sr`, trong khi có tài nguyên `values-b+sr+Latn`. Mọi biến thể Chinese có thể bị đưa về giản thể. `nb` có resources nhưng không nằm trong supported codes; `tl-PH`, `in-ID` được cắt sau kiểm tra alias nên không được chuẩn hóa tiếp như `tl`/`in` thuần.

Không suy luận “cùng language code là cùng script”. Không quảng bá hỗ trợ Serbian Cyrillic hay Chinese Traditional khi chưa có bản dịch/model phù hợp. Quy tắc alias phải kiểm tra tag đầy đủ.

### F05 — P1: ngôn ngữ giao diện đang quyết định ngôn ngữ tài liệu OCR

`TextRecognitionHelper.kt:125–138` lấy UI locale để chọn recognizer. Người dùng giao diện Việt quét tài liệu Nhật không có cách chọn ngôn ngữ tài liệu độc lập.

- `tessLang = if (currentLangCode == "en") "eng" else "vie"`: ép Tesseract ở ja/ko/fr/ar… sẽ dùng `vie`; nhãn có thể vẫn ghi ngôn ngữ đã chọn.
- `th`, `ar` ở Auto trả `""`; caller hiện dùng thông báo không tìm thấy chữ. Không phân biệt không hỗ trợ, chưa tải model, lỗi chạy và ảnh không có chữ.
- Fallback sang Latin/English cho tài liệu hệ chữ khác có thể trả vài ký tự/số và bị coi thành công.
- `lastEngineUsed` là chuỗi toàn cục đã gắn ngôn ngữ; có thể cũ sau đổi locale hoặc thuộc tác vụ khác.
- `ToolsFragment.kt:338–340,379–381` gắn nhãn “Tiếng Việt + Tiếng Anh” nhưng pipeline không mặc định gọi model kết hợp `vie+eng`.

Không thêm model mới trong đợt này. Mục tiêu là chọn đúng năng lực đã có và báo đúng giới hạn. Chất lượng OCR thực tế phải đo bằng tài liệu mẫu, không suy từ tên engine.

### F06 — P1: nội dung sản phẩm không nhất quán giữa locale

`values-en/strings.xml:74` ghi 10GB cloud storage, mặc định ghi sao lưu Google Drive 15GB. `dialog_vip_upgrade.xml:109` sử dụng trực tiếp key này, nên khác biệt hiện lên UI. Quyền lợi không được tự thay đổi khi đổi ngôn ngữ.

`vip_price_yearly` ở mặc định là 20.000 VND/năm nhưng nhiều locale là 699.000 VND/năm giảm 40%. **Chưa tìm thấy callsite hiện tại của key giá này**; không kết luận người dùng đang bị tính sai tiền. Dialog hiện có giá hardcode và thao tác kích hoạt dùng thử. Cần chốt nội dung từ hành vi sản phẩm, bỏ bản sao cũ/claim không có bằng chứng; không tự triển khai thanh toán hay đổi mức giá.

Hai ứng viên `vip_price_yearly` ja/hu trong kiểm tra placeholder là dấu `%` trong nội dung giảm giá, không phải bằng chứng chắc chắn crash. `formatted="false"` của de đã được công cụ loại khỏi so sánh. Không thêm `%s` chỉ để làm checker xanh.

### F07 — P2: bộ chọn ngôn ngữ vẫn thiên về tiếng Việt

`LanguageSelectionDialog.kt:47–53` ghi mô tả hệ thống tiếng Việt. `LanguageAdapter.kt:67` luôn dùng `vietnameseName`; tìm kiếm chỉ theo tên bản ngữ, tên Việt, mã. Giao diện English tìm “Japanese” không khớp tên Nhật/Việt hiện có. Badge “Chưa có OCR” cũng hardcode. Cần tìm theo tên ở locale UI + bản ngữ + English + code; giữ tiếng Việt như alias tương thích. Chuẩn hóa dấu cần xử lý `đ/Đ` nếu muốn hỗ trợ tìm không dấu.

### F08 — P2: Arabic/RTL, độ dài chữ và accessibility chưa được kiểm chứng

Manifest có `supportsRtl=true`, nhiều layout đã dùng Start/End: đây là nền tảng tốt. Tuy nhiên `ic_chevron_right.xml` chưa auto-mirror, sáu toolbar dùng `rotation=180`, `fragment_files.xml:163` dùng mũi tên văn bản cố định. Các chuỗi đường dẫn/email/SSID trộn RTL cần kiểm tra Bidi. Hộp ngôn ngữ có list 260dp và nhiều hàng/nút chiều cao cố định: rủi ro khi font lớn, chưa kết luận tràn khi chưa render.

Không mirror ảnh tài liệu, tọa độ crop hay nút xoay vật lý trái/phải. Chỉ mirror điều hướng theo hướng đọc.

### F09 — P2: đếm số, ngày giờ, lỗi và dữ liệu đã lưu

`items_count`, `extracted_images_count`, `presentation_created` dùng string đơn; `DocumentManagementActivity.kt:177–225` ghép “tệp”. `FileUtils.kt:115–121` dùng `Locale.getDefault()` và mẫu ngày cố định `dd/MM/yyyy HH:mm`. Định dạng số đã có locale ở một số chỗ, nhưng cần locale UI context, ngày hiển thị phù hợp vùng và plurals.

Tiêu đề tài liệu đã persist không được tự dịch/đổi tên khi đổi UI. Lỗi trong ViewModel/helper và chuỗi engine không nên cache dưới dạng văn bản đã dịch. Export Word hiện tạo HTML UTF-8 nhưng chưa có hướng đoạn văn (`PdfConverterHelper.kt` phần `createWordFromText`); cần kiểm tra văn bản đa hệ chữ/bidi trước khi tuyên bố hỗ trợ xuất đầy đủ.

## 3. Quyết định triển khai đã đề xuất

1. Giữ phạm vi 37 lựa chọn UI hiện có, hoàn thiện từng locale; không thêm ngôn ngữ mới, không lặng lẽ gỡ ngôn ngữ đang hỗ trợ. Nếu chưa đủ QA thì ghi là chưa hoàn thành, không gọi fallback tiếng Anh là đã dịch.
2. `values/` là nguồn tiếng Anh chuẩn. Hợp nhất chênh lệch có chủ đích trong `values-en`, rồi bỏ bản sao en nếu không cần override. Không đổi key bừa bãi.
3. Locale UI dùng AppCompat/framework làm nguồn chính. “System” là locale override rỗng. Dùng cơ chế chọn resources của Android để xét danh sách ngôn ngữ; mặc định English khi không match. Không ép `en` vào preference chỉ vì máy đang dùng ngôn ngữ chưa hỗ trợ.
4. Tag canonical đề xuất: `zh-Hans`, `sr-Latn`, `nb`; alias `zh`, `sr`, `no`, `tl`, `in` của dữ liệu cũ được migrate có kiểm soát. Nhận biết `zh-Hant`/`sr-Cyrl` riêng, không tự khẳng định là script đang hỗ trợ. Region giữ khi phù hợp; không viết bộ match locale tự chế nếu Android đã đáp ứng.
5. Tách `UiLanguage` và `OcrLanguage`. Auto trong OCR nghĩa là tự chọn engine **theo ngôn ngữ tài liệu đã chọn**, không phải tự phát hiện ngôn ngữ trong ảnh. Lần đầu có thể gợi ý theo UI để giữ thuận tiện, sau đó chọn riêng và lưu riêng.
6. Thông điệp nội bộ dùng mã lỗi/metadata và tham số; render ở UI bằng locale hiện tại. Nội dung OCR, tên file do người dùng đặt, dữ liệu QR và dữ liệu lưu không dịch.

Hướng dùng locale rỗng/đồng bộ Settings được đối chiếu với [Android per-app languages](https://developer.android.com/guide/topics/resources/app-languages). Quy tắc layout/Bidi tham khảo [Android language and culture support](https://developer.android.com/training/basics/supporting-devices/languages). ML Kit nhận diện theo script và bản unbundled cần tải model trước lần dùng đầu: [supported languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages), [Android setup](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

## 4. Các gói việc cho mô hình nhỏ

Làm tuần tự, một gói/một lượt. Với gói UI hoặc dịch, mỗi lượt chỉ sửa một nhóm nhỏ bên dưới. Trước khi sửa đọc diff hiện có của đúng file. Sau mỗi gói ghi file đã đổi, kiểm tra đã chạy và giới hạn còn lại. Không tự commit/push, nâng dependency, build release hay sửa sample apps.

### L00 — Baseline và danh mục chuỗi

**Đầu vào:** kế hoạch này, script kiểm kê, JSON; chỉ đọc source cần xác minh.

- Chạy lại kiểm kê, lưu baseline mới riêng nếu source thay đổi. Ghi revision/working-tree state vào biên bản, không ghi đè snapshot ngày 16/09.
- Phân loại 102 XML literals thành: phải dịch, thương hiệu/kỹ thuật, số ký hiệu, dữ liệu mẫu bị binding ghi đè. Di chuyển dữ liệu mẫu sang `tools:text` khi hợp lý.
- Phân loại 441 dòng Kotlin; thêm tìm kiếm literals ASCII ở `text`, `Toast`, dialog, chooser, lỗi trả ra UI, accessibility. Không dịch log, MIME, protocol, đường dẫn hay tên bản ngữ.
- Tạo bảng `file:line → resource key → screen → status`; glossary thống nhất Scan/Import/Export/Folder/Document/Page/Backup/Sync/Watermark.

**Nghiệm thu:** danh mục có lý do cho mỗi ngoại lệ; không lấy số 441 làm số bug. Không thay production code.

### L01 — Catalog và normalize locale (độc lập với UI)

**File:** `utils/AppLanguageManager.kt`, catalog/parser mới nếu cần; `res/xml/locales_config.xml`; các resource alias liên quan.

- Tách metadata ngôn ngữ UI khỏi `OcrType`; có `tag`, tên bản ngữ, alias. Chưa sửa routing OCR trong gói này; giữ adapter tương thích tạm thời để build.
- Chuẩn hóa bằng `Locale.forLanguageTag` sau đổi `_` thành `-`; không dùng `startsWith("zh")`/cắt mất script. Map legacy code trước/sau parse một cách nhất quán.
- Áp dụng canonical tags và migrate mã cũ; không xóa alias folders trước khi có test match trên API minSdk.
- Unit test thuần: `in`, `in-ID`, `id-ID`; `tl`, `tl-PH`, `fil-PH`; `no`, `nb-NO`; `sr`, `sr-Latn-RS`, `sr-Cyrl`; `zh`, `zh-Hans-CN`, `zh-Hant-TW`; tag sai/rỗng. Chốt kỳ vọng fallback cho script chưa có, không tự đổi Traditional thành Simplified mà không biểu đạt đúng.

**Nghiệm thu:** catalog/config/resource mapping có kiểm tra consistency; số lựa chọn không tăng do alias; không đổi route OCR ngoài adapter tương thích.

### L02 — Sửa vòng đời locale và migration

**Phụ thuộc:** L01. **File:** manager, `TScannerApplication`, manifest service metadata, `MoreFragment`, dialog ngôn ngữ nếu cần.

- Dùng locale list rỗng khi System; ngừng áp preference riêng mỗi startup.
- Migrate preference cũ đúng một lần. Ưu tiên lựa chọn framework/AppCompat đã tồn tại; với API 32 trở xuống phải đợi trạng thái auto-storage được phục hồi tại lifecycle phù hợp trước khi quyết định nó rỗng. Không suy từ getter trong `Application.onCreate` rằng người dùng chưa chọn.
- Nếu không có override và legacy là System/không có: không set locale cụ thể. Legacy explicit hợp lệ chỉ được import khi chưa có trạng thái mới. Đánh dấu migration sau thành công để Settings reset về System không bị preference cũ phục hồi lại.
- UI badge/checkmark đọc nguồn locale chính. Đọc resolved locale từ UI context; background chỉ nhận locale rõ ràng khi cần tạo nội dung người dùng thấy.
- Không gọi `recreate()` thêm nếu AppCompat đã làm. Bảo toàn draft/editor khi activity được tạo lại.

**Nghiệm thu thiết bị:** API 26/32 và 33/36; chọn app language, đổi trong Settings, System, restart/process death, đổi system khi app sống; cả `[ru,fr]` và `[ru]`. Không flip locale khi mở lại app. Test migration với legacy System/vi/sr/no và framework locale khác legacy.

### L03 — Sửa trải nghiệm chọn ngôn ngữ

**Phụ thuộc:** L02. **File:** `LanguageSelectionDialog`, `LanguageAdapter`, layout dialog/item, strings en/vi.

- Tên bản ngữ + tên trong locale UI; tìm kiếm tên UI/bản ngữ/English/code/alias Việt không dấu.
- Resource hóa mô tả System, fallback, trạng thái không có kết quả, accessibility.
- Bỏ badge engine khỏi danh sách ngôn ngữ UI; chuyển thông tin năng lực sang phần chọn ngôn ngữ OCR ở L06. Ngôn ngữ UI không mất khả năng chọn vì OCR chưa hỗ trợ.
- Hộp thoại cuộn/giới hạn chiều cao theo cửa sổ; tránh nút Close bị đẩy khỏi màn hình khi font lớn/bàn phím mở.

**Nghiệm thu:** UI English tìm “Japanese”, “Vietnamese”; UI Việt tìm “tieng duc”; tìm `nb`, `tl`; checkmark đúng sau trở về System; không còn mô tả tiếng Việt trong UI English.

### L04a–L04f — Đưa toàn bộ văn bản UI vào resources

**Phụ thuộc:** L00. Mỗi hàng là một lượt độc lập sau lượt trước; sửa cặp Kotlin/XML và `values/`, `values-vi/` cùng nhau. Dùng key có sẵn nếu cùng ý nghĩa; key mới theo prefix màn hình. Mỗi lượt tối đa khoảng 3–6 file logic/layout; chia nhỏ tiếp khi vượt giới hạn.

| Gói | Nhóm file bắt đầu | Kiểm tra tối thiểu |
|---|---|---|
| L04a | `CameraScanActivity`, `activity_camera_scan`, `dialog_scan_options`, `IdCardOptionsDialog`, `dialog_id_card_options` | Camera thường/thẻ, hướng dẫn hai mặt, lỗi camera, hoàn tất |
| L04b | Editor/crop/thumbnail + `PostScanEditorViewModel` + layout tương ứng | Undo, reset, crop, tiến độ, lỗi export, discard/retry; chia editor và crop thành hai lượt nếu cần |
| L04c | `PdfViewerActivity`, `PdfPageAdapter`, `OcrResultActivity`, layout tương ứng | Tên trang, lưu, chia sẻ, đọc và xuất text |
| L04d | `FilesFragment`, `DocumentManagementActivity`, `DocumentAdapter`, `FolderAdapter`, `ManagedFileAdapter` + layouts | Folder gốc, số tệp, loại tệp, menu, đường dẫn, xác nhận xóa; chia theo từng màn hình |
| L04e | `HomeFragment`, `ToolsFragment`, `SaveExportDialog`, `ExportDocDialog`, `CreatePdfDialog`, `IdCardComposeActivity` + layouts | Tiến trình scan/import/convert/export và lỗi; một màn hình hoặc một cặp dialog/lượt |
| L04f | `MoreFragment`, account/VIP/update/about, QR, rename/create-folder dialogs + helper QR | Toàn bộ nhánh signed-in/out, backup, update pending, QR Wi-Fi/SMS/CCCD; chia account/VIP, update, QR, dialog còn lại |

Quy tắc chung:

- Không ghép câu đã dịch; dùng `%1$s`, `%2$d` và giữ kiểu/thứ tự tham số. Không đưa raw `Exception.message` thành thông báo chính; log chi tiết, UI dùng lỗi ổn định.
- Với ViewModel/repository, trả mã lỗi và dữ liệu; UI resolve resource. Không đưa Activity Context vào ViewModel để dịch. Tiêu đề mặc định mới được khởi tạo ở boundary có context; không tự đổi tên tài liệu đã lưu.
- Đếm danh từ chuyển sang plurals ngay ở callsite mới; L08 rà lại số/ngày và trường hợp còn sót.
- Tên protocol, T-Scanner, PDF, Google Drive… có thể `translatable=false` sau review; không dùng thuộc tính này để che chuỗi người dùng cần hiểu.
- Chỉ thay văn bản/metadata trình bày, không sửa logic camera, file IO hay entitlement. Các file đang dirty phải giữ nguyên sửa lỗi trước đó của người dùng.

**Nghiệm thu từng lượt:** compile resources/Kotlin; smoke English và Việt trên đúng màn hình; danh mục L00 không còn literal chưa phân loại trong nhóm đã xử lý. Đừng chờ dịch 35 ngôn ngữ mới phát hiện resource dùng sai.

### L05 — Chốt nội dung nguồn và loại bỏ bản sao cũ

**Phụ thuộc:** L04, có thể sửa sai nội dung F06 sớm trước toàn bộ L04 nếu cần.

- So sánh `values/` với `values-en`: 3 key khác hiện tại là `vip_perks_title`, `vip_perk_5`, `vip_price_yearly`. Hợp nhất ý nghĩa, sau đó bỏ duplicate en nếu không còn override cần thiết.
- Ghi rõ VIP hiện là activation trial trong code. Không quảng bá một kho 10/15GB do app cấp khi thực tế dùng Drive của người dùng; mô tả sao lưu vào tài khoản Drive cá nhân là phương án an toàn về nội dung.
- Giữ mức giá/cơ chế hiện hành đã xác nhận trong sản phẩm; key giá cũ không dùng có thể xóa sau tra tất cả references. Không tự đổi giá/currency theo locale hoặc triển khai billing.
- Chỉnh mô tả OCR cho khớp routing/capability L06–L07: không khẳng định tất cả ngôn ngữ châu Á dùng Paddle, không khẳng định model unbundled luôn sẵn offline, không tuyên bố 100% chính xác.

**Nghiệm thu:** cùng quyền lợi/tính năng ở mọi locale; key không dùng có bằng chứng trước khi xóa; bảng thuật ngữ và nội dung en/vi đủ ổn định để dịch.

### L06 — Tách ngôn ngữ tài liệu khỏi ngôn ngữ UI

**Phụ thuộc:** L01–L03. Chia làm hai lượt: model/settings trước, tích hợp entrypoint sau.

- Tạo `OcrLanguage`/`OcrSettings` có preference riêng ngôn ngữ tài liệu và engine preference hiện có. Settings hiển thị hai trường riêng; giữ engine Auto là tự chọn engine, không quảng cáo tự nhận diện ngôn ngữ ảnh.
- Lần chưa chọn: gợi ý UI language nếu OCR hỗ trợ, nếu không thì yêu cầu chọn ngôn ngữ tài liệu có hỗ trợ trước khi chạy. Không tự ngầm nhận Arabic/Thai bằng English.
- Truyền snapshot `OcrRequest(languageTag, engineMode)` xuyên suốt một job; đổi UI/ngôn ngữ OCR giữa job không làm mỗi trang dùng một model khác.
- Truy vết tất cả callsite `recognizeTextFromFileSync`, `recognizeTextFromUriSync`, callback wrappers và luồng convert PDF/Word/Excel/PPT; không chỉ sửa Home. Giữ wrapper tương thích trong bước chuyển, rồi bỏ khi hết caller.

**Nghiệm thu:** UI Việt + tài liệu Nhật; UI Anh + tài liệu Việt; UI Arabic + tài liệu Anh; đổi UI không đổi lựa chọn OCR đã lưu; nhiều trang một job dùng cùng request.

### L07a — Routing và kết quả OCR có cấu trúc

**Phụ thuộc:** L06. **File:** `TextRecognitionHelper`, `TesseractOcrHelper`, capability model; caller liên quan.

- Tách hàm chọn engine/capability thuần để unit test. Mapping ban đầu: vi→vie, en→eng; zh-Hans→Paddle/ML Kit Chinese; ja/ko/hi→recognizer chuyên biệt; nhóm Latin còn lại→ML Kit Latin. Không mặc định non-en→vie.
- Engine explicit không phù hợp ngôn ngữ phải trả trạng thái không tương thích + gợi ý Auto, không âm thầm chạy sai. Chưa xác nhận Paddle hỗ trợ ja/ko ở asset hiện tại thì không dùng nó làm fallback cho các script đó.
- Fallback phải giữ capability cho ngôn ngữ/script; không coi kết quả số/Latin của model sai hệ chữ là thành công toàn tài liệu. Không bổ sung model dung lượng lớn.
- Trả kết quả dạng Success(text, engineId, documentLanguage, fallbackUsed) / NoText / UnsupportedLanguage / IncompatibleEngine / ModelUnavailable / Failure. Giữ coroutine cancellation; không nuốt cancellation thành NoText.
- Thay `lastEngineUsed` bằng metadata theo job/trang, truyền qua kết quả/Intent; render nhãn engine tại UI. Các wrapper đồng bộ/callback đều cần thống nhất.

**Nghiệm thu:** test bảng ngôn ngữ × engine; ar/th không về NoText; forced Tesseract ja không chạy vie; lỗi init model không thành ảnh trắng; hai job không lẫn nhãn engine.

### L07b — Trạng thái model và nội dung OCR

**Phụ thuộc:** L07a. **File:** ML Kit wrapper và UI tiến trình/lỗi.

- Phân biệt model unbundled chưa sẵn sàng và ảnh không có chữ; kiểm tra availability/request tải bằng API phù hợp dependency hiện tại, có retry/cancel.
- Thông báo bằng resources: cần kết nối lần đầu, đang tải, thử lại, ngôn ngữ chưa hỗ trợ. Không khóa tính năng scan/lưu ảnh/PDF khi OCR unavailable.
- Kết quả rỗng không được biến thành dữ liệu có thể copy/export: `OcrResultActivity` hiện gán `no_text_found` vào `extractedText`. Giữ empty-state UI tách khỏi dữ liệu OCR, vô hiệu hóa export/copy khi không có nội dung.

**Nghiệm thu:** fresh install offline, tải model thành công, mất mạng, hủy; OCR offline sau khi model có sẵn. Dùng mock/fake cho unit tests, dùng thiết bị cho dependency tải thực tế.

### L08 — Plurals, định dạng và RTL

**Phụ thuộc:** L04; chia số/ngày và RTL thành hai lượt.

- Plurals cho items/files/pages/images/slides/documents và các toast sync có số lượng. Không áp plural cho tỷ lệ trang `x/y` nếu không có danh từ biến đổi.
- Ngày hiển thị dùng formatter theo locale UI; số/kích thước file dùng formatter Android phù hợp. Tên file/ID kỹ thuật dùng định dạng ổn định `Locale.ROOT` khi cần, tách khỏi ngày hiển thị; không đổi tên file hiện có.
- Dùng drawable back/forward auto-mirror thay rotation cố định; kiểm tra Start/End, BidiFormatter cho đường dẫn/email/dữ liệu trộn hướng, contentDescription đã dịch.
- Không mirror bitmap/crop/rotate. Với export text, kiểm tra paragraph direction theo nội dung, giữ nguyên Unicode và escape HTML đầy đủ; không ép hướng theo UI locale vì tài liệu có thể khác UI.

**Nghiệm thu:** ar và `ar-XB`, số lượng 0/1/2/3/11/100, số âm nếu nghiệp vụ có; de/fr ngày giờ; font 1.0/1.3/2.0; màn hình hẹp; văn bản Arabic + tên file Latin; tài liệu Nhật/Việt/Arabic trong export. Không đặt font/bỏ dấu để né lỗi layout.

### L09 — Dịch phần còn thiếu và kiểm định theo nhóm nhỏ

**Phụ thuộc:** source en/vi đã ổn định ở L05, strings mới từ L03/L06–L08 đã bổ sung.

- Mỗi lượt **một locale, 40–60 key** hoặc một nhóm màn hình. Đọc bản dịch sẵn và glossary; không dịch toàn bộ 37 ngôn ngữ trong một lần gọi mô hình nhỏ.
- Thứ tự đề xuất để phát hiện sớm vấn đề hệ chữ: `fr`, `de`, `ja`, `zh-Hans`, `ko`, `ar`, `th`, `hi`; sau đó `es`, `pt`, `id`, `fil`, `ms`, `tr`; cuối cùng `nl`, `pl`, `cs`, `da`, `sv`, `nb`, `fi`, `hu`, `ro`, `hr`, `sk`, `sl`, `sq`, `ca`, `et`, `lv`, `lt`, `is`, `af`, `sr-Latn`. Việt/Anh đã được xử lý trong từng gói UI nhưng vẫn phải review cuối.
- Bổ sung toàn bộ key mới, 26 key editor, duyệt toàn bộ key giống English; không copy English sang locale chỉ để đạt coverage.
- Giữ placeholder/escape/newline/markup, không dịch `%1$s` hay tag XML. Nội dung có `%` tự nhiên phải có chiến lược `formatted=false` nếu không nội suy hoặc escape đúng khi có nội suy.
- Đồng bộ alias folders từ nguồn canonical bằng quy tắc tái tạo được, hoặc gỡ khi test thiết bị chứng minh không còn cần. Không để hai bản locale tự diverge.
- Mô hình nhỏ không chắc chất lượng dịch phải ghi `needs-review`. Cần người thông thạo duyệt bản địa hóa, đặc biệt Arabic/Thai/Hindi/CJK; “đủ key” không chứng minh tự nhiên hoặc đúng ý.

**Nghiệm thu từng locale:** không missing key người dùng thấy; duplicate/placeholder/XML hợp lệ; danh sách cùng English có lý do; review meaning giữ nguyên quyền lợi; smoke các màn hình chính. Bỏ suppress MissingTranslation cho key đã hoàn thiện, không tạo suppression hàng loạt mới.

### L10 — Cổng chống tái phát và nghiệm thu tổng

**Phụ thuộc:** các gói trên.

- Nâng script kiểm kê thành validator có exit code lỗi và allowlist có lý do: keys, placeholder có kiểu/index, plurals, XML escaping, catalog/config/alias, hardcoded text. Script hiện tại chỉ xử lý `<string>` và phát hiện lexical, phải bổ sung plurals và trường hợp format phức tạp trước khi dùng chặn CI.
- Unit tests cho canonicalization, routing, migration decision thuần; instrumentation cho locale thực, activity recreation, resources và plurals. Không dựa vào `unitTests.returnDefaultValues=true` để kết luận API Android hoạt động đúng; nếu dùng Robolectric phải cấu hình rõ hoặc dùng androidTest hiện có.
- Bật pseudo-locales `en-XA`/`ar-XB` ở debug khi cần; không đưa chúng vào danh sách ngôn ngữ phát hành.
- Chạy unit tests, assemble debug, lint; xử lý cảnh báo mới trong phạm vi; không dùng baseline/suppression để giấu untranslated/hardcoded.
- Kiểm tra bản cài từ AAB qua internal test hoặc bundletool: language split và đổi locale offline/online. APK debug đầy đủ resources không đủ chứng minh bản Store đổi ngôn ngữ được. Chỉ cấu hình tắt language splitting nếu có bằng chứng cần và chấp nhận tăng dung lượng.

**Nghiệm thu tổng:** hoàn thành ma trận ở mục 5; báo rõ ô chưa chạy; không tuyên bố đã hỗ trợ tốt 37 ngôn ngữ nếu còn bản dịch `needs-review`.

## 5. Ma trận kiểm thử phải lưu kết quả

| Trục | Tình huống | Kỳ vọng |
|---|---|---|
| API | 26, 32, 33, 36 | Chọn/khôi phục locale đúng; Settings app language trên 33+ |
| Locale lifecycle | fresh install, upgrade legacy, restart, process death, foreground/background | Không đổi lại lựa chọn cũ, không mất draft |
| System mode | vi→en khi app sống; `[ru,fr]`; `[ru]`; explicit app locale | Theo danh sách hệ thống khi System; explicit giữ nguyên; English khi không match |
| Tag | id/in, fil/tl, nb/no, sr-Latn/sr-Cyrl, zh-Hans/zh-Hant | Alias đúng, không quảng bá script chưa hỗ trợ |
| UI | 4 tab, camera, chọn cách scan, editor/crop, viewer, ID card, OCR, export, QR, account/VIP/update, quản lý file | Không trộn ngôn ngữ do code; câu đầy đủ; trạng thái lỗi cũng đã dịch |
| Kích thước | hẹp, font 1.0/1.3/2.0, de/fr dài, Arabic, pseudo-locales | Không cắt nút quan trọng; đọc/cuộn được; điều hướng RTL đúng |
| Số/ngày | 0,1,2,3,11,100; en/vi/fr/ar | Đúng plural, format, không crash nội suy |
| OCR | UI vi/document ja; UI en/document vi; UI ar/document en; ar/th unsupported; forced incompatible engine | Chọn theo tài liệu, thông báo đúng, không trả kết quả nhầm như thành công |
| Model | lần đầu offline, chưa tải, đang tải, tải xong offline, hủy | NoText khác ModelUnavailable; scan/PDF vẫn dùng được |
| Dữ liệu | tên Unicode, OCR đa hệ chữ, QR raw, export text, draft | Giữ nguyên nội dung, không đổi tên hay tự dịch |
| Phân phối | debug APK đầy đủ + AAB/internal test | Đổi locale hoạt động sau cài; tài nguyên ngôn ngữ có sẵn hoặc được tải đúng |

## 6. Lệnh và kết quả đã kiểm tra

Chạy tại root bằng PowerShell:

```powershell
python scripts/audit_localization.py > LOCALIZATION_AUDIT_CURRENT.json
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

Lệnh cuối chỉ chạy khi có thiết bị/emulator. Đối với lượt chỉ dịch/resources, chạy `:app:processDebugResources` trước và kiểm tra placeholder; các gói locale/routing phải chạy test hành vi tương ứng, không chỉ compile.

Thực tế trong lần audit này:

- Script inventory đã chạy thành công; các XML trong phạm vi kiểm kê parse được; chưa thấy duplicate key.
- `:app:lintDebug --offline` **không khởi động được**: wrapper không tạo được lock tại `C:\.gradle\wrapper\dists\gradle-9.7.1-bin\...`. Đây là giới hạn môi trường, không phải lint pass hay lỗi application.
- `adb devices` **không khởi động được daemon**: `could not read ok from ADB Server`. Chưa kiểm tra màn hình thật, recreation, fallback Android hay OCR accuracy trên thiết bị.
- Chưa chạy build/unit/instrumentation đầy đủ; không có số đo độ chính xác OCR. Khi triển khai, dùng JDK/Gradle cache đã cấu hình hợp lệ và thiết bị của dự án, không đổi Gradle version chỉ để vượt lỗi môi trường.

## 7. Prompt bàn giao sẵn cho mô hình nhỏ

```text
Đọc PLAN_MULTILINGUAL_2026-09-16.md và chỉ thực hiện gói <Lxx>.
Đọc git diff hiện tại của những file định sửa; giữ toàn bộ thay đổi đã có.
Đọc đúng các file trong gói và phần JSON liên quan; không quét sample android/ios.
Nếu gói quá lớn, chỉ làm tiểu mục <màn hình/locale/40–60 key> rồi báo phạm vi còn lại.
Theo quyết định kỹ thuật và tiêu chí nghiệm thu trong kế hoạch; không tự mở rộng scope.
Không dịch log/protocol/dữ liệu người dùng; không copy English để lấp coverage;
không thêm suppression để che lỗi; không sửa logic xử lý ảnh/thanh toán ngoài gói.
Chạy kiểm tra phù hợp và báo lỗi môi trường riêng với lỗi code.
Kết thúc: nêu thay đổi, file, test pass/fail/chưa chạy và hạng mục kế tiếp.
Không commit, push hay build release trừ khi được yêu cầu riêng.
```

Thứ tự thực hiện mặc định: **L00 → L01 → L02 → L03 → L04a…f → L06 → L07a/b → L08 → L05 chốt nguồn → L09 từng locale → L10**. F06 có thể sửa sớm; L05 phải chốt lại sau khi mô tả OCR cuối cùng ổn định. Ưu tiên phiên bản en/vi sạch và cơ chế locale/OCR đúng trước, sau đó mở rộng bản dịch với cùng điều kiện nghiệm thu.
