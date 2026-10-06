# Kiểm tra sau triển khai và kế hoạch sửa cho mô hình nhỏ

Ngày 2026-09-17 — lượt kiểm tra sau `PLAN_LANGUAGE_ROLLOUT_SMALL_MODEL_2026-09-17.md`.

## 1. Phạm vi và kết quả xác minh

Kiểm tra working tree hiện tại, tập trung các thay đổi đa ngôn ngữ, locale, OCR và các luồng gọi OCR/export liên quan. Đây không phải chứng nhận toàn bộ thuật toán ảnh, thanh toán, Drive hay mọi thiết bị. Chỉ kiểm tra và lập kế hoạch; không sửa production code hoặc các thay đổi chưa commit của người dùng.

| Kiểm tra thực sự chạy | Kết quả |
|---|---|
| Script audit localization | PASS — 585 strings, 7 plurals, 41 locale folders ngoài base |
| 6 tests Python của validator | PASS |
| `:app:testDebugUnitTest --rerun` | PASS — 88 tests, 0 failed/errors/skipped |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` | PASS — báo cáo hiện hành 0 errors, 2124 warnings; các task phân tích Lint được Gradle nhận UP-TO-DATE |
| 3 fixture bổ sung cho validator, tạo trong thư mục tạm | Cả 3 dữ liệu sai đều trả `errors=[]` — xem F07 |
| `adb devices`, sau khi chạy với quyền phù hợp | Daemon chạy, danh sách thiết bị trống |

Không có kiểm thử trực tiếp trên thiết bị/emulator trong lượt này. Không đo leak/RAM thực tế, độ chính xác OCR hay migration runtime. Các phát hiện dưới đây phân biệt đường code xác nhận được với ảnh hưởng runtime cần thử.

### Những vấn đề cũ đã được xử lý

- 84 lỗi MissingQuantity đã hết; validator đã nói rõ giới hạn của nó và có tests.
- Có bộ chọn ngôn ngữ tài liệu OCR thực sự, setter đã được gọi; ngôn ngữ OCR được lưu độc lập với UI.
- Routing cấp trên không còn chấp nhận French/German ép Tesseract thành `vie`, không coi mọi mã lạ là Latin.
- Home/Files/Viewer đã dùng structured results và aggregator, chặn trường hợp xuất Word chỉ có header; phân biệt trang NoText với lỗi.
- ModelUnavailable đã được tạo ở một số đường engine; lỗi ML Kit không còn đồng loạt chuyển thành NoText.
- Nguồn locale hệ thống đã tách khỏi Activity context; migration API 33+ dùng LocaleManager, API cũ có ActivityLifecycleCallbacks để hoàn tất. Những phần này vẫn cần nghiệm thu thiết bị, không báo lại lỗi cũ như chưa sửa.
- Các hardcode camera/MainActivity và lỗi export ViewModel cũ đã được xử lý; sáu locale mở rộng đã có cải thiện nội dung. Các locale khác còn chưa nghiệm thu theo bảng trạng thái, không tự mở đợt dịch mới.

## 2. Các lỗi còn lại

### F01 — P1: PaddleOCR vẫn biến lỗi xử lý thành NoText

**Bằng chứng:** `app/src/main/java/com/tscanner/app/paddleocr/PaddleOcrEngine.kt:140–201`, đặc biệt catch tại 199–201; `utils/TextRecognitionHelper.kt`, hàm `runPaddleOcrStructured`.

Wrapper mới có structured result, nhưng engine bên trong vẫn catch `Throwable`, log rồi trả `""`. Do đó exception inference không đến được catch của wrapper; wrapper coi chuỗi rỗng là `EngineRunResult.NoText`. Các guard session null cũng trả rỗng. `cropTextLine(...) ?: continue` ở dòng 171 còn bỏ qua vùng crop lỗi.

Khi primary Paddle lỗi mà fallback ML Kit cũng NoText, pipeline trả NoText. Nếu một vùng crop hỏng nhưng vùng khác nhận diện được, kết quả thiếu dòng có thể được coi là Success. Aggregator mới không thể phân biệt vì lỗi đã mất trước khi đến nó.

**Mức chắc chắn:** hành vi nuốt lỗi xác nhận từ code; chưa fault-inject ONNX trên thiết bị.

**Sửa:** engine thấp nhất trả/throw lỗi phân loại được; NoText chỉ khi chạy thành công và không thấy chữ. Giữ cancellation. Không bỏ crop thất bại như vùng không có chữ; mặc định coi là lỗi trang thay vì xuất thiếu dòng im lặng. Cho phép fallback đúng script cứu tác vụ nếu nó thực sự thành công.

### F02 — P1: Tesseract còn đổi model ngôn ngữ âm thầm bên trong helper

**Bằng chứng:** `utils/TesseractOcrHelper.kt:108–110,118–137`.

Mặc dù resolver đã đúng, helper vẫn thử `vie -> eng` hoặc `eng -> vie` khi `init` model đầu thất bại. Sau đó trả `EngineRunResult.Success(text)` không có model thực tế. Wrapper vẫn gắn `documentLanguage` theo request và `fallbackUsed=false`.

Tình huống: traineddata tiếng Việt tồn tại nhưng hỏng/không init được, tiếng Anh init được. Tác vụ Việt được nhận diện bằng model Anh nhưng vẫn báo như đã dùng model đúng. Đây là lỗi ở lớp dưới, tests resolver hiện tại không bắt được.

**Sửa:** chỉ init model tương ứng request; model lỗi phải trả ModelUnavailable/Failure, để fallback cùng capability cấp trên xử lý. Nếu thực sự muốn model kết hợp, phải là lựa chọn rõ ràng có metadata, không tự thêm trong gói sửa này.

### F03 — P2: ML Kit recognizer không đóng khi thành công/thất bại bình thường

**Bằng chứng:** `utils/TextRecognitionHelper.kt:520–569`.

Mỗi lần nhận diện tạo một TextRecognizer mới. `close()` chỉ nằm trong `invokeOnCancellation`; success và failure listener chỉ resume continuation. Khi job hoàn tất bình thường, cancellation handler không chạy, nên thiếu giải phóng tường minh tài nguyên client sau từng trang.

**Ảnh hưởng:** nhiều trang/lặp nhiều tác vụ tạo nhiều client mà không đóng; nguy cơ giữ tài nguyên/model/service lâu hơn cần thiết. Chưa đo mức tăng RAM và không khẳng định đã gây OOM.

**Sửa:** đóng đúng một lần ở mọi đường kết thúc hoặc quản lý recognizer dùng chung có owner rõ ràng. Cách nhỏ nhất: mỗi tác vụ sở hữu client và completion cleanup; tách việc ngừng nhận callback khỏi thời điểm task nền thực sự kết thúc. Không recycle bitmap còn được SDK dùng chỉ vì coroutine caller đã hủy.

### F04 — P2: callback OCR sống ngoài lifecycle, có thể trả kết quả cho lần mở màn hình khác

**Bằng chứng:** `TextRecognitionHelper.kt:33,588–606` dùng singleton scope và không trả Job; `ui/home/HomeFragment.kt:550–555`; `ui/tools/ToolsFragment.kt:354–372,433–451`; `MainActivity.kt:279–283` replace và tái sử dụng instance fragment.

Callback không thuộc `viewLifecycleOwner.lifecycleScope`. `isAdded/context != null` chỉ kiểm tra lúc nhận kết quả, không kiểm tra callback thuộc view/job nào.

Tình huống cần kiểm thử: OCR ảnh A ở Home → chuyển tab → quay lại Home trước khi OCR xong → chạy OCR B. Callback A có thể thấy fragment đã được add lại và mở kết quả A; Tools có thể dismiss loading của job mới hoặc tiếp tục export từ tác vụ cũ. Công việc cũng tiếp tục sau khi view bị hủy.

**Mức chắc chắn:** singleton scope/thiếu job token xác nhận từ code; thứ tự UI cần chạy fake chậm hoặc thiết bị, chưa quan sát crash thực tế.

**Sửa:** gọi suspend API trong scope của view, giữ Job và request-generation token; hủy/loại kết quả cũ khi view bị hủy hoặc job mới thay thế. Kiểm tra token sau mỗi suspend trước UI/export. Không chỉ thêm một `isAdded` nữa.

### F05 — P2: lỗi OCR vẫn hiện tiếng Anh thô và mọi ModelUnavailable đều bị gọi là “đang tải”

**Bằng chứng:** `TextRecognitionHelper.kt:74–96,205–211`; `HomeFragment.kt:579` và nhánh Failure trong Tools; helper Tesseract/Paddle.

- `getLocalizedOcrErrorMessage` trả nguyên `result.error` nếu không rỗng. Ví dụ thiếu file trả `File does not exist or is empty`, ảnh hỏng trả `Failed to load bitmap...`; UI Việt vẫn thấy tiếng Anh kỹ thuật.
- `NOT_ENOUGH_SPACE`, lỗi mạng, model packaged init thất bại đều có thể thành ModelUnavailable, rồi UI hiển thị `ocr_model_downloading`. Model lỗi hoặc máy hết dung lượng không được giải quyết bằng việc chờ tải.
- Không có bằng chứng mọi trạng thái này thực sự đang tải; không nên đặt tên tiến trình nếu chưa có download job tương ứng.

**Sửa:** mã lỗi ổn định + tham số; phân biệt thiếu/tải model, hết dung lượng, mạng, model bundled hỏng, ảnh lỗi. Localize ở UI; log kỹ thuật riêng. Các caller dùng chung mapper, không tự toast `result.error`. Nút retry chỉ hứa khả năng mà code thực hiện được.

### F06 — P2: một số nội dung hiển thị chưa phản ánh lựa chọn và locale hiện tại

**Bằng chứng:** `ToolsFragment.kt:350–351,429–430`; `ui/adapter/ManagedFileAdapter.kt:41`; `ui/dialogs/AccountDetailDialog.kt:80`.

- Tools vẫn gắn `ocr_lang_vi_en` cho cả vi và en: tiến trình ghi Việt + Anh dù request chỉ chọn một model. Ngôn ngữ tài liệu zh-Hant có trong OCR catalog nhưng không thuộc UI catalog; dùng `AppLanguageManager.getLanguage` làm label sẽ rơi về “đa ngôn ngữ” thay vì tên đã chọn.
- Hai callsite ngày vẫn gọi `FileUtils.formatDate(timestamp)` không truyền context, nên dùng mẫu cố định `dd/MM/yyyy HH:mm`; các danh sách khác đã dùng formatter locale UI. Chưa đồng nhất ngày giữa các màn hình.
- `OcrResultActivity` nhận engine label đã format trong Intent (`:86–88`), không phải metadata; recreation do đổi locale có thể giữ phần nhãn cũ. Đây là giới hạn kiến trúc cần test trong gói nhỏ, không phải lý do viết lại OCR.

**Sửa:** label ngôn ngữ lấy từ OCR catalog/request; ngày hiển thị truyền UI context; truyền engineId/languageTag/fallback flag để format ở màn hình nhận. Không dịch lại text OCR hoặc đổi tên file đã lưu.

### F07 — P2: validator vẫn bỏ lọt placeholder plural sai và thiếu cả thư mục locale

**Bằng chứng:** `scripts/audit_localization.py:83–91,195–219`; vòng lặp chỉ duyệt các `values-*/strings.xml` đang tồn tại.

Đã chạy fixture độc lập trong thư mục tạm, không chỉnh resources ứng dụng:

| Fixture | Kỳ vọng | Thực tế |
|---|---|---|
| Base plural dùng `%1$d`, bản dịch dùng `%2$d` | Lỗi tham số không được cung cấp | `errors=[]` |
| Hai item cùng `quantity="other"` | Lỗi trùng quantity | `errors=[]` |
| Catalog/config khai báo en/vi/fr nhưng không có locale folder nào | Phát hiện vi/fr thiếu resources | `errors=[]` |

Nguyên nhân: chỉ so kiểu khi index có trong base; index mới bị bỏ qua. Item plural được ép vào dictionary làm mất duplicate. So catalog/config chưa kiểm tra resource mapping thực tế. Lint là gate bổ sung hữu ích nhưng không sửa được tuyên bố quá mức của riêng validator.

**Sửa:** kiểm tra index ngoài tập tham số nguồn/callsite, không làm mất duplicate trước validation; kiểm tra folder mapping canonical/alias với ngoại lệ English dùng base hợp lệ. Một quantity có thể bỏ count để câu tự nhiên, nhưng không được invent `%2$d` khi chỉ có một argument.

## 3. Kế hoạch từng gói cho mô hình nhỏ

Giữ nguyên 37 lựa chọn UI và các bản dịch. Không mở thêm đợt dịch locale. Không làm lại những phần đã sửa; chỉ xử lý findings còn tồn tại. Mỗi lượt một gói hoặc tiểu gói; đọc diff và giữ sửa đổi có sẵn. Không commit/push/build release/phát hành.

### S01 — Sửa ranh giới lỗi Paddle (F01)

**File sở hữu:** `paddleocr/PaddleOcrEngine.kt`, phần wrapper Paddle trong `TextRecognitionHelper.kt`; test mới cho boundary.

1. Đọc mọi đường trả rỗng/session null/catch/crop bỏ qua; tách empty detection thật khỏi lỗi inference/init/crop.
2. Trả cấu trúc lỗi hoặc throw exception cụ thể cho wrapper; giữ CancellationException. Bảo đảm bitmap/tensor/crop được giải phóng trong finally.
3. Giữ fallback ML Kit Chinese đúng script. Success từ fallback được ghi metadata fallback; cả hai không chạy thành công thì không NoText giả.

**Regression bắt buộc:** fake detector throw; recognizer throw ở vùng thứ hai; crop thất bại; detection thành công không có box; primary lỗi/fallback thành công; primary lỗi/fallback NoText. Test phải đi qua engine adapter thực, không chỉ test aggregator với enum tự tạo.

**Đạt khi:** mọi lỗi trên đến UI dưới dạng lỗi hoặc được fallback thành công thật; không xuất thiếu dòng/trang như Success.

### S02 — Loại đổi ngôn ngữ ngầm Tesseract (F02)

**File sở hữu:** `TesseractOcrHelper.kt`, tests init adapter; wrapper chỉ sửa nếu interface cần.

1. Vi chỉ init vie, en chỉ init eng; không thử model khác sau init fail.
2. Model init thất bại trả status, để routing cấp trên fallback phù hợp; không bọc fallback ngầm dưới nhãn Tesseract thành công.
3. Không thêm model hoặc thay thuật toán nhận diện; giữ API cũ nếu còn caller, nhưng không giữ hành vi sai.

**Regression:** fake init vie=false/eng=true → không gọi eng và không Success; chiều ngược lại tương tự; init đúng thành công, không có text, hủy. Chạy lại OcrRoutingTest và tests mới.

### S03 — Quản lý tài nguyên ML Kit (F03)

**File sở hữu:** `TextRecognitionHelper.processMlKitRecognition`, adapter test hoặc fake SDK wrapper nhỏ.

1. Xác định owner client/input và terminal callback. Close exactly once ở success/failure/init exception/cancel.
2. Nếu task SDK không hủy ngay được, không giải phóng bitmap mà task còn dùng. Chọn cleanup theo completion hoặc dùng bản copy có owner riêng; không sửa thuật toán ảnh rộng hơn.
3. Gỡ tham chiếu callback khi hoàn thành; tránh double resume/close khi cancel đồng thời success.

**Regression:** đếm create/close cân bằng qua 100 fake requests; cancel trước/sau completion; callback đến muộn; synchronous throw. Instrumentation nhiều trang đo tài nguyên khi có thiết bị, chưa đo thì ghi rõ.

### S04 — Gắn OCR vào lifecycle và chặn kết quả cũ (F04)

**Phụ thuộc:** S03 nếu thay API cancellation. Chia S04a Home, S04b Tools, mỗi lượt có tests riêng.

1. Caller dùng suspend API trong `viewLifecycleOwner.lifecycleScope`; lưu Job/token khi bắt đầu. Helper không tự giữ Activity bằng singleton scope cho đường UI này.
2. Hủy/invalid token khi onDestroyView hoặc job mới. Token được kiểm tra trước điều hướng, toast, dismiss loading và trước tạo/export file.
3. Loading dialog thuộc job đang chạy; job cũ không được dismiss dialog mới. Khi thay view, không “hồi sinh” callback cũ chỉ vì isAdded lại true.
4. Rà caller callback cũ còn dùng sau migration; xóa wrapper không còn cần hoặc yêu cầu caller cung cấp scope, không rewrite module khác.

**Regression:** fake OCR A chậm → rời/đến lại tab → B → A trả trước/sau B; chỉ B được tác động UI/export. Hủy khi đổi locale/recreation, không toast lỗi do cancellation. Cần instrumentation để nghiệm thu UI; JVM pure test không đủ.

### S05 — Localize và phân loại thông báo lỗi (F05)

**Phụ thuộc:** status cuối cùng của S01/S02. Chia S05a model/mapper, S05b caller từng màn hình.

1. Error code/metadata cho missing input, decode, network/model pending, storage full, bundled model init, engine failure. Không lưu câu đã dịch trong engine.
2. Mapper chung dùng resources English/Việt và bổ sung những key mới cần thiết ở locale đóng gói theo lượt nhỏ; không copy English để giả bản dịch hoàn tất. Không mở đợt dịch toàn file.
3. Chỉ dùng “đang tải” khi có trạng thái tải được xác nhận; nếu chưa sẵn sàng thì nói chưa sẵn sàng và hành động có ích. Hết dung lượng phải nói giải phóng dung lượng; model bundled hỏng không hứa chờ download.
4. Home/Tools/Viewer dùng mapper, không lấy raw throwable/message. Log vẫn giữ nguyên dữ liệu chẩn đoán.

**Regression:** tạo từng status trong vi/en, không lộ literal English ở UI Việt; mã NOT_ENOUGH_SPACE không thành downloading; lỗi input không thành NoText. Check resource placeholders và lint.

### S06 — Đồng bộ label/ngày/metadata hiển thị (F06)

Chia S06a Tools labels + ngày, S06b metadata kết quả OCR.

- Tools gọi tên ngôn ngữ từ OCR catalog với request snapshot: vi chỉ Việt, en chỉ Anh, zh-Hant ghi đúng Traditional; không dùng UI catalog để diễn giải OCR.
- ManagedFileAdapter/AccountDetailDialog truyền UI context cho formatter; không đổi format ID/path/file name đã lưu.
- OcrResultActivity nhận metadata có cấu trúc; format ở locale hiện tại sau recreation. Hỗ trợ đường Intent cũ an toàn nếu cần; dữ liệu OCR không được dịch.

**Đạt khi:** UI en/OCR vi, UI vi/OCR en, zh-Hant label đúng; ngày en/vi/fr nhất quán giữa danh sách; đổi locale khi mở kết quả không giữ nhãn fallback bằng ngôn ngữ cũ.

### S07 — Bổ sung validator regression (F07)

**File sở hữu:** `scripts/audit_localization.py`, `scripts/test_audit_localization.py`.

- Thêm đúng 3 fixtures đã chứng minh trước khi sửa; chúng phải thất bại với checker cũ.
- Kiểm tra duplicate quantity trước dictionary, index mới không có trong base/callsite phải lỗi.
- Catalog/config/resources mapping phải kiểm tra cả thiếu toàn folder; English có thể dùng base, alias legacy map đúng theo catalog. Không yêu cầu mỗi alias có một thư mục nếu fallback canonical hợp lệ đã được xác minh.
- Giữ trường hợp bỏ placeholder count ở singular/dual hợp lệ; không áp equality mù giữa mọi quantity.

**Đạt khi:** fixture lỗi bị bắt; fixture hợp lệ vẫn pass; audit source thật pass; Lint vẫn là gate quantity ngôn ngữ. Không tuyên bố script kiểm tra natural-language quality.

### S08 — Nghiệm thu tích hợp, cập nhật trạng thái đúng bằng chứng

**Phụ thuộc:** S01–S07. Không tự mở rộng dịch các locale chưa nghiệm thu.

1. Chạy tests Python, unit tests thực sự, assemble và Lint. Ghi kết quả mới, không copy số 88/2124 cố định.
2. Khi có thiết bị: API 26/32 và 33+; migration legacy, System, đổi UI trong lúc OCR; request A/B; model thiếu/hỏng; bộ ảnh nhiều trang có trang lỗi/trắng; retry/cancel.
3. Native smoke: Tesseract vi/en và ML Kit ja/ko/hi/Latin/Chinese; Paddle lỗi inference/crop được fault-inject. Không tuyên bố OCR accuracy theo ngôn ngữ nếu chỉ test routing.
4. Kiểm tra AAB language split khi có điều kiện; không upload/phát hành. Update `LANGUAGE_ROLLOUT_STATUS.md` bằng kết quả, giữ “chưa kiểm thử thiết bị” nếu thiếu thiết bị.

**Thứ tự:** S01 → S02 → S03 → S04a/b → S05a/b → S06a/b → S07 → S08. Có thể giao S07 trước để cải thiện gate; mỗi lượt một gói, không yêu cầu mô hình nhỏ làm toàn bộ một lần.

## 4. Prompt bàn giao

```text
Chỉ thực hiện gói <Sxx/tiểu gói> trong
REVIEW_AND_FIX_PLAN_POST_ROLLOUT_2026-09-17.md.
Đọc finding Fxx liên quan, source hiện tại và git diff trước khi sửa.
Giữ các thay đổi chưa commit; không sửa lại những lỗi đã được xử lý đúng.
Không thêm ngôn ngữ/model, không xóa/ẩn 37 lựa chọn, không dịch hàng loạt.
Viết regression test bắt lỗi ở boundary thực đang sai, không chỉ test enum/pure helper.
Sửa trong phạm vi gói; chạy test tương ứng. Không suppress lỗi để làm build xanh.
Kết thúc báo: file đổi, lỗi đã sửa, test pass/fail/chưa chạy, rủi ro còn lại.
Cập nhật LANGUAGE_ROLLOUT_STATUS.md theo bằng chứng; không báo nghiệm thu thiết bị
khi chỉ có JVM tests. Không commit/push/build release/phát hành.
```

Lệnh xác minh dùng trong lượt audit:

```powershell
python scripts/audit_localization.py
python -m unittest scripts/test_audit_localization.py
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain
```

Cache Gradle cần quyền ghi thích hợp; lỗi quyền không phải lỗi ứng dụng. Cảnh báo Lint 2124 không phải 2124 lỗi hành vi: phần lớn là TypographyEllipsis (1716); vẫn cần phân loại accessibility/plural còn lại trong QA, không che toàn bộ bằng baseline.
