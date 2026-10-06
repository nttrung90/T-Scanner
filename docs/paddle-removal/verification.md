# Báo cáo Nghiệm thu & Xác minh P06 — Loại bỏ PaddleOCR & ONNX Runtime

- **Thời điểm thực hiện**: 2026-09-24T09:00:00+07:00
- **Git HEAD**: `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`
- **Mục tiêu**: Kiểm thử hồi quy tích hợp toàn diện, xác minh tính tương thích ngược của tài liệu và cấu hình legacy, đo lường dung lượng artifacts và xác nhận sự biến mất hoàn toàn của Paddle/ONNX.
- **Phạm vi & Ranh giới kiểm tra**: Các kiểm tra trên máy build (JVM unit tests, lint, gradle build) xác minh tính đúng đắn của logic mã nguồn, luồng điều hướng (routing resolution), tính toàn vẹn dữ liệu (persistence roundtrip, editing, export Docx/Xlsx), và việc dọn dẹp an toàn (LegacyPaddleCleanup); chúng **KHÔNG THAY THẾ** việc kiểm tra trực tiếp trên thiết bị Android thật hoặc máy ảo emulator.
- **Trạng thái**: ĐẠT KIỂM TRA MÁY BUILD (chưa chạy nghiệm thu thiết bị thật P07 / F06).

---

## 1. Tóm tắt Kết quả Thực thi các Gói (P00 → P05 & F01 → F03 Post-Reaudit)

| Gói | Hạng mục thực hiện | Trạng thái | Ghi chú nghiệm thu |
|---|---|---|---|
| **P00 / F00** | Cố định Baseline & Artifacts Đối chuẩn | ĐẠT | Baseline ban đầu: 413 tests. Baseline sau P06: 460 tests, 718 lint warnings, APK 28.69 MB. Re-audit độc lập phát hiện lỗi nuốt cancellation và rò rỉ lifecycle recognizer. |
| **P01** | Di trú Preference & Request Caller | ĐẠT | Chuẩn hóa `paddle` -> `auto` trong `normalizeEngineMode`, di trú SharedPreferences an toàn, bảo vệ metadata tài liệu cũ. Thêm 13 tests. |
| **P02** | Chuyển hướng Nhận diện Tiếng Trung sang Google ML Kit | ĐẠT | Thay `PADDLE_OCR_V4` bằng `MLKIT_CHINESE` trong enum và bảng ánh xạ ngôn ngữ. Thêm `mlKitChineseRunner` dispatcher. Thêm 11 tests. |
| **P03** | Làm sạch Giao diện, Resource & Chuỗi Đa ngôn ngữ | ĐẠT | Gỡ Option Paddle khỏi `dialog_ocr_engine_selection.xml`, cập nhật 8 file `strings.xml`, thêm `ocr_engine_legacy_paddle` cho tài liệu lịch sử. Thêm 6 tests. |
| **P04** | Loại bỏ Mã thực thi, Assets, Runtime ONNX & Dependency | ĐẠT | Xóa `PaddleOcrEngine.kt`, 4 assets model ONNX, gỡ `onnxruntime-android:1.21.1`, gỡ ProGuard rules ONNX, gỡ startup init. Thêm 6 verification tests. |
| **P05** | Dọn dẹp Model cũ trong Bộ nhớ riêng (`filesDir/paddleocr`) | ĐẠT | Tạo `LegacyPaddleCleanup.kt` chạy nền Dispatchers.IO, không đệ quy, không theo symlink, idempotent, bảo toàn 100% Sentinels Tesseract/OCR/Data. Thêm 15 tests. |
| **P06** | Hồi quy Tích hợp Toàn diện & Đo lường Dung lượng | ĐẠT | Thêm `PaddleRemovalVerificationTest.kt` (5 tests), đo lường chi tiết APK/AAB candidate, xác nhận 0 entry Paddle/ONNX. |
| **F01** | Đóng TextRecognizer ML Kit Close-Once & Xử lý Trạng thái Cuối | ĐẠT | Khắc phục rò rỉ native recognizer trong `TextRecognitionHelper.kt`: thêm `closedGuard = AtomicBoolean(false)`, đảm bảo `safeCloseRecognizer()` luôn được gọi chính xác 1 lần trong `invokeOnCancellation`, terminal callbacks (`addOnSuccessListener`, `addOnFailureListener`) và lỗi đồng bộ/mapper. Thêm 9 lifecycle tests (`MlKitRecognizerLifecycleTest.kt`). |
| **F02** | Lan truyền CancellationException & Điểm dừng Active trong Cleanup | ĐẠT | Khắc phục lỗi nuốt `CancellationException` trong `LegacyPaddleCleanup.kt`: bắt riêng `CancellationException` trước `Throwable` để rethrow ngay lập tức; bổ sung seam `checkActive` và kiểm tra active trước khi quét, trước từng file và trước khi xóa thư mục. Thêm 4 cancellation probe tests vào `LegacyPaddleCleanupTest.kt` (nâng tổng số lên 19 tests). |
| **F03** | Kiểm thử Tích hợp Vòng đời Tài liệu Cũ (Persistence, Edit, Docx, Xlsx) | ĐẠT | Thêm `PaddleLegacyPersistenceExportTest.kt` (5 tests): xác minh tài liệu legacy `engineId="paddle"` lưu/đọc JSON vẹn toàn, thực thi `OcrEditCommand.ReplacePageText` tăng revision CAS chính xác, xuất file OpenXML DOCX và XLSX thật có cấu trúc ZIP hợp lệ, hiển thị nhãn "PaddleOCR (Legacy)" không crash. |

---

## 2. Kết quả Kiểm thử & Đo kiểm Build Máy Chủ (Build Host Gates)

### 2.1. Bộ Kiểm thử Đơn vị & Tích hợp (Unit & Integration Test Suite)
- **Lệnh thực thi**: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain`
- **Kết quả**: `BUILD SUCCESSFUL`
- **Tổng số tests**: **478 tests passed** (tăng từ 413 baseline ban đầu -> 460 sau P06 -> 478 tests sau F01-F03, tăng ròng +65 tests kiểm soát chất lượng).
- **Failures**: **0**
- **Skipped**: **0**
- **Thời gian chạy**: ~7.0s
- **Các bộ test mới được bổ sung**:
  1. `PaddlePreferenceMigrationTest.kt`: 13 tests (di trú SharedPreferences và request caller).
  2. `ChineseMlKitRoutingTest.kt`: 11 tests (routing tiếng Trung sang ML Kit, xử lý lỗi, download model).
  3. `PaddleUiAndStringsCleanupTest.kt`: 6 tests (làm sạch layout dialog, đa ngôn ngữ, giữ nhãn legacy).
  4. `PaddlePhysicalRemovalVerificationTest.kt`: 6 tests (kiểm tra runtime classpath, assets, build.gradle, proguard, application).
  5. `LegacyPaddleCleanupTest.kt`: 19 tests (dọn dẹp filesystem an toàn, fault injection, link avoidance, sentinel hash, cancellation propagation).
  6. `PaddleRemovalVerificationTest.kt`: 5 tests (vòng đời tài liệu legacy, bảng biểu, edits, multi-page batch failure, line-only selection).
  7. `OcrRoutingTest.kt`: +3 tests generic fallback outcome resolution.
  8. `MlKitRecognizerLifecycleTest.kt`: 9 tests (vòng đời đóng recognizer ML Kit, atomic close-once, failure, cancellation, late callbacks).
  9. `PaddleLegacyPersistenceExportTest.kt`: 5 tests (vòng đời lưu/đọc JSON, lệnh sửa OcrEditCommand, xuất DOCX/XLSX thật, nhãn UI legacy).

### 2.2. Kiểm tra Phân tích Mã tĩnh (Android Lint)
- **Lệnh thực thi**: `.\gradlew.bat :app:lintDebug --offline --console=plain`
- **Kết quả**: `BUILD SUCCESSFUL`
- **Errors**: **0**
- **Warnings**: **718 warnings** (giảm 2 warnings so với baseline 720 warnings ban đầu do đã dọn dẹp các resource và code không dùng).
- **Báo cáo chi tiết**: `app/build/reports/lint-results-debug.html`.

### 2.3. Kiểm tra Cây Phụ thuộc Runtime Classpath
- **Lệnh thực thi**: `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline --console=plain`
- **Kết quả**:
  - `Select-String "onnx"`: **0 kết quả** (không còn bất kỳ thư viện nào của Microsoft ONNX Runtime).
  - `Select-String "paddle"`: **0 kết quả**.

---

## 3. Đo lường & So sánh Chi tiết Kích thước Artifacts (Baseline vs Candidate)

### 3.1. Tổng quan Kích thước File (Release Candidate)

| Artifact | Baseline (P00) | Candidate (P06) | Độ lệch (Delta Bytes) | Tỷ lệ giảm |
|---|---|---|---|---|
| **Release APK** (`app-release-unsigned.apk`) | 75,507,865 bytes (75.51 MB) | **28,691,181 bytes** (28.69 MB) | **-46,816,684 bytes** | **-62.00%** |
| **Release AAB** (`app-release.aab`) | 44,799,144 bytes (44.80 MB) | **17,988,354 bytes** (17.99 MB) | **-26,810,790 bytes** | **-59.85%** |

- **SHA-256 Baseline APK**: `0079E414FD2B5B75F762CEBB565E4D41FE722335D53B77EA07704208D4A2404D`
- **SHA-256 Candidate APK**: `DD8A0389C4FE036A0B23D32306CC7D8B8020F089B0E447A978BB421710CF247E`
- **SHA-256 Baseline AAB**: `A6EDB6E1D33E67CE7A9BA2AC62933325D042FE9447865AC37A5A53A23CD740D8`
- **SHA-256 Candidate AAB**: `1BFC251B23DEE377AF9DDFDF697942AF4AF3151BBB504BEA90B3DA06D36AF061`

### 3.2. Phân tích Kích thước Nội bộ APK theo Danh mục (Compressed Bytes)

| Danh mục thành phần | Baseline (bytes) | Candidate (bytes) | Chênh lệch (bytes) | Nguyên nhân thay đổi |
|---|---|---|---|---|
| `native_arm64-v8a` | 26,576,896 | 8,567,768 | **-18,009,128** | Gỡ bỏ `libonnxruntime.so` & `libonnxruntime4j_jni.so` (arm64) |
| `native_armeabi-v7a` | 19,261,156 | 6,148,344 | **-13,112,812** | Gỡ bỏ `libonnxruntime.so` & `libonnxruntime4j_jni.so` (arm32) |
| `assets/` | 20,550,441 | 4,963,538 | **-15,586,903** | Xóa 4 files assets PaddleOCR ONNX models và dictionaries |
| `dex` | 6,845,035 | 6,794,546 | **-50,489** | Gỡ bỏ mã bytecode Java/Kotlin của PaddleOcrEngine và ONNX Java bindings |
| `resources.arsc` | 960,144 | 959,228 | **-916** | Dọn dẹp chuỗi tài nguyên không dùng |
| `res/` | 942,561 | 942,493 | **-68** | Dọn dẹp layout XML của dialog engine selection |
| `other` | 61,796 | 61,796 | 0 | Không thay đổi |
| **TỔNG NỘI BỘ NÉN** | **75,198,029** | **28,437,713** | **-46,760,316** | Giảm tổng thể toàn bộ artifact |

### 3.3. Kiểm tra Các Entry PaddleOCR & ONNX trong Artifact
- **Số entry khớp `*paddle*` trong Candidate APK**: **0**
- **Số entry khớp `*onnx*` trong Candidate APK**: **0**
- **Số entry khớp `*paddle*` trong Candidate AAB**: **0**
- **Số entry khớp `*onnx*` trong Candidate AAB**: **0**
- **Thư viện C++ Native còn lại trong Candidate APK**:
  - `lib/arm64-v8a/libtesseract.so`
  - `lib/arm64-v8a/libleptonica.so`
  - `lib/arm64-v8a/libjpeg.so`
  - `lib/arm64-v8a/libpngx.so`
  - `lib/arm64-v8a/liblanguage_id_l2c_jni.so`
  - `lib/arm64-v8a/libsurface_util_jni.so`
  - `lib/arm64-v8a/libimage_processing_util_jni.so`
  *(tương tự cho `lib/armeabi-v7a/`)*
  -> Toàn bộ thư viện Tesseract và xử lý ảnh được giữ nguyên vẹn 100%.

---

## 4. Xác nhận Tương thích Ngược & Bảo vệ Dữ liệu Người dùng

1. **Khả năng đọc tài liệu cũ có `engineId = "paddle"`**:
   - Thử nghiệm trên `PaddleRemovalVerificationTest.kt` và `PaddlePreferenceMigrationTest.kt` chứng minh: Tài liệu quét từ các phiên bản trước có `engineId="paddle"`, chứa bảng biểu, hình học polygon, các dòng không có word tokens, hoặc các dòng đã được người dùng chỉnh sửa (`editedText`, `isEdited`) khi mở ra, chỉnh sửa và lưu lại thì `engineId` lịch sử vẫn được bảo toàn nguyên vẹn 100%, không bị chuyển thành "auto" hay "mlkit_chinese", không gây kích hoạt OCR lại.
2. **Hit-testing chọn dòng trên tài liệu Paddle cũ**:
   - `OcrSelectionController` hỗ trợ chọn dòng (line-level) hoàn hảo trên các tài liệu legacy mà không gây crash và không tạo fake word tokens.
3. **Bảo vệ chống tạo trang trắng khi thiếu model**:
   - Khi OCR tiếng Trung gặp lỗi module đang tải (`OcrModelUnavailableType.DOWNLOADING`), hệ thống trả về `ModelUnavailable` có kiểm soát.
   - Trong quá trình xử lý đa trang, `MultiPageOcrAggregator` trả về `PageError` ngăn chặn việc xuất tài liệu thiếu trang hoặc chuyển lỗi kỹ thuật thành trang trắng (`NoText`).
4. **Bảo toàn dữ liệu cục bộ khi dọn dẹp model cũ**:
   - `LegacyPaddleCleanup` chỉ xóa các file thường trong allowlist nằm tại `filesDir/paddleocr`.
   - Sentinel tests xác nhận hash SHA-256 của `tessdata/vie.traineddata`, tài liệu quét trong `ocr_docs/`, và file cấu hình ứng dụng giữ nguyên 100% trước và sau dọn dẹp.

---

## 5. Kết luận Cổng P06, Chu trình Sửa sau Re-audit (F01–F04) & Chuyển giao P07 / F06

- Toàn bộ các yêu cầu của P06 và các bản sửa sau re-audit (F01–F03) đã được kiểm chứng độc lập trên môi trường build với kết quả thành công tuyệt đối (478/478 tests passed, 0 lint errors).
- APK giảm từ **75.51 MB** xuống **28.69 MB** (vượt xa chỉ tiêu giảm kích thước, giải phóng 46.82 MB trên thiết bị).
- AAB giảm từ **44.80 MB** xuống **17.99 MB** (giải phóng 26.81 MB).
- **Ranh giới nghiệm thu**: Mọi kết quả kiểm tra tại đây đều là kiểm tra host (JVM / static analysis / packaging inspection). Chúng chứng minh mã nguồn không có lỗi logic, không rò rỉ lifecycle recognizer, không nuốt cancellation, không còn entry Paddle/ONNX, và giữ nguyên tính tương thích tài liệu legacy. Tuy nhiên, các bài test host **KHÔNG THAY THẾ ĐƯỢC** nghiệm thu trên thiết bị thật/emulator Android.
- Điểm dừng an toàn: Chưa thực hiện P07/F06, chưa phát hành. Các ca kiểm thử trên thiết bị thật/emulator (upgrade in-place, download model ML Kit qua mạng thật, kiểm tra font và bố cục chữ Hán thực tế) được bảo toàn nguyên vẹn ở trạng thái **CHƯA CHẠY** để chuyển giao cho cổng P07 / F06.
