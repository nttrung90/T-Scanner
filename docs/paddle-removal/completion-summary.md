# Báo cáo Tổng kết Hoàn thành Dự án Loại bỏ PaddleOCR & ONNX Runtime (Gói F06)

- **Thời điểm hoàn thành**: 2026-09-24T10:30:00+07:00
- **Dự án**: T-Scanner Android App
- **Mục tiêu cốt lõi**: Loại bỏ hoàn toàn PaddleOCR và Microsoft ONNX Runtime để giảm mạnh dung lượng ứng dụng (APK/AAB), chuyển hướng nhận diện tiếng Trung sang Google ML Kit Text Recognition v2 (unbundled model tải qua Google Play Services), dọn dẹp an toàn dữ liệu model cũ trong bộ nhớ ứng dụng, bảo toàn tuyệt đối 100% dữ liệu lịch sử và sửa chữa dứt điểm các lỗi phát hiện qua kiểm tra độc lập (re-audit).
- **Trạng thái cổng F06**: **HOÀN THÀNH MÁY BUILD 100% — NGHIỆM THU THIẾT BỊ CHƯA CHẠY (DO THIẾU THIẾT BỊ VẬT LÝ)**.
- **Quy tắc phát hành**: **KHÔNG PHÁT HÀNH TỰ ĐỘNG**. Bản dựng đã sẵn sàng để đội ngũ QA / Release nạp lên thiết bị vật lý nghiệm thu 13 ca kiểm tra trước khi publish lên Google Play Store.

---

## 1. Tóm tắt Hai Chu trình Thực thi (P00–P06 & F00–F06)

### 1.1. Chu trình Loại bỏ Ban đầu (P00 → P06)
- **P00 (Baseline)**: Lưu trữ APK gốc (75.51 MB), AAB gốc (44.80 MB), 413 tests, 720 lint warnings.
- **P01 (Preference Migration)**: Di trú cài đặt SharedPreferences từ `paddle` sang `auto`, bảo toàn trường `engineId="paddle"` trong metadata tài liệu lịch sử.
- **P02 (ML Kit Chinese Routing)**: Thay thế `PADDLE_OCR_V4` bằng `MLKIT_CHINESE`, xây dựng dispatcher tự động tải model tiếng Trung qua Google Play Services.
- **P03 (UI & Strings Cleanup)**: Gỡ bỏ lựa chọn PaddleOCR trong dialog, làm sạch chuỗi tài nguyên 8 ngôn ngữ, bổ sung chuỗi định danh `"PaddleOCR (Legacy)"`.
- **P04 (Physical Removal)**: Xóa `PaddleOcrEngine.kt`, xóa 4 assets ONNX (15.58 MB), gỡ thư viện `onnxruntime-android:1.21.1`, gỡ ProGuard rules ONNX và mã khởi tạo.
- **P05 (Safe Cleanup)**: Tạo `LegacyPaddleCleanup.kt` tự động dọn dẹp các model cũ trong `filesDir/paddleocr` trên luồng nền IO, bảo vệ sentinel Tesseract và tài liệu người dùng.
- **P06 (Host Verification)**: Chạy hồi quy 460 tests, đo lường APK giảm xuống 28.69 MB (-62.00%).

### 1.2. Chu trình Sửa lỗi An toàn Sau Re-audit (F00 → F06)
- **F00 (Fix Baseline & Reproduction Probes)**: Ghi nhận 2 probe fail do nuốt `CancellationException` trong cleanup; thiết lập baseline sửa lỗi.
- **F01 (ML Kit Lifecycle & Close-Once)**:
  - Khắc phục lỗi R01 (ML Kit TextRecognizer không được đóng sau khi hoàn thành hoặc lỗi).
  - Thêm cơ chế `AtomicBoolean(false)` và `safeCloseRecognizer()` kích hoạt qua `invokeOnCancellation`, terminal callbacks (`addOnSuccessListener`, `addOnFailureListener`) và khối ngoại lệ đồng bộ/mapper.
  - Bổ sung `MlKitRecognizerLifecycleTest.kt` (9 unit tests xanh).
- **F02 (Cancellation Propagation & Cleanup Checkpoints)**:
  - Khắc phục lỗi R02 (`LegacyPaddleCleanup` nuốt `CancellationException` trong khối `catch (e: Throwable)`).
  - Bắt riêng và rethrow ngay `CancellationException`; chèn các điểm kiểm tra hoạt động `checkActive()` trước khi quét, trước từng file và trước khi xóa thư mục.
  - Tích hợp 4 cancellation probe tests vào `LegacyPaddleCleanupTest.kt` (nâng tổng số lên 19 tests).
- **F03 (Integration Test: Legacy Persistence / Edit / Export)**:
  - Khắc phục thiếu sót kiểm thử R03: Tạo `PaddleLegacyPersistenceExportTest.kt` (5 tests).
  - Xác minh tài liệu `engineId="paddle"` lưu/đọc JSON vẹn toàn, chạy lệnh sửa `ReplacePageText` tăng phiên bản CAS chuẩn xác, xuất file OpenXML DOCX và XLSX thật có cấu trúc ZIP hợp lệ, hiển thị nhãn legacy thân thiện.
- **F04 (Documentation & Signed APK Procedures)**:
  - Cập nhật `verification.md` làm rõ ranh giới kiểm tra host vs kiểm tra thiết bị.
  - Cập nhật `device-acceptance.md`: Thay thế hướng dẫn cài APK unsigned bằng 3 cách cài đặt có chữ ký số (Debug APK, apksigner với keystore kiểm thử, bundletool).
- **F05 (Host Re-verification & Artifact Measurements)**:
  - Chạy full build gates: 478 tests passed, 0 failures, 0 skipped, 0 lint errors.
  - Quét xác nhận 0 entry Paddle/ONNX trong APK, AAB và runtime classpath.
  - Tạo `docs/paddle-removal/fix-verification.md`.
- **F06 (Device Check & Project Completion)**:
  - Kiểm tra ADB thiết bị: không có thiết bị kết nối.
  - Ghi nhận trạng thái trung thực: 13 ca kiểm tra thiết bị là **CHƯA CHẠY**.
  - Ban hành báo cáo tổng kết đóng kế hoạch.

---

## 2. Bảng Đối chiếu Chỉ số Kỹ thuật & Thành quả Đạt được

| Tiêu chí | Trước khi gỡ (P00) | Sau khi hoàn thành (F05/F06) | Kết quả cải thiện |
|---|---|---|---|
| **Dung lượng Release APK** | 75,507,865 bytes (75.51 MB) | **28,707,565 bytes (28.71 MB)** | **Giảm 46.80 MB (-61.98%)** |
| **Dung lượng Release AAB** | 44,799,144 bytes (44.80 MB) | **17,989,022 bytes (17.99 MB)** | **Giảm 26.81 MB (-59.85%)** |
| **Dung lượng Debug APK** | 77.2 MB | **31.44 MB** | **Giảm 45.76 MB** |
| **Thư viện Native C++ (.so)** | Tesseract + ONNX Runtime (4 ABI) | **Chỉ còn Tesseract & xử lý ảnh** | Xóa hoàn toàn `libonnxruntime*.so` |
| **Assets Model nén trong app** | 4 files ONNX models (15.58 MB) | **0 file model ONNX** | Giải phóng 100% dung lượng assets |
| **Tổng số Unit Tests** | 413 tests | **478 tests** | **+65 tests chất lượng cao** |
| **Tỷ lệ Pass Unit Tests** | 100% | **100% (478/478)** | 0 failures, 0 skipped |
| **Android Lint Errors** | 0 errors | **0 errors** | 0 lỗi chặn |
| **Dọn dẹp Bộ nhớ Thiết bị** | Chưa có cơ chế dọn | **`LegacyPaddleCleanup` an toàn** | Tự xóa model cũ, bảo vệ dữ liệu scan |
| **Nhận diện Tiếng Trung** | ONNX Runtime nặng nề offline | **Google ML Kit v2 unbundled** | Tự tải qua Play Services khi cần |
| **Tài liệu Lịch sử Paddle** | N/A | **Tương thích ngược 100%** | Giữ metadata, sửa chữ, xuất DOCX/XLSX |

---

## 3. Danh mục Files Thay đổi và Bổ sung trong Toàn bộ Dự án

### 3.1. Các file mã nguồn và cấu hình sản xuất đã chỉnh sửa
- `app/build.gradle`: Gỡ bỏ phụ thuộc ONNX Runtime, cập nhật phụ thuộc ML Kit Chinese.
- `app/proguard-rules.pro`: Gỡ bỏ rules giữ mã của ONNX Runtime.
- `app/src/main/java/com/tscanner/app/TScannerApplication.kt`: Gỡ khởi tạo Paddle, kích hoạt dọn dẹp `LegacyPaddleCleanup`.
- `app/src/main/java/com/tscanner/app/ocr/OcrModels.kt`: Cập nhật enum engine, ánh xạ ngôn ngữ tiếng Trung sang ML Kit.
- `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt`:
  - Thêm routing tiếng Trung sang ML Kit Chinese.
  - Chuẩn hóa cấu hình SharedPreferences `paddle` -> `auto`.
  - Giữ nguyên nhãn `"PaddleOCR (Legacy)"` cho tài liệu cũ.
  - Bổ sung cơ chế đóng TextRecognizer `safeCloseRecognizer()` close-once qua `AtomicBoolean`.
- `app/src/main/java/com/tscanner/app/utils/LegacyPaddleCleanup.kt`:
  - Module dọn dẹp file an toàn trong `filesDir/paddleocr`.
  - Lan truyền `CancellationException` đúng chuẩn và bổ sung `checkActive()` checkpoints.
- `app/src/main/res/layout/dialog_ocr_engine_selection.xml`: Gỡ bỏ RadioButton chọn engine Paddle.
- `app/src/main/res/values*/strings.xml`: Cập nhật mô tả động cơ OCR trên 8 ngôn ngữ, bổ sung chuỗi nhãn legacy.

### 3.2. Các file đã xóa vĩnh viễn khỏi repository
- `app/src/main/java/com/tscanner/app/ocr/PaddleOcrEngine.kt`
- `app/src/main/assets/ch_PP-OCRv4_det_infer.onnx`
- `app/src/main/assets/ch_PP-OCRv4_rec_infer.onnx`
- `app/src/main/assets/ch_ppocr_mobile_v2.0_cls_infer.onnx`
- `app/src/main/assets/ppocr_keys_v1.txt`

### 3.3. Các bộ kiểm thử tự động mới được tạo lập
1. `app/src/test/java/com/tscanner/app/PaddlePreferenceMigrationTest.kt` (13 tests)
2. `app/src/test/java/com/tscanner/app/ChineseMlKitRoutingTest.kt` (11 tests)
3. `app/src/test/java/com/tscanner/app/PaddleUiAndStringsCleanupTest.kt` (6 tests)
4. `app/src/test/java/com/tscanner/app/PaddlePhysicalRemovalVerificationTest.kt` (6 tests)
5. `app/src/test/java/com/tscanner/app/LegacyPaddleCleanupTest.kt` (19 tests)
6. `app/src/test/java/com/tscanner/app/PaddleRemovalVerificationTest.kt` (5 tests)
7. `app/src/test/java/com/tscanner/app/MlKitRecognizerLifecycleTest.kt` (9 tests)
8. `app/src/test/java/com/tscanner/app/PaddleLegacyPersistenceExportTest.kt` (5 tests)

### 3.4. Tài liệu kỹ thuật và báo cáo nghiệm thu
- `docs/paddle-removal/plan.md`: Kế hoạch thực hiện ban đầu.
- `docs/paddle-removal/verification.md`: Báo cáo xác minh tích hợp P06.
- `docs/paddle-removal/device-acceptance.md`: Hướng dẫn và ma trận 13 ca kiểm thử thiết bị Android.
- `docs/paddle-removal/fix-baseline.md`: Báo cáo cố định baseline trước sửa lỗi re-audit.
- `docs/paddle-removal/fix-verification.md`: Báo cáo đo kiểm build host sau sửa lỗi re-audit F05.
- `docs/paddle-removal/completion-summary.md`: Báo cáo tổng kết toàn diện đóng kế hoạch F06.

---

## 4. Ma trận Trạng thái Cuối cùng & Kết quả Kiểm tra ADB

### 4.1. Trạng thái Kết nối ADB Thiết bị
- **Lệnh thực thi**: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices`
- **Kết quả**: `List of devices attached` (0 thiết bị kết nối).
- **Kết luận**: Môi trường không có thiết bị hoặc máy ảo Android.

### 4.2. Bảng Ma trận 13 Ca Nghiệm thu Thiết bị
Theo đúng cam kết không tự động giả định kết quả thiết bị:

| STT | Ca kiểm thử bắt buộc | Trạng thái Nghiệm thu | Lý do / Ghi chú |
|:---:|---|:---:|---|
| 1 | Nâng cấp tại chỗ, prefs Paddle + zh-Hant | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 2 | Root model cũ đầy đủ, dọn dẹp an toàn | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 3 | Root có file lạ hoặc subfolder lạ | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 4 | Cài mới, offline, Chinese model chưa tải | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 5 | Tải unbundled model qua Play Services | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 6 | Môi trường thiếu/lỗi Google Play Services | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 7 | OCR chữ Hán giản thể (zh-Hans) & phồn thể (zh-Hant) | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 8 | Bảng biểu tiếng Trung, văn bản nghiêng/mixed | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 9 | Mở, chọn dòng, sửa & lưu tài liệu Paddle cũ | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 10 | Batch nhiều trang có trang lỗi / thiếu model | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 11 | Hủy coroutine OCR / xoay màn hình | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 12 | Hồi quy các ngôn ngữ khác (Việt, Anh, Nhật, v.v.) | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |
| 13 | Smoke test luồng CameraX, PDF, QR, Drive | **CHƯA CHẠY** | Blocked do không có thiết bị kết nối |

---

## 5. Khuyến nghị Bàn giao Tiếp theo cho Đội ngũ QA / Release

1. **Không phát hành tự động lên Google Play Store**: Giữ nguyên nguyên tắc kiểm soát chất lượng nghiêm ngặt; bản dựng chỉ được đẩy lên production sau khi QA hoàn tất nghiệm thu tối thiểu các ca trọng yếu (Ca 1, 2, 4, 5, 7, 9, 10, 11) trên thiết bị thật.
2. **Sử dụng bản dựng đã ký**:
   - Để kiểm thử nhanh tính năng: Cài đặt `app/build/outputs/apk/debug/app-debug.apk`.
   - Để kiểm thử tối ưu R8/ProGuard Release: Ký `app/build/outputs/apk/release/app-release-unsigned.apk` bằng `apksigner` theo hướng dẫn tại `docs/paddle-removal/device-acceptance.md`.
3. **Môi trường thiết bị khuyến nghị**:
   - Ít nhất 1 thiết bị Android ARM64 chạy Android 10+ có Google Play Services bản mới.
   - Thử nghiệm trên cả mạng Wi-Fi bình thường và chế độ máy bay (Airplane mode) để kiểm tra luồng unbundled model download của Google ML Kit.
