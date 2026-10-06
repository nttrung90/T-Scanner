# Baseline Dung Lượng và Cấu Hình Đo Nghiệm Thu OCR

**Ngày thiết lập baseline:** 22/09/2026  
**Gói:** S00 — Chốt baseline và phép đo  
**Kế hoạch tham chiếu:** `PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md`

---

## 1. Môi trường và Toolchain

- **Hệ điều hành:** Windows 10 (10.0 amd64)
- **Java Runtime (JDK):** Oracle JDK 22 (build 22+36-2370)
- **Gradle:** 9.7.1
- **Android Gradle Plugin (AGP):** 9.3.0
- **Android SDK:**
  - `compileSdk`: 36
  - `minSdk`: 26
  - `targetSdk`: 36
  - `build-tools`: 36.0.0 / 34.0.0
- **Cấu hình ABI:** `arm64-v8a`, `armeabi-v7a` (2 ABI)
- **R8 / Minification:** `minifyEnabled false` (cả `release` và `debug`)
- **Tùy chọn AAPT:** `noCompress 'traineddata', 'onnx'`
- **Bundle Split:** `bundle { language { enableSplit = false } }`

---

## 2. Trạng thái mã nguồn tại thời điểm đo

- **Git SHA HEAD:** `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`
- **Thông điệp HEAD:** `chore: bump version to 0.6.0 (Build 11)`
- **Trạng thái working tree:** Giữ nguyên toàn bộ file staged/unstaged hiện hành của người dùng; baseline được build trực tiếp từ trạng thái working tree thực tế.
- **Vị trí lưu trữ baseline độc lập:**
  - Bản sao artifact và manifest đo được lưu tại: `app/build/baseline/`
  - File manifest: `app/build/baseline/baseline_manifest.json`
  - Không commit file binary vào git repository.

---

## 3. Lệnh Build Baseline

Lệnh chuẩn để build release artifact:

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease --console=plain
```

Lệnh chạy kiểm tra kích thước và delta:

```powershell
pwsh -File "scripts/measure_ocr_size.ps1"
```

Lệnh cập nhật baseline mới (khi có yêu cầu):

```powershell
pwsh -File "scripts/measure_ocr_size.ps1" -RecordBaseline
```

---

## 4. Số đo Baseline thực tế

### 4.1. Thông số Artifacts

| Tệp Artifact | Đường dẫn xuất | Kích thước (Bytes) | Kích thước (MB thập phân) | SHA256 Checksum |
|---|---|---:|---:|---|
| **APK Release (unsigned)** | `app/build/outputs/apk/release/app-release-unsigned.apk` | **75.347.472** | 75,35 MB | `802710A7387288FF3ADB1F31D09550151B716FC4FB957392B72FB5030FE289B8` |
| **AAB Release** | `app/build/outputs/bundle/release/app-release.aab` | **44.641.170** | 44,64 MB | `5B5D2A6501A14FBD0EBFA4644AF98CE55BAA8933B0F39EFECFB30C9B1F9F115E` |

### 4.2. Phân rã cấu trúc bên trong APK Baseline (Compressed bytes)

| Thành phần APK | Kích thước nén (bytes) | Tỷ lệ (%) | Ghi chú |
|---|---:|---:|---|
| `native_arm64-v8a` | 26.576.896 | 35,27% | ONNX Runtime + Tesseract + OpenCV native libs |
| `assets` | 20.550.442 | 27,27% | Uncompressed models/tessdata (`traineddata`, `onnx`) |
| `native_armeabi-v7a` | 19.261.156 | 25,56% | ONNX Runtime + Tesseract + OpenCV 32-bit native libs |
| `dex` | 6.697.916 | 8,89% | Mã bytecode Dalvik/ART (classes.dex, classes2.dex) |
| `resources.arsc` | 952.624 | 1,26% | Bảng tài nguyên ứng dụng |
| `res` | 937.814 | 1,24% | Drawables, layouts, XML |
| `other` | 61.796 | 0,08% | META-INF, AndroidManifest.xml compiled |
| **Tổng APK** | **75.347.472** | **100,00%** | |

---

## 5. Ngưỡng và Quy tắc Nghiệm thu Dung lượng

1. **Trần cứng tuyệt đối:**
   - Delta tăng tối đa: **+10.000.000 byte** (10 MB thập phân).
   - Kích thước APK tối đa cho phép: **85.347.472 byte**.
   - Vượt quá trần này: **FAIL ngay lập tức**, không nghiệm thu, phải tối ưu hoặc loại bỏ dependency/asset dư thừa. Không tự nâng trần.
2. **Mức cảnh báo:**
   - Cảnh báo tại: **+8.000.000 byte** (8 MB thập phân).
   - Kích thước APK cảnh báo: **83.347.472 byte**.
   - Còn lại 2 MB dự phòng rủi ro.
3. **Quy tắc so sánh:**
   - Mọi phép đo sau này phải so sánh với cùng cấu hình release (`minifyEnabled false`, 2 ABI `arm64-v8a` và `armeabi-v7a`).
   - Không tự ý bật R8/minify hay cắt bớt ABI để làm đẹp kết quả đo.
   - Script đo `scripts/measure_ocr_size.ps1` là công cụ duy nhất xác định pass/fail cho gate dung lượng.
