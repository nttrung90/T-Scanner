# Báo cáo Baseline F00 — Cố định Baseline & Bảo toàn Probe trước Sửa chữa

- **Thời điểm ghi nhận**: 2026-09-24T09:23:00+07:00
- **Git HEAD**: `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`
- **Mục tiêu**: Cố định trạng thái baseline, kết quả bộ chuẩn, xác minh sự tồn tại và tái hiện của probe độc lập, không sửa mã nguồn production.
- **Trạng thái production**: **KHÔNG SỬA ĐỔI** trong gói F00.

---

## 1. Môi trường Build & Trạng thái Git

### 1.1. Cấu hình môi trường
- **Hệ điều hành**: Windows 10 Pro 64-bit (10.0 amd64)
- **Java Runtime**: Oracle JDK 22 (`build 22+36-2370`)
- **Gradle**: 9.7.1 (Kotlin 2.4.0, Groovy 4.0.32, Ant 1.10.17)
- **Android Gradle Plugin / Compile SDK**: `compileSdk 36`, `minSdk 26`, `targetSdk 36`
- **Application ID**: `com.tscanner.app`
- **Version**: `versionCode 15`, `versionName "0.8.0"`
- **ABI Filters**: `arm64-v8a`, `armeabi-v7a`

### 1.2. Trạng thái Working Tree
- Working tree có sẵn **108 file đã sửa đổi (modified)** và **70+ file chưa theo dõi (untracked)**.
- Toàn bộ working tree được bảo toàn 100%, không thực hiện `git reset`, `git clean` hay `git checkout`.
- Đã tạo thư mục log riêng biệt: `build/paddle-removal-fix/`.

---

## 2. Trạng thái Artifacts Hiện có

### 2.1. Baseline Artifacts (P00)
- **Baseline Release APK**: `app/build/baseline_paddle_removal/baseline-p00-release-unsigned.apk`
  - Kích thước: `75,507,865 byte` (75.51 MB)
  - SHA-256: `0079E414FD2B5B75F762CEBB565E4D41FE722335D53B77EA07704208D4A2404D`
- **Baseline Release AAB**: `app/build/baseline_paddle_removal/baseline-p00-release.aab`
  - Kích thước: `44,799,144 byte` (44.80 MB)
  - SHA-256: `A6EDB6E1D33E67CE7A9BA2AC62933325D042FE9447865AC37A5A53A23CD740D8`

### 2.2. Candidate Artifacts (Sau P06)
- **Candidate Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`
  - Kích thước: `28,691,181 byte` (28.69 MB)
  - SHA-256: `DD8A0389C4FE036A0B23D32306CC7D8B8020F089B0E447A978BB421710CF247E`
  - Số entry Paddle/ONNX: `0`
- **Candidate Release AAB**: `app/build/outputs/bundle/release/app-release.aab`
  - Kích thước: `17,988,354 byte` (17.99 MB)
  - SHA-256: `1BFC251B23DEE377AF9DDFDF697942AF4AF3151BBB504BEA90B3DA06D36AF061`
  - Số entry Paddle/ONNX: `0`

---

## 3. Tái hiện Probe Độc lập (`PaddleIndependentReauditTest`)

- **Vị trí file probe**: `build/paddle-reaudit/tests/com/tscanner/app/PaddleIndependentReauditTest.kt`
- **Lệnh thực thi**:
  ```powershell
  .\gradlew.bat -I build/paddle-reaudit/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.PaddleIndependentReauditTest --offline --console=plain
  ```
- **Kết quả**: `BUILD FAILED` (3 tests completed, **2 failed**).
  1. `fileDeletionCancellationMustPropagate`: **FAILED** (AssertionError: Cancellation during file deletion was swallowed).
  2. `directoryDeletionCancellationMustPropagate`: **FAILED** (AssertionError: Cancellation during directory deletion was swallowed).
  3. `cleanupPreservesUnknownAndSiblingData`: **PASSED** (Control test bảo toàn dữ liệu file lạ và file sibling).
- **Log lưu trữ**: `build/paddle-removal-fix/probe.log`.

---

## 4. Kiểm chuẩn Bộ chuẩn Hiện tại (Không có Probe Init Script)

- **Lệnh thực thi**:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
  ```
- **Kết quả Test Suite**: `BUILD SUCCESSFUL`
  - **Tổng số tests**: **460 tests passed**
  - **Failures**: **0**
  - **Skipped**: **0**
  - **Thời gian chạy**: ~6.8s
- **Kết quả Lint (`app/build/reports/lint-results-debug.html`)**:
  - **Errors**: **0**
  - **Warnings**: **718** (lỗi nền có sẵn từ trước)
- **Log lưu trữ**: `build/paddle-removal-fix/baseline-test.log`, `build/paddle-removal-fix/standard-test.log`.

---

## 5. Danh mục 3 Vấn đề Cần Khắc phục (R01, R02, R03)

| Mã vấn đề | Mức độ | Vị trí | Nguyên nhân cốt lõi | Kế hoạch xử lý |
|---|---|---|---|---|
| **R01** | P2 | `utils/TextRecognitionHelper.kt:746-801` | Recognizer ML Kit chỉ được đóng trong `invokeOnCancellation`; listener Success/Failure resume coroutine nhưng không đóng client. | Gói **F01a** (tạo seam và test đỏ) & **F01b** (cơ chế close-once terminal handling). |
| **R02** | P3 | `utils/LegacyPaddleCleanup.kt:138-147, 155-159` | Hai khối `catch (t: Throwable)` nuốt `CancellationException` khi xóa file và thư mục. | Gói **F02** (chuyển probe thành test chuẩn, rethrow cancellation, thêm checkpoint `ensureActive`). |
| **R03** | Tài liệu & Kiểm chứng | `docs/paddle-removal/` & verification test | `PaddleRemovalVerificationTest.kt` mới chỉ kiểm tra model serialization in-memory; thiếu test repository disk round-trip và docx/xlsx export; tài liệu P07 hướng dẫn dùng unsigned APK. | Gói **F03** (test repository/export thực) & **F04** (sửa tài liệu & chuẩn bị 2 APK cùng test key). |

---

## 6. Kết luận Cổng F00

- Đã cố định toàn bộ thông số baseline và hash artifact.
- Đã xác minh tái hiện thành công 2 ca thất bại của probe độc lập R02.
- Mã nguồn production hoàn toàn giữ nguyên, chưa bị sửa đổi.
- Sẵn sàng chuyển giao sang gói tiếp theo: **F01a**.
