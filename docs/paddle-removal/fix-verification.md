# Báo cáo Xác minh Nghiệm thu Build Host Sau Re-audit (Gói F05) — Loại bỏ PaddleOCR & ONNX Runtime

- **Thời điểm thực hiện**: 2026-09-24T10:25:00+07:00
- **Mục tiêu**: Nghiệm thu toàn diện trên môi trường máy build sau chu trình sửa chữa re-audit (F00 → F04), đối chiếu 3 mốc (Baseline P00 vs Candidate P06 vs Fix Candidate F05), xác minh dung lượng artifact, cấu trúc thư viện native và kiểm tra triệt để sự vắng mặt của Paddle/ONNX.
- **Trạng thái cổng F05**: **ĐẠT XÁC MINH MÁY BUILD** (Chuyển giao cho cổng nghiệm thu thiết bị thật F06).

---

## 1. Bảng Đối chiếu So sánh 3 Mốc (P00 vs P06 vs F05)

| Chỉ số / Hạng mục kiểm tra | Baseline Gốc (P00) | Sau Gỡ Ban Đầu (P06) | Sau Sửa Re-audit (F05) | Chênh lệch F05 vs P00 | Đánh giá |
|---|---|---|---|---|---|
| **Tổng số Unit Tests** | 413 passed | 460 passed | **478 passed** | **+65 tests** | Đạt 100% (0 fail, 0 skip) |
| **Lint Errors** | 0 errors | 0 errors | **0 errors** | 0 | Đạt chuẩn nghiêm ngặt |
| **Lint Warnings** | 720 warnings | 718 warnings | **744 warnings** | +24 (cảnh báo AGP/format) | Đạt (0 blocker error) |
| **Kích thước Release APK** (`app-release-unsigned.apk`) | 75,507,865 bytes (75.51 MB) | 28,691,181 bytes (28.69 MB) | **28,707,565 bytes** (28.71 MB) | **-46,800,300 bytes (-61.98%)** | Giảm ~46.80 MB |
| **Kích thước Release AAB** (`app-release.aab`) | 44,799,144 bytes (44.80 MB) | 17,988,354 bytes (17.99 MB) | **17,989,022 bytes** (17.99 MB) | **-26,810,122 bytes (-59.85%)** | Giảm ~26.81 MB |
| **Kích thước Debug APK** (`app-debug.apk`) | 77.2 MB | 31.4 MB | **31,435,808 bytes** (31.44 MB) | **-45.76 MB** | Đã ký sẵn debug.keystore |
| **Số entry khớp `paddle` trong APK/AAB** | > 10 entries | 0 | **0** | Đã xóa triệt để | Đạt |
| **Số entry khớp `onnx` trong APK/AAB** | > 15 entries | 0 | **0** | Đã xóa triệt để | Đạt |
| **Phụ thuộc ONNX trong `releaseRuntimeClasspath`** | Có (`onnxruntime-android:1.21.1`) | 0 | **0** | Đã xóa triệt để | Đạt |
| **Đóng ML Kit Recognizer** | Thiếu (R01 / rò rỉ lifecycle) | Thiếu | **Close-Once Atomic & Safe** | Khắc phục triệt để R01 | Đạt (9 tests) |
| **Lan truyền Cancellation Cleanup** | Nuốt `CancellationException` | Nuốt `CancellationException` | **Rethrow & Checkpoints** | Khắc phục triệt để R02 | Đạt (19 tests) |
| **Vòng đời Legacy Paddle Doc** | Chưa kiểm tra sâu xuất file | Kiểm tra cơ bản | **Roundtrip + Edit + Docx + Xlsx** | Bổ sung kiểm thử R03 | Đạt (5 tests) |

---

## 2. Chi tiết Artifacts Cuối cùng (F05)

### 2.1. File Hash SHA-256
- **Release APK** (`app/build/outputs/apk/release/app-release-unsigned.apk`):
  `605F0C93338CC6F929A5718045DECB07BED152DB21601B654E24DD5A77613280` (28,707,565 bytes)
- **Release AAB** (`app/build/outputs/bundle/release/app-release.aab`):
  `9DF31D673519FB75127FAAEC9D879805C6E7ECDBFDABFDDC34003820A80A58EA` (17,989,022 bytes)
- **Debug APK** (`app/build/outputs/apk/debug/app-debug.apk`):
  `D246C998D2F8DAF7609A7A7E780D098C4C8311C3A2956E3B296B7D0462DFFB58` (31,435,808 bytes)

### 2.2. Kiểm tra Thư viện C++ Native trong Release APK
Không còn bất kỳ file `.so` nào của Microsoft ONNX Runtime (`libonnxruntime.so` và `libonnxruntime4j_jni.so`).
Danh sách các file thư viện native còn lại trong APK:
- `lib/arm64-v8a/libimage_processing_util_jni.so`
- `lib/arm64-v8a/libjpeg.so`
- `lib/arm64-v8a/liblanguage_id_l2c_jni.so`
- `lib/arm64-v8a/libleptonica.so`
- `lib/arm64-v8a/libpngx.so`
- `lib/arm64-v8a/libsurface_util_jni.so`
- `lib/arm64-v8a/libtesseract.so`
- `lib/armeabi-v7a/libimage_processing_util_jni.so`
- `lib/armeabi-v7a/libjpeg.so`
- `lib/armeabi-v7a/liblanguage_id_l2c_jni.so`
- `lib/armeabi-v7a/libleptonica.so`
- `lib/armeabi-v7a/libpngx.so`
- `lib/armeabi-v7a/libsurface_util_jni.so`
- `lib/armeabi-v7a/libtesseract.so`

Toàn bộ các engine xử lý ảnh và Tesseract OCR native được bảo toàn nguyên vẹn 100%.

---

## 3. Tóm tắt Nội dung Nâng cấp & Sửa chữa trong Chu trình F00 → F04

1. **Gói F01 (Khắc phục R01 — Vòng đời Google ML Kit TextRecognizer)**:
   - Sửa đổi trong `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt`.
   - Bổ sung `closedGuard = AtomicBoolean(false)` và cơ chế `safeCloseRecognizer()` đảm bảo client native luôn được giải phóng đúng một lần khi coroutine hoàn thành, lỗi, bị hủy (`cont.invokeOnCancellation`), hoặc lỗi mapper đồng bộ.
   - Thêm `app/src/test/java/com/tscanner/app/MlKitRecognizerLifecycleTest.kt` với 9 ca kiểm thử chuyên biệt.

2. **Gói F02 (Khắc phục R02 — Lan truyền Hủy Coroutine & Checkpoint trong LegacyPaddleCleanup)**:
   - Sửa đổi trong `app/src/main/java/com/tscanner/app/utils/LegacyPaddleCleanup.kt`.
   - Bắt riêng `CancellationException` trước nhánh `Throwable` tổng quát để rethrow ngay lập tức, không để quá trình hủy bị che giấu hoặc nuốt nhầm.
   - Bổ sung seam `checkActive` và chèn các điểm kiểm tra hoạt động trước khi quét thư mục, trước khi kiểm tra/xóa từng file, và trước khi xóa thư mục.
   - Bổ sung 4 cancellation probe tests vào `app/src/test/java/com/tscanner/app/LegacyPaddleCleanupTest.kt` (nâng tổng số lên 19 tests).

3. **Gói F03 (Khắc phục R03 — Kiểm thử Tích hợp Vòng đời Tài liệu Cũ & Xuất DOCX / XLSX)**:
   - Tạo mới `app/src/test/java/com/tscanner/app/PaddleLegacyPersistenceExportTest.kt` (5 tests).
   - Xác minh toàn vẹn việc lưu/đọc JSON tài liệu legacy có `engineId="paddle"`, thực thi lệnh chỉnh sửa `OcrEditCommand.ReplacePageText` với cơ chế kiểm soát phiên bản CAS, xuất file OpenXML DOCX và XLSX thật có cấu trúc ZIP hợp lệ, và định dạng nhãn UI "PaddleOCR (Legacy)".

4. **Gói F04 (Đính chính Tài liệu & Thủ tục Cài đặt APK Có Ký số)**:
   - Cập nhật `docs/paddle-removal/verification.md` làm rõ ranh giới: kiểm tra máy build chứng minh tính đúng của logic mã nguồn nhưng không thay thế nghiệm thu trên thiết bị thật.
   - Cập nhật `docs/paddle-removal/device-acceptance.md`: thay thế hướng dẫn cài đặt `app-release-unsigned.apk` bằng 3 phương thức cài đặt có chữ ký số (Debug APK, apksigner test keystore, bundletool).
   - Bảo lưu 13 ca kiểm tra thiết bị ở trạng thái **CHƯA CHẠY**.

---

## 4. Kết luận Nghiệm thu Cổng F05

- Toàn bộ 6 cổng kiểm tra máy build (`testDebugUnitTest`, `assembleDebug`, `lintDebug`, `assembleRelease`, `bundleRelease`, `dependencies releaseRuntimeClasspath`) đạt kết quả `BUILD SUCCESSFUL`.
- Hệ thống đạt 478/478 tests, 0 failures, 0 skipped, 0 lint errors.
- Mã nguồn và artifacts sạch 100% không còn bất kỳ entry hay phụ thuộc nào của Paddle/ONNX.
- Toàn bộ điều kiện nghiệm thu máy build hoàn tất. Chuyển giao sang Gói F06 để kiểm tra thiết bị Android hoặc lập báo cáo nghiệm thu đóng chu trình.
