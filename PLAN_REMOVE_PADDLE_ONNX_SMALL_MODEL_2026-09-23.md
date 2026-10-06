# Kế hoạch loại bỏ PaddleOCR và ONNX an toàn — 23/09/2026

## 1. Mục tiêu và giới hạn

Tài liệu để giao từng gói cho mô hình nhỏ. Phiên lập kế hoạch chỉ đọc code và tạo tài liệu; chưa sửa production, chưa chạy build/test mới. Không thể bảo đảm tuyệt đối không có lỗi chỉ bằng đọc code; chỉ nghiệm thu sau các cổng kiểm tra bên dưới.

Kết quả cần đạt:
- APK/AAB không còn model Paddle, runtime ONNX hoặc mã thực thi Paddle.
- Tiếng Trung giản thể/phồn thể dùng ML Kit Chinese đang có; không thêm engine/dependency OCR mới.
- Không còn lựa chọn Paddle trong UI; cấu hình engine cũ `paddle` chuyển thành `auto`.
- Chỉ dọn model Paddle cũ trong bộ nhớ riêng của ứng dụng; giữ nguyên tài liệu, ảnh, PDF, OCR đã sửa, Tesseract và cấu hình khác.
- Tài liệu có metadata `engineId="paddle"` vẫn đọc/chỉnh sửa/xuất được. Metadata lịch sử KHÔNG phải cấu hình chọn engine.

Không đổi applicationId, chữ ký, minSdk, ABI, version hay chính sách nén các tài nguyên khác để làm đẹp số đo. Không sửa camera/Drive/editor ngoài điểm tích hợp bắt buộc. Không cập nhật thư viện ML Kit trong công việc này. Không gỡ Tesseract, ML Kit Latin/Chinese/Japanese/Korean/Devanagari hoặc language-id.

Working tree đang có nhiều thay đổi có sẵn. Không reset/clean/stash toàn bộ, không checkout đè, không commit tất cả file. Trước mỗi gói kiểm tra diff và đọc lại code; số dòng dưới đây là mốc khảo sát, có thể dịch chuyển.

## 2. Bằng chứng hiện tại và rủi ro

| Điểm | Bằng chứng trong repo | Rủi ro nếu gỡ thiếu |
|---|---|---|
| Dependency/nén | `app/build.gradle:40,90-91`, `app/proguard-rules.pro:14-16` | Còn runtime nặng hoặc import không compile |
| Khởi tạo nền | `TScannerApplication.kt:60-68` | Gọi class đã xóa; model tiếp tục được tạo lại |
| Model trên máy | `paddleocr/PaddleOcrEngine.kt:54-57,103-107` | Bỏ assets nhưng dữ liệu cũ vẫn chiếm bộ nhớ |
| Preference | `utils/TextRecognitionHelper.kt:37-38,158-165` | Getter trả nguyên `paddle`, không có migration |
| Yêu cầu OCR trực tiếp | `TextRecognitionHelper.kt:471-484` | Kiểm tra tương thích trước chuẩn hóa; request cũ có thể lỗi |
| Nhánh Trung | `TextRecognitionHelper.kt:565-593,616-625,805-806` | Mất OCR Trung nếu chỉ xóa Paddle mà không nối ML Kit |
| Registry/routing | `utils/AppLanguageManager.kt:28`; `utils/OcrModels.kt:196-201,357,382-428` | Enum, danh sách engine, manual routing lệch nhau |
| UI | `ui/dialogs/OcrEngineSelectionDialog.kt`, `OcrLanguageAdapter.kt:68`, `res/layout/dialog_ocr_engine_selection.xml:158-202` | ViewBinding/resource còn tham chiếu đã xóa |
| Nội dung dịch | `res/values*/strings.xml`: `about_engine`, `ocr_engine_auto_desc`, `ocr_engine_paddle*` | About hoặc mô tả vẫn quảng bá Paddle |
| Kết quả cũ | `ocr/model/OcrDocument.kt:429,455,493`; `ui/ocr/reader/OcrSelectionController.kt:92` | Xóa metadata/line fallback làm hỏng đọc kết quả cũ |
| Model ML Kit | `AndroidManifest.xml:94-95`, `TextRecognitionHelper.kt:737-797` | Chưa tải model bị báo nhầm trang trắng hoặc mất trang |

Các rủi ro trên suy ra từ đường gọi hiện tại; chưa phải lỗi đã tái hiện của một bản gỡ Paddle. Bản gỡ chưa tồn tại.

Lưu ý hai loại Auto khác nhau: **engine Auto** và **ngôn ngữ tài liệu AUTO**. Code hiện dùng `TESSERACT_PRIMARY` cho languageMode AUTO/VI_EN. Không nhân việc gỡ Paddle để thay thuật toán tự nhận diện ngôn ngữ. Tiếng Trung bắt buộc kiểm tra với languageMode MANUAL + zh-Hans/zh-Hant và engine Auto/ML Kit. Nếu muốn ngôn ngữ AUTO nhận tiếng Trung tốt hơn, lập gói riêng sau.

ML Kit Chinese đang là bản unbundled. Model phải được Google Play Services tải trước khi OCR; khai báo tự tải trong manifest không chứng minh đã sẵn sàng. Giữ xử lý thiếu model, offline, thiếu dung lượng, lỗi thực thi và hủy tác vụ riêng biệt. Tham khảo: https://developers.google.com/ml-kit/tips/installation-paths . Không hứa OCR Trung offline ngay sau cài mới; sau khi model sẵn sàng cần kiểm tra offline thực tế.

## 3. Thứ tự và hợp đồng chung

Thực hiện tuần tự **P00 → P01 → P02 → P03 → P04 → P05 → P06 → P07**. Mỗi lượt một gói, không chạy các agent sửa chung file đồng thời. P01–P03 vẫn giữ class/runtime/model để trạng thái trung gian compile được. Chỉ xóa vật lý ở P04 khi các caller đã được chuyển. Không phát hành các trạng thái trung gian.

Mỗi gói phải báo: file sửa; diff thuộc gói; test mới; lệnh và kết quả thật; phần chưa xác minh; điểm dừng. Test phải gọi logic production, không sao chép router/migration vào test rồi tự kiểm tra bản sao. Dùng test seam nhỏ khi cần giả lập SDK, không dựng kiến trúc OCR mới. Nếu có lỗi nền, ghi riêng với bằng chứng trước/sau, không bỏ test để làm xanh.

### P00 — Ghi baseline và cố định phạm vi (chỉ đọc/ghi báo cáo)

**Phạm vi:** báo cáo `docs/paddle-removal/baseline.md`, log/build artifacts riêng; không sửa production.

1. Ghi git status/diff, HEAD, thời điểm, cấu hình build và môi trường Gradle; bảo toàn thay đổi có sẵn.
2. Rà `paddle|onnx|PADDLE_OCR_V4|ENGINE_MODE_PADDLE` trong main/test/Gradle/resources/ProGuard. Phân loại execution, preference migration, metadata lịch sử và tài liệu lưu trữ.
3. Chạy bộ chuẩn trước thay đổi; lưu số test, failures, lint warnings. Các báo cáo re-audit cũ chỉ là thông tin nền, không coi là kết quả hiện tại.
4. Build APK/AAB baseline từ checkout hiện tại nếu có thể; sao chép vào thư mục riêng trước build candidate, ghi SHA-256. Không ghi đè `app/release` hay baseline dự án khác.
5. Chuẩn bị bộ nâng cấp trên emulator/thiết bị thử: cài bản cũ cùng applicationId/chữ ký; chọn Paddle, chọn zh-Hant; tạo tài liệu và OCR đã sửa; ghi inventory/hash dữ liệu cần giữ. Không uninstall/clear data khi kiểm tra nâng cấp.

**Nghiệm thu:** baseline có nguồn gốc rõ; lỗi nền tách riêng; không đụng dữ liệu người dùng thật. Không có thiết bị thì ghi P07 còn chờ.

**Prompt:** Đọc `PLAN_REMOVE_PADDLE_ONNX_SMALL_MODEL_2026-09-23.md`, chỉ làm P00. Ghi baseline và phạm vi, bảo toàn working tree; không sửa production. Báo lệnh thực chạy và dừng trước P01.

### P01 — Chuẩn hóa cấu hình legacy và bảo vệ request đầu vào

**Phạm vi:** `utils/TextRecognitionHelper.kt`; helper thuần nhỏ nếu cần; `PaddlePreferenceMigrationTest.kt` mới và test tích hợp request. Không xóa engine/UI/model trong gói này.

**Tái hiện cần bảo vệ:** prefs `tscanner_ocr_prefs` / `key_preferred_ocr_engine` = `paddle`; mở dialog hoặc OCR ngay sau nâng cấp. Trường hợp khác: tạo trực tiếp `OcrRequest(engineMode="paddle")` bỏ qua getter.

1. Dùng một hàm chuẩn hóa: đúng giá trị legacy `paddle` → `auto`; giữ auto/mlkit/tesseract. Không mở rộng thành việc âm thầm chấp nhận mọi engine lạ; giá trị lạ trong request vẫn theo chính sách lỗi hiện tại. Null/missing prefs giữ mặc định Auto.
2. Getter trả giá trị chuẩn hóa ngay và ghi lại chỉ key engine nếu là legacy; setter không ghi lại `paddle`. Không clear preferences, không đổi manual language hoặc languageMode.
3. Chuẩn hóa một lần ở đầu `recognizeInternalStructured`, trước `isEngineCompatible`; toàn bộ phần sau dùng effective request nhất quán, không có nhánh còn đọc engine cũ. Không sửa request gốc bằng side effect.
4. Migration phải lặp lại an toàn: process chết trước khi preference ghi xong thì lần sau chuẩn hóa tiếp. Không chỉ dựa vào cờ chạy một lần lúc startup. Không chặn main thread để chờ ghi đĩa.
5. Không đổi `engineId="paddle"` trong tài liệu đã lưu, export snapshot hoặc kết quả lịch sử thành `auto`/`mlkit_chinese`.

**Test:** legacy→Auto trong getter và persistence; lần đọc thứ hai; setter legacy; giữ ba engine hợp lệ; giữ ngôn ngữ, preference khác; request trực tiếp legacy không báo incompatible trước routing; engine lạ không được âm thầm chạy. Kiểm tra reader mở metadata legacy độc lập migration.

**Nghiệm thu:** cả đường preference và request trực tiếp không còn thực thi Paddle do giá trị cấu hình legacy; không thay metadata tài liệu. Test migration gọi production adapter với SharedPreferences test double hoặc instrumentation, không chỉ test hàm string.

**Prompt:** Đọc kế hoạch này, chỉ làm P01 sau P00. Thêm chuẩn hóa legacy ở preference và đầu pipeline trước compatibility, giữ nguyên metadata lịch sử và cấu hình khác. Chạy test tập trung, compile và dừng trước P02.

### P02 — Chuyển routing tiếng Trung sang ML Kit Chinese

**Phạm vi:** `utils/AppLanguageManager.kt`, `utils/OcrModels.kt`, `utils/TextRecognitionHelper.kt`, `OcrRoutingTest.kt`, `ChineseMlKitRoutingTest.kt` mới. Không xóa SDK/model ở gói này.

1. Thay enum `PADDLE_OCR_V4` bằng `MLKIT_CHINESE` sau khi xác nhận không có serialization enum name/ordinal ở caller khác. Không dùng replace-all với metadata string.
2. Map zh/zh-Hans/zh-Hant và alias zh-CN/zh-SG/zh-TW/zh-HK/zh-MO qua normalization hiện có về nhánh Chinese. Không đổi ngôn ngữ tài liệu gốc thành tiếng Anh.
3. Registry tiếng Trung chỉ trả `[auto, mlkit]`. Auto và manual ML Kit gọi trực tiếp `runMlKitChineseStructured`; bỏ fallback qua Paddle, bỏ runPaddle/import và nhánh manual Paddle sau khi P01 đã nhận legacy.
4. Success có engineId `mlkit_chinese`, fallbackUsed=false khi đi thẳng, giữ pageDocument/bounding boxes và language metadata theo hợp đồng hiện tại. Không tự nâng cấp tài liệu OCR cũ.
5. Giữ NoText chỉ cho nhận dạng thành công nhưng không có chữ. ModelUnavailable/Failure không biến thành NoText hoặc Success rỗng; CancellationException được truyền tiếp.
6. Không tự route sang Latin/Tesseract khi Chinese thiếu model. Giữ Chinese dependency và `ocr_chinese` trong manifest.
7. Nếu cần giữ hằng legacy để P03 compile, đổi chú thích thành chỉ dành migration; không để nó là engine được hỗ trợ. Class Paddle tạm còn để P04 xóa.

**Test:** ma trận alias × engine Auto/ML Kit; request legacy sau chuẩn hóa; gọi adapter Chinese đúng một lần, không Paddle; Success có layout; NoText; thiếu model; lỗi; cancel. Regression vi/VI_EN, Latin, ja, ko, devanagari và unsupported giữ hành vi trước. Test cả dispatcher production, không chỉ enum resolver.

**Nghiệm thu:** không còn lời gọi thực thi Paddle trong TextRecognitionHelper; Chinese còn OCR và kết quả có cấu trúc; không phát sinh thay đổi chính sách languageMode AUTO.

**Prompt:** Đọc kế hoạch này, chỉ làm P02 sau P01. Chuyển toàn bộ nhánh Trung sang MLKIT_CHINESE bằng SDK hiện có, giữ lỗi/NoText/cancel và layout đúng hợp đồng. Không đổi ngôn ngữ AUTO; không xóa SDK. Chạy test routing và dừng trước P03.

### P03 — Xóa lựa chọn Paddle và cập nhật đủ bản dịch

**Phạm vi:** `OcrEngineSelectionDialog.kt`, `OcrLanguageAdapter.kt`, `dialog_ocr_engine_selection.xml`, toàn bộ `app/src/main/res/values*/strings.xml`, helper hiển thị engine trong `TextRecognitionHelper.kt`.

1. Xóa cả row XML, listener, checkmark, compatibility/alpha và binding Paddle trong cùng thay đổi; giữ thứ tự và thao tác Auto/Tesseract/ML Kit.
2. Nhãn tiếng Trung thành ML Kit; cập nhật `about_engine`, `ocr_engine_auto_desc`; xóa resource lựa chọn Paddle sau khi hết reference.
3. Engine lịch sử `paddle` vẫn có nhãn đọc được ở chi tiết kết quả nếu caller dùng; có thể giữ tên literal lịch sử hoặc resource riêng như `ocr_engine_legacy_paddle`. Không hiển thị nó như lựa chọn hiện hành. Đừng khiến chuỗi lịch sử phải giữ resource lựa chọn đã xóa.
4. Rà tất cả locale thực tế, không chỉ tiếng Việt/English; không sửa `locale_archive` và báo cáo cũ chỉ để search bằng 0.
5. Không làm dialog trống/không chọn gì sau nâng cấp: getter P01 phải trả Auto và dấu chọn khớp.

**Test:** compile ViewBinding/resources; validator localization theo cách dùng của script hiện có; mở dialog sau preference legacy, chọn lại ML Kit/Auto; ngôn ngữ Trung chỉ có các lựa chọn tương thích; About và mô tả tất cả locale không quảng bá Paddle. Screenshot thiết bị ở P07.

**Nghiệm thu:** không còn lựa chọn UI Paddle; không mất nhãn metadata lịch sử; không dangling resource/binding. Không bỏ bảo vệ lựa chọn line-only trong reader.

**Prompt:** Đọc kế hoạch này, chỉ làm P03 sau P02. Gỡ row và binding Paddle đồng bộ, sửa nhãn/About/mọi locale, giữ khả năng hiển thị engine lịch sử. Chạy kiểm tra resources và build, dừng trước P04.

### P04 — Loại bỏ class, assets, dependency và khởi tạo nền

**Phạm vi:** `TScannerApplication.kt`, `paddleocr/PaddleOcrEngine.kt`, `app/src/main/assets/paddleocr/`, `app/build.gradle`, `app/proguard-rules.pro`, test phụ thuộc class Paddle.

1. Xóa coroutine khởi tạo Paddle trước khi nối cleanup. Không đụng coroutine Tesseract, đồng bộ Drive và cleanup scan.
2. Rà lại toàn bộ import/caller của `PaddleOcrEngine`, `ai.onnxruntime`, các kiểu `paddleocr.OcrPoint/RecognizedLine`. Không xóa `ocr.model.OcrPoint` — đây là kiểu khác đang dùng chung.
3. Xóa class/package Paddle và bốn assets: `ch_PP-OCRv4_det.onnx`, `ch_PP-OCRv4_rec.onnx`, `ppocr_keys_v1.txt`, `vi_dict.txt`.
4. Gỡ dependency `com.microsoft.onnxruntime:onnxruntime-android:1.21.1`, rule keep/dontwarn ONNX, riêng mục `onnx` trong noCompress. Giữ `traineddata` và các cấu hình khác.
5. Xử lý `PaddleOcrEngineTest.kt`: bỏ test inference của engine đã xóa; chuyển các test generic về fallback/error/layout còn giá trị sang bộ thích hợp. Không xóa hàng loạt test có chữ paddle. Giữ fixture legacy trong OcrDocumentModel/MultiPage/Selection và test line-only.
6. Các tham chiếu còn lại chỉ được thuộc migration/cleanup/metadata lịch sử/test tương ứng/tài liệu lịch sử. Ghi danh sách ngoại lệ, không yêu cầu toàn repo sạch mọi chữ paddle.

**Test:** full compile và unit suite; dependency report releaseRuntimeClasspath không còn ONNX; APK/AAB candidate không chứa assets Paddle, libonnxruntime.so hoặc libonnxruntime4j_jni.so cho bất kỳ ABI nào.

**Nghiệm thu:** xóa được runtime thực; không lỗi unresolved reference; bộ generic và legacy còn bảo vệ dữ liệu. Không tự xóa build cache hoặc APK baseline.

**Prompt:** Đọc kế hoạch này, chỉ làm P04 sau P03. Xác nhận caller đã chuyển rồi gỡ startup/class/assets/ONNX/ProGuard, bảo toàn kiểu OcrPoint chung và test legacy. Chạy unit/build, dừng trước P05.

### P05 — Dọn đúng model cũ, không đụng dữ liệu người dùng

**Phạm vi:** helper mới `utils/LegacyPaddleCleanup.kt`, điểm gọi nền trong `TScannerApplication.kt`, `LegacyPaddleCleanupTest.kt` mới. Chỉ triển khai sau khi P04 không còn writer/init Paddle.

**Thiết kế cố định để mô hình nhỏ không xóa quá rộng:**
1. Root duy nhất là child cố định `File(context.filesDir, "paddleocr")`; không nhận đường dẫn từ preference, intent, metadata hay input người dùng.
2. Trước xóa, xác minh parent/target canonical nằm đúng child trực tiếp dự kiến của filesDir. Không theo symbolic link; dùng kiểm tra NOFOLLOW_LINKS phù hợp minSdk 26 và kiểm tra lại trước thao tác. Nếu root là link hoặc vị trí bất thường: bỏ qua, log chẩn đoán gọn.
3. Chỉ xóa file thường trực tiếp trong allowlist bốn tên của P04, không duyệt/xóa đệ quy. Nếu tên đó là thư mục/link thì bỏ qua. Với mỗi file kiểm tra canonical parent đúng root và không phải symlink.
4. Xóa thư mục `paddleocr` bằng thao tác xóa thư mục rỗng sau khi xử lý. Có file lạ/subfolder thì giữ nguyên và báo skipped; không cố giải phóng đủ MB bằng cách xóa rộng hơn.
5. Chạy ở Dispatchers.IO, không đợi ở main; lỗi I/O/quyền/xóa thất bại không crash, không chặn OCR/khởi động. Không nuốt CancellationException nếu helper suspend.
6. Idempotent: thiếu root/file là bình thường, xóa dở hoặc process chết thì lần khởi động sau thử lại phần còn lại. Không cần cờ completed; tránh cờ ghi thành công trước khi xóa thực. Helper kiểm tra ngắn mỗi lần mở app, không quét toàn bộ filesDir.
7. Không xóa filesDir, cacheDir, external storage, OCR manifests/drafts, ảnh/PDF, thư mục Tesseract, dữ liệu ML Kit hoặc preference khác. Không đưa đường dẫn/tên tài liệu người dùng vào log.

**Test filesystem thật trong thư mục tạm:** root thiếu; đủ model; thiếu một phần; lần chạy thứ hai; file lạ và folder lạ; model-name là folder; root/file symlink trỏ ra ngoài nếu môi trường hỗ trợ; lỗi deletion qua seam trả false/exception; mô phỏng xóa dở rồi chạy lại. Đặt sentinel trong Tesseract, OCR, sibling và target bên ngoài; hash phải giữ nguyên. Không mô phỏng permission bằng chmod rồi mặc định test đã có hiệu lực trên Windows.

**Nghiệm thu:** chỉ allowlist file thường bị xóa; không xóa theo link/đệ quy; không crash khi thất bại; dữ liệu người dùng không đổi. Cài mới không tạo lại folder/model. Nếu môi trường không tạo được symlink, ghi test đó chưa chạy và kiểm tra bổ sung trên Android.

**Prompt:** Đọc kế hoạch này, chỉ làm P05 sau P04. Viết cleanup allowlist filesDir/paddleocr không đệ quy/không theo link, chạy IO, idempotent và không chặn app. Thêm test sentinel và fault injection; không xóa dữ liệu thật. Dừng trước P06.

### P06 — Hồi quy tích hợp, tương thích dữ liệu và xác minh dung lượng

**Phạm vi:** test/fixtures/script kiểm tra artifact và báo cáo `docs/paddle-removal/verification.md`. Nếu phát hiện lỗi production, trả về đúng gói gây lỗi; không tiện tay refactor.

1. Chạy toàn bộ tests, assembleDebug, lintDebug; build release APK/AAB sau các sửa đổi cuối. Mỗi lần đổi production sau đó phải chạy lại các kiểm tra bị ảnh hưởng.
2. Fixture JSON legacy `engineId=paddle`, dòng không có token, bảng, chỉnh sửa, nhiều trang: đọc → chỉnh sửa → lưu/mở lại → export; giữ text, hình học, source image và dữ liệu edit. Không thay engineId lịch sử bằng nhãn engine mới. Không chạy lại OCR chỉ vì tên engine cũ.
3. Kiểm tra preference legacy → effective Auto → MANUAL zh-Hant → Chinese adapter → structured page → reader/export. Trường hợp model unavailable không được đánh dấu trang trắng, không công bố batch nhiều trang thành công đầy đủ khi có lỗi, không ghi đè kết quả cũ bằng nội dung rỗng.
4. Rà nguồn/res/dependency/artifact lần cuối; mọi exception legacy có lý do. Giữ test của Tesseract, ML Kit, autosave, export và lựa chọn line-only.
5. Đo file bằng byte và MB thập phân, SHA-256, danh sách ZIP entry cùng kích thước nén/raw. So baseline cùng cấu hình; không dùng debug so release. `scripts/measure_ocr_size.ps1` có thể dùng với tham số baseline/candidate rõ ràng sau khi đọc script, không dùng RecordBaseline ghi đè baseline cũ.

Lệnh từ repo root (mô hình thực hiện phải ghi kết quả thật):
```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline --console=plain
```
Test tập trung dùng `--tests com.tscanner.app.<TênTest>` theo class thực tế. Cache/wrapper Access denied cần kiểm tra GRADLE_USER_HOME và quyền ghi, không mặc định đó là lỗi code. Không lấy test counts trong tài liệu trước làm kết quả lượt này.

Số tham khảo đã đo trong phiên nghiên cứu: APK release 75.507.865 byte; AAB 44.799.144 byte; entry Paddle+ONNX chiếm lần lượt 46.708.844 và 26.758.079 byte. Sau gỡ dự kiến APK ~28,80 MB, AAB ~18,04 MB; đây không phải ngưỡng cứng hoặc số thực của candidate. DEX/alignment làm delta khác nhẹ. AAB không phải download size Google Play. Model được copy vào filesDir hiện khoảng 15,60 MB; chỉ tuyên bố thu hồi khi nâng cấp và đo thực.

**Nghiệm thu:** test/build không có regression mới, artifact không còn Paddle/ONNX, metadata cũ tương thích, baseline/candidate so sánh hợp lệ. Lint warnings và lỗi nền ghi rõ; chưa qua P07 thì chỉ được ghi “đạt kiểm tra máy build”.

**Prompt:** Đọc kế hoạch này, chỉ làm P06 sau P05. Kiểm thử production integration và dữ liệu legacy, chạy bộ chuẩn/release và đo ZIP/dependency chính xác. Tách lỗi nền, không xóa test để làm xanh, dừng trước P07 và ghi rõ chưa xác minh trên thiết bị.

### P07 — Nghiệm thu Android, nâng cấp và mất model

**Phạm vi:** thiết bị/emulator thử, báo cáo `docs/paddle-removal/device-acceptance.md`; không thao tác phá dữ liệu người dùng thật. Không publish tự động.

| Ca bắt buộc | Kết quả cần đạt |
|---|---|
| Nâng cấp tại chỗ, prefs Paddle + zh-Hant | Cùng chữ ký/applicationId, không uninstall; UI chọn Auto, zh-Hant còn nguyên |
| Root model cũ đầy đủ | File allowlist biến mất; app restart không tạo lại; hash tài liệu/Tesseract/sentinel giữ nguyên |
| Root có file lạ hoặc xóa lỗi | File lạ còn, app không crash; phần lỗi có thể thử lại lần mở sau |
| Cài mới, offline, Chinese model chưa có | Thông báo chưa sẵn sàng/thất bại phù hợp; không “không có chữ”, không mất ảnh/tài liệu |
| Cho phép tải model, thử lại rồi offline | OCR Chinese thành công khi model sẵn sàng; Auto engine và ML Kit thủ công đều hoạt động |
| Play Services thiếu/lỗi trên môi trường thử | OCR Chinese lỗi có kiểm soát; mở app, xử lý ảnh và nhánh không phụ thuộc vẫn dùng được |
| OCR zh-Hans và zh-Hant | Chữ, dấu câu, hộp chữ, thứ tự đọc và export có thể sử dụng; so cùng bộ ảnh trước/sau |
| Ảnh bảng/nhỏ/nghiêng/mixed Chinese-English | Kiểm tra chính xác và lựa chọn vùng; ghi sai khác, không chỉ kiểm tra có text |
| Tài liệu OCR Paddle cũ, line-only | Mở/chọn/sửa/lưu/mở lại/xuất không crash và không mất edit |
| OCR nhiều trang có một trang thiếu model/lỗi | Không báo thành công đầy đủ hoặc bỏ trang âm thầm; giữ dữ liệu để thử lại |
| Thoát/xoay màn hình/hủy OCR | Không callback vào màn hình đã hủy, không crash, không mất nội dung đã lưu |
| Tiếng Việt/VI_EN/Latin/Nhật/Hàn/Hindi | Không regression so baseline |
| Smoke chụp/crop/filter/PDF/QR/Drive | Luồng đang hoạt động trước đó vẫn hoạt động; không tuyên bố đã audit toàn bộ Drive |

Kiểm tra ARM64 bắt buộc và ARM32 nếu vẫn phát hành ABI đó; nếu thiếu thiết bị thì ghi rõ phần còn thiếu, không ghi nghiệm thu toàn phần. Ảnh test chứa chữ Trung cần ground truth hoặc người đọc được tiếng Trung đánh giá, không lấy “không crash” thay cho độ chính xác.

**Nghiệm thu:** đủ bằng chứng nâng cấp, không mất dữ liệu, thiếu model không thành trang trắng, Chinese hoạt động sau tải. Nếu chất lượng giảm không chấp nhận được hoặc lỗi cũ chặn ca kiểm thử, ghi blocker và dừng phát hành; không tự khôi phục dependency hoặc sửa ngoài scope.

**Prompt:** Đọc kế hoạch này, chỉ làm P07 sau P06 trên môi trường thử. Thử nâng cấp tại chỗ, cleanup an toàn, thiếu model/offline, Chinese layout và dữ liệu OCR legacy. Ghi thiết bị/lệnh/kết quả thật từng ca, không publish. Nếu không có thiết bị, để các ca là CHƯA CHẠY.

## 4. Cổng phát hành và dừng an toàn

Chỉ kết luận “sẵn sàng phát hành phần gỡ Paddle” khi P00–P07 đạt, không có regression mới, không mất dữ liệu, không còn runtime/model trong artifact. Build xanh không đủ chứng minh nâng cấp, model download hoặc OCR chính xác.

Nếu gói lỗi: dừng ở gói đó, ghi nguyên nhân và giữ baseline/candidate để điều tra. Nếu cần hoàn tác, chỉ hoàn tác diff do chính gói tạo dựa trên bản chụp trước gói; không dùng git reset --hard hoặc checkout toàn file đang có sửa của người dùng. Không hạ version/cài đè APK cũ lên dữ liệu thật để thử rollback. Cleanup chỉ loại dữ liệu model tái tạo được, không phải tài liệu.

Mẫu bàn giao sau mỗi gói:
```text
Gói: Pxx
Phụ thuộc đã đạt:
File thực sửa và phần có sẵn đã giữ:
Hành vi trước → sau:
Test regression mới và đường production được gọi:
Lệnh / kết quả / số failures / lint warnings:
Kiểm tra thiết bị đã chạy hoặc chưa chạy:
Rủi ro còn lại / blocker:
Điểm dừng: chưa thực hiện Pxx tiếp theo, chưa phát hành.
```

## 5. Trạng thái khi tạo tài liệu

- Đã đối chiếu đường preference, dispatcher OCR, enum/registry, UI/resources, startup, model path, dependency và test hiện tại.
- Đã sử dụng số đo ZIP ở lượt nghiên cứu trước trong cùng cuộc trao đổi, ghi rõ là tham khảo.
- Chưa sửa code, chưa gỡ model/runtime, chưa tạo bản candidate, chưa chạy unit/build/lint mới hoặc thử nâng cấp trên thiết bị trong lượt lập kế hoạch.
- Các báo cáo OCR re-audit có sẵn không được coi là xác nhận toàn bộ ứng dụng hiện tại không lỗi; P00 phải ghi baseline mới để tách lỗi có sẵn khỏi regression do thay đổi này.
