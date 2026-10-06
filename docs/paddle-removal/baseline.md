# Báo cáo Baseline P00 — Kế hoạch loại bỏ PaddleOCR & ONNX Runtime

- **Thời điểm ghi nhận**: 2026-09-23T22:10:00+07:00
- **Git HEAD**: `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`
- **Mục tiêu**: Cố định baseline, phạm vi rà soát và lưu trữ artifact đối chuẩn trước khi sửa đổi mã nguồn.
- **Trạng thái production**: KHÔNG SỬA ĐỔI trong gói P00.

---

## 1. Môi trường Build & Trạng thái Git

### 1.1. Cấu hình môi trường
- **Hệ điều hành**: Windows 10 Pro 64-bit (10.0 amd64)
- **Java Runtime**: Oracle JDK 22 (`build 22+36-2370`)
- **Gradle**: 9.7.1 (Build time: 2026-08-19, Kotlin 2.4.0, Groovy 4.0.32, Ant 1.10.17)
- **Android Gradle Plugin / Compile SDK**: `compileSdk 36`, `minSdk 26`, `targetSdk 36`
- **Application ID**: `com.tscanner.app`
- **Version**: `versionCode 15`, `versionName "0.8.0"`
- **ABI Filters**: `arm64-v8a`, `armeabi-v7a`
- **Packaging / Nén**: `aaptOptions { noCompress 'traineddata', 'onnx' }`
- **Build Types**: Release `minifyEnabled false`, Debug `minifyEnabled false`

### 1.2. Trạng thái Working Tree
- Working tree có sẵn **108 file đã sửa đổi (modified)** và **70+ file chưa theo dõi (untracked)** từ các hạng mục tính năng trước đó (Scan workflow, OCR Reader/Editor, Multilingual, Cloud Sync).
- Toàn bộ working tree được bảo toàn 100%, không thực hiện `git reset`, `git clean` hay `git checkout`.

---

## 2. Rà soát & Phân loại Tham chiếu PaddleOCR / ONNX

Rà soát toàn diện các chuỗi: `paddle`, `onnx`, `PADDLE_OCR_V4`, `ENGINE_MODE_PADDLE`.

| Nhóm phân loại | File / Vị trí | Chi tiết & Trách nhiệm xử lý |
|---|---|---|
| **Execution (Mã thực thi)** | `app/src/main/java/com/tscanner/app/paddleocr/PaddleOcrEngine.kt` | Toàn bộ object `PaddleOcrEngine` (524 dòng), sử dụng `ai.onnxruntime.*`, DBNet detection, SVTR recognition. Sẽ bị xóa hoàn toàn ở **P04**. |
| | `app/src/main/java/com/tscanner/app/TScannerApplication.kt:60-68` | Coroutine nền khởi tạo `PaddleOcrEngine.initialize()`. Sẽ gỡ bỏ ở **P04** trước khi nối cleanup **P05**. |
| | `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt` | Các hàm `runPaddleOcrStructured` (dòng 707-720), routing `ENGINE_MODE_PADDLE` (dòng 565-572), routing Trung Auto (dòng 616-625). Sẽ chuyển sang ML Kit Chinese ở **P02**. |
| | `app/src/main/java/com/tscanner/app/utils/OcrModels.kt` | Mapping ngôn ngữ `"zh", "zh-Hans", "zh-Hant" -> OcrType.PADDLE_OCR_V4` (dòng 357), `isEngineCompatible` (dòng 386, 393), `getCompatibleEnginesForLanguage` (dòng 427). Sẽ đổi sang `MLKIT_CHINESE` ở **P02**. |
| | `app/src/main/java/com/tscanner/app/utils/AppLanguageManager.kt:28` | Enum `OcrType.PADDLE_OCR_V4`. Sẽ đổi thành `MLKIT_CHINESE` ở **P02**. |
| **Preference & Migration** | `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt:45, 160-167, 471-484` | `ENGINE_MODE_PADDLE = "paddle"`. Getter `getPreferredOcrEngine` hiện trả nguyên giá trị `paddle` từ SharedPreferences `tscanner_ocr_prefs`. Pipeline structured OCR kiểm tra compatibility trước chuẩn hóa. Cần hàm migration legacy `paddle` -> `auto` ở **P01**. |
| **UI & Resources** | `app/src/main/java/com/tscanner/app/ui/dialogs/OcrEngineSelectionDialog.kt` | Tham chiếu ViewBinding và logic chọn `layout_engine_paddle`, `iv_check_paddle`. Sẽ gỡ ở **P03**. |
| | `app/src/main/java/com/tscanner/app/ui/dialogs/OcrLanguageAdapter.kt:68` | Nhãn `Paddle / ML Kit` cho tiếng Trung. Sẽ đổi thành `ML Kit` ở **P03**. |
| | `app/src/main/res/layout/dialog_ocr_engine_selection.xml:158-202` | Khối UI Option 3 của PaddleOCR. Sẽ xóa ở **P03**. |
| | `app/src/main/res/values*/strings.xml` (9 locales: default, vi, es, fr, de, pt, in, ja, ...) | Các chuỗi `about_engine`, `ocr_engine_auto_desc`, `ocr_engine_paddle`, `ocr_engine_paddle_desc`. Sẽ cập nhật/dọn dẹp ở **P03**. |
| **Build & Assets** | `app/build.gradle:40, 91` | `noCompress 'onnx'` và dependency `com.microsoft.onnxruntime:onnxruntime-android:1.21.1`. Sẽ gỡ ở **P04**. |
| | `app/proguard-rules.pro:14-16` | Quy tắc `-keep class ai.onnxruntime.** { *; }` và `-dontwarn ai.onnxruntime.**`. Sẽ gỡ ở **P04**. |
| | `app/src/main/assets/paddleocr/` | 4 assets: `ch_PP-OCRv4_det.onnx`, `ch_PP-OCRv4_rec.onnx`, `ppocr_keys_v1.txt`, `vi_dict.txt` (tổng 15,596,385 bytes raw). Sẽ xóa ở **P04**. |
| **Metadata Lịch sử (BẢO LƯU)** | `app/src/main/java/com/tscanner/app/ocr/model/OcrDocument.kt:429, 455, 493` | Trường `OcrPage.engineId` nhận giá trị lịch sử `"paddle"`. Hợp đồng JSON phải giữ nguyên, không đổi thành `"auto"` hay `"mlkit"`. |
| | `app/src/main/java/com/tscanner/app/ui/ocr/reader/OcrSelectionController.kt:92` | Fallback chọn theo dòng (line-level) cho các tài liệu legacy hoặc engine chỉ có line. Phải giữ nguyên. |
| **Tests** | `app/src/test/java/com/tscanner/app/PaddleOcrEngineTest.kt` | Unit test cho engine Paddle (sẽ nghỉ hưu ở **P04**). |
| | `app/src/test/java/com/tscanner/app/OcrRoutingTest.kt` | Test routing OcrType / engine mode (sẽ cập nhật ở **P02**). |
| | `app/src/test/java/com/tscanner/app/OcrDocumentModelTest.kt:188` | Test serialization với `engineId = "paddle"`. BẢO LƯU làm test tương thích ngược. |
| | `app/src/test/java/com/tscanner/app/OcrMultiPageIntegrationAndHandoffTest.kt:112` | Handoff test với `engineId = "paddle"`. BẢO LƯU. |
| | `app/src/test/java/com/tscanner/app/OcrSelectionControllerTest.kt:76-98` | Test line-level selection với `line_paddle_1`. BẢO LƯU. |
| | `app/src/test/java/com/tscanner/app/OcrUserFlowIntegrationTest.kt:126` | Test incompatible engine error. BẢO LƯU. |

---

## 3. Kết quả Kiểm chuẩn Baseline Thực tế

Toàn bộ các lệnh sau được thực thi trực tiếp trên máy phát triển và ghi nhận kết quả:

### 3.1. Unit Test Suite
- **Lệnh thực thi**: `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline --console=plain`
- **Kết quả**: `BUILD SUCCESSFUL in 2m 4s`
- **Tổng số test**: **413**
- **Failures**: **0**
- **Skipped**: **0**
- **Thời gian chạy**: 7.230s

### 3.2. Debug Build & Lint Check
- **Lệnh thực thi**: `.\gradlew.bat :app:assembleDebug :app:lintDebug --offline --console=plain`
- **Kết quả Build**: `BUILD SUCCESSFUL in 19s`
- **Kết quả Lint (`app/build/reports/lint-results-debug.txt`)**:
  - **Errors**: **0**
  - **Warnings**: **720** (chủ yếu là `OldTargetApi`, `LockedOrientationActivity`, `DiscouragedApi`, `MissingTranslation` và hardcoded strings có sẵn trong dự án).

### 3.3. Runtime Classpath Dependencies
- **Lệnh thực thi**: `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline --console=plain`
- **Hiện diện ONNX**: `+--- com.microsoft.onnxruntime:onnxruntime-android:1.21.1` (dòng 640 của dependency report) xác nhận runtime ONNX đang được kéo vào APK/AAB release.

---

## 4. Kích thước & Chi tiết Artifacts Baseline

Các artifact baseline được build fresh bằng lệnh:
`.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain`

Và được sao chép sang thư mục cách ly độc lập:
`app/build/baseline_paddle_removal/` (không ghi đè thư mục baseline cũ tại `app/build/baseline/`).

### 4.1. Baseline Release APK (`app-release-unsigned.apk`)
- **Vị trí lưu trữ**: `app/build/baseline_paddle_removal/baseline-p00-release-unsigned.apk`
- **SHA-256**: `0079E414FD2B5B75F762CEBB565E4D41FE722335D53B77EA07704208D4A2404D`
- **Kích thước file**: **75,507,865 byte** (75.5079 MB thập phân / 72.0099 MiB)

#### Chi tiết các entry PaddleOCR & ONNX trong APK (8 entries):
| Tên entry trong APK | Kích thước nén (bytes) | Kích thước giải nén (bytes) | Phương thức lưu trữ |
|---|---|---|---|
| `lib/arm64-v8a/libonnxruntime.so` | 17,908,496 | 17,908,496 | Stored (16 KB page-aligned) |
| `lib/arm64-v8a/libonnxruntime4j_jni.so` | 100,632 | 100,632 | Stored (16 KB page-aligned) |
| `lib/armeabi-v7a/libonnxruntime.so` | 13,039,136 | 13,039,136 | Stored (16 KB page-aligned) |
| `lib/armeabi-v7a/libonnxruntime4j_jni.so` | 73,676 | 73,676 | Stored (16 KB page-aligned) |
| `assets/paddleocr/ch_PP-OCRv4_det.onnx` | 4,744,262 | 4,744,262 | Stored (`noCompress 'onnx'`) |
| `assets/paddleocr/ch_PP-OCRv4_rec.onnx` | 10,825,534 | 10,825,534 | Stored (`noCompress 'onnx'`) |
| `assets/paddleocr/ppocr_keys_v1.txt` | 16,891 | 26,250 | Deflated |
| `assets/paddleocr/vi_dict.txt` | 217 | 339 | Deflated |
| **TỔNG PADDLE + ONNX TRONG APK** | **46,708,844** (~46.71 MB) | **46,718,325** (~46.72 MB) | Chiếm 61.86% tổng dung lượng APK |

### 4.2. Baseline Release AAB (`app-release.aab`)
- **Vị trí lưu trữ**: `app/build/baseline_paddle_removal/baseline-p00-release.aab`
- **SHA-256**: `A6EDB6E1D33E67CE7A9BA2AC62933325D042FE9447865AC37A5A53A23CD740D8`
- **Kích thước file**: **44,799,144 byte** (44.7991 MB thập phân / 42.7238 MiB)

#### Chi tiết các entry PaddleOCR & ONNX trong AAB (8 entries):
| Tên entry trong AAB | Kích thước nén (bytes) | Kích thước giải nén (bytes) |
|---|---|---|
| `base/lib/arm64-v8a/libonnxruntime.so` | 6,436,216 | 17,908,496 |
| `base/lib/arm64-v8a/libonnxruntime4j_jni.so` | 29,118 | 100,632 |
| `base/lib/armeabi-v7a/libonnxruntime.so` | 5,891,202 | 13,039,136 |
| `base/lib/armeabi-v7a/libonnxruntime4j_jni.so` | 26,311 | 73,676 |
| `base/assets/paddleocr/ch_PP-OCRv4_det.onnx` | 4,387,669 | 4,744,262 |
| `base/assets/paddleocr/ch_PP-OCRv4_rec.onnx` | 9,970,455 | 10,825,534 |
| `base/assets/paddleocr/ppocr_keys_v1.txt` | 16,891 | 26,250 |
| `base/assets/paddleocr/vi_dict.txt` | 217 | 339 |
| **TỔNG PADDLE + ONNX TRONG AAB** | **26,758,079** (~26.76 MB) | **46,718,325** (~46.72 MB) | Chiếm 59.73% tổng dung lượng AAB |

---

## 5. Môi trường Thiết bị & Trạng thái Nâng cấp (P07)

- **Kiểm tra ADB**:
  - Lệnh: `& "C:\Users\nguye\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices`
  - Kết quả: `List of devices attached` (trống, không có thiết bị thật hoặc máy ảo đang kết nối).
- **Trạng thái P07**: **CHƯA CHẠY** do chưa có thiết bị kết nối. Các ca kiểm thử nâng cấp (upgrade in-place, preference legacy, cleanup model filesDir, Play Services Chinese download) sẽ được bảo lưu tại cổng P07.

---

## 6. Kết luận Nghiệm thu P00

1. **Baseline có nguồn gốc rõ ràng**:
   - Commit HEAD: `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`.
   - 413 unit tests pass 100%, 0 failures.
   - Lint ghi nhận 0 errors, 720 warnings nền.
   - APK baseline 75,507,865 bytes (Paddle/ONNX chiếm 46,708,844 bytes).
   - AAB baseline 44,799,144 bytes (Paddle/ONNX chiếm 26,758,079 bytes).
2. **Bảo tồn Working Tree**:
   - Toàn bộ thay đổi của người dùng được giữ nguyên.
   - Không có file mã nguồn nào bị thay đổi trong gói P00.
   - Baseline artifacts được lưu tại thư mục cách ly `app/build/baseline_paddle_removal/`, không chạm vào `app/build/baseline/` cũ.
3. **Sẵn sàng cho P01**: Điểm dừng an toàn trước P01.
