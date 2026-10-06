# Báo cáo Nghiệm thu Thiết bị Thực tế P07 — Loại bỏ PaddleOCR & ONNX Runtime

- **Thời điểm lập báo cáo**: 2026-09-24T09:05:00+07:00
- **Git HEAD**: `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8`
- **Mục tiêu**: Nghiệm thu hành vi thực tế trên Android: nâng cấp tại chỗ từ bản baseline có Paddle sang candidate không có Paddle, tự động dọn dẹp model cũ trong bộ nhớ riêng, tải unbundled model ML Kit Chinese qua Google Play Services, nhận diện ký tự tiếng Trung và bảo vệ toàn vẹn dữ liệu.
- **Trạng thái cổng P07**: **CHƯA CHẠY TRÊN THIẾT BỊ** (Hiện tại không có thiết bị thật hoặc máy ảo emulator kết nối qua ADB).
- **Quy tắc phát hành**: **KHÔNG PHÁT HÀNH TỰ ĐỘNG**. Chỉ phát hành sau khi các ca kiểm thử bên dưới được nghiệm thu thành công trên ít nhất một thiết bị ARM64 (và ARM32 nếu có phát hành).

---

## 1. Trạng thái Kết nối Thiết bị (ADB Check)

- **Lệnh thực thi**:
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
  ```
- **Kết quả trả về**:
  ```text
  List of devices attached
  (trống)
  ```
- **Kết luận**: Môi trường build hiện tại không phát hiện thiết bị hoặc emulator Android đang chạy. Theo đúng cam kết tại Mục 3 và Mục 4 của kế hoạch `PLAN_REMOVE_PADDLE_ONNX_SMALL_MODEL_2026-09-23.md`, tất cả 13 ca kiểm thử thiết bị được ghi nhận là **CHƯA CHẠY** và chuẩn bị sẵn ma trận kịch bản chi tiết để người kiểm thử / QA thực hiện ngay khi có thiết bị.

---

## 2. Ma trận 13 Ca Nghiệm thu Bắt buộc trên Thiết bị Android

| STT | Ca kiểm thử bắt buộc | Quy trình thực hiện & Lệnh ADB | Kết quả cần đạt (Pass Criteria) | Trạng thái thực tế |
|:---:|---|---|---|:---:|
| **1** | **Nâng cấp tại chỗ, prefs Paddle + zh-Hant** | 1. Cài đặt APK Baseline (bản ký debug hoặc ký bằng test keystore)<br>2. Thiết lập SharedPreferences: engine="paddle", language="zh-Hant"<br>3. Cài đặt đè bản Candidate đã ký bằng cùng keystore (`adb install -r`) không uninstall | Cùng `applicationId` và keystore signing. Sau khi mở bản mới: UI tự động hiển thị engine là "Auto", ngôn ngữ zh-Hant được giữ nguyên, không crash. | **CHƯA CHẠY** |
| **2** | **Root model cũ đầy đủ** | 1. Đặt đủ 4 file model cũ vào `/data/data/com.tscanner.app/files/paddleocr/`<br>2. Đặt file sentinel vào `tessdata` và `ocr_docs`<br>3. Khởi động app mới | Cả 4 file model allowlist biến mất; thư mục `paddleocr` bị xóa; khởi động lại app không tạo lại; hash SHA-256 các file sentinel giữ nguyên 100%. | **CHƯA CHẠY** |
| **3** | **Root có file lạ hoặc xóa lỗi** | 1. Tạo file lạ `custom_notes.txt` hoặc subfolder trong `files/paddleocr/`<br>2. Mở app mới | 4 file allowlist bị xóa; file lạ và subfolder lạ được giữ nguyên; thư mục `paddleocr` không bị xóa ép; app không crash. | **CHƯA CHẠY** |
| **4** | **Cài mới, offline, Chinese model chưa có** | 1. Gỡ cài đặt cũ, tắt Wi-Fi/4G (`adb shell svc wifi disable; svc data disable`)<br>2. Cài mới Candidate APK đã ký<br>3. Thử quét OCR một trang tiếng Trung | Hiển thị thông báo thân thiện "Đang tải / cần kết nối mạng để tải module tiếng Trung"; tuyệt đối không báo trang trắng (NoText), không mất ảnh đã chụp. | **CHƯA CHẠY** |
| **5** | **Cho phép tải model, thử lại rồi offline** | 1. Bật lại Wi-Fi, nhấn "Thử lại" để Play Services tải module Chinese (`play-services-mlkit-text-recognition-chinese`)<br>2. Tắt mạng để thử offline | OCR tiếng Trung thành công khi model đã tải về máy; các lần quét tiếp theo khi offline hoàn toàn đều nhận diện tốt trên cả Auto và ML Kit thủ công. | **CHƯA CHẠY** |
| **6** | **Play Services thiếu/lỗi trên môi trường thử** | 1. Chạy trên emulator không có Google Play Services (AOSP Image) hoặc tắt Play Services | OCR tiếng Trung báo lỗi có kiểm soát; các chức năng chụp ảnh, crop, xử lý ảnh và Tesseract tiếng Việt/Anh vẫn hoạt động bình thường. | **CHƯA CHẠY** |
| **7** | **OCR zh-Hans và zh-Hant thực tế** | 1. Quét tài liệu chữ Hán giản thể (zh-Hans) và phồn thể (zh-Hant)<br>2. So sánh ảnh trước/sau nâng cấp | Chữ Hán, dấu câu, khung bao từ/dòng, thứ tự đọc top-to-bottom và xuất file text/PDF sử dụng tốt; được đánh giá bởi người đọc được tiếng Trung. | **CHƯA CHẠY** |
| **8** | **Ảnh bảng/nhỏ/nghiêng/mixed Chinese-English** | 1. Chụp bảng biểu tiếng Trung có ô số liệu, văn bản nghiêng nhẹ, trộn tiếng Anh | Bảng biểu nhận diện đúng cột/dòng; văn bản trộn tiếng Trung - Anh không bị nuốt chữ; ghi nhận độ chính xác chi tiết. | **CHƯA CHẠY** |
| **9** | **Tài liệu OCR Paddle cũ, line-only** | 1. Nạp tài liệu quét cũ có `engineId="paddle"`, dòng không có word tokens<br>2. Mở trong reader, chọn vùng, sửa chữ, lưu lại, mở lại, xuất file | Mở và hiển thị bình thường; hit-test chọn dòng hoạt động tốt; sửa chữ lưu lại không mất edit; metadata `engineId="paddle"` giữ nguyên. | **CHƯA CHẠY** |
| **10** | **OCR nhiều trang có một trang thiếu model/lỗi** | 1. Quét tài liệu 3 trang (trang 1 tiếng Việt, trang 2 tiếng Trung khi chưa có model, trang 3 tiếng Việt) | Batch báo `PageError` tại trang 2; không báo thành công toàn bộ; không bỏ qua trang âm thầm; giữ dữ liệu để người dùng thử lại. | **CHƯA CHẠY** |
| **11** | **Thoát/xoay màn hình/hủy OCR** | 1. Bắt đầu OCR rồi nhấn Back hoặc xoay màn hình thiết bị liên tục | Coroutine hủy an toàn; không callback vào Activity đã hủy; không gây crash app; không mất dữ liệu scan draft. | **CHƯA CHẠY** |
| **12** | **Tiếng Việt / VI_EN / Latin / Nhật / Hàn / Hindi** | 1. Quét lần lượt các ngôn ngữ khác đang hỗ trợ | Không có bất kỳ regression nào so với baseline; Tesseract và ML Kit các ngôn ngữ khác chạy ổn định. | **CHƯA CHẠY** |
| **13** | **Smoke test luồng chụp/crop/filter/PDF/QR/Drive** | 1. Chụp ảnh bằng CameraX, crop 4 góc, áp bộ lọc magic/B&W, tạo PDF, quét QR, đồng bộ Google Drive | Toàn bộ các luồng chức năng cốt lõi trước đó vẫn hoạt động bình thường 100%. | **CHƯA CHẠY** |

---

## 3. Hướng dẫn Thực hiện Nghiệm thu khi Kết nối Thiết bị

Khi gắn thiết bị Android (hoặc khởi động Android Emulator) qua USB/TCP:

### Bước 1: Kiểm tra kết nối và ABI thiết bị
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell getprop ro.product.cpu.abi
```
*Yêu cầu*: Thiết bị phải có ABI `arm64-v8a` hoặc `armeabi-v7a`.

### Bước 2: Cài đặt và nghiệm thu bản Candidate

> [!WARNING]
> Android Package Manager (đặc biệt từ Android 11 trở lên) sẽ từ chối cài đặt trực tiếp file APK unsigned (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). Phải sử dụng một trong các phương thức cài đặt có chữ ký số hợp lệ sau:

#### Phương thức A: Sử dụng bản Debug (Khuyên dùng cho kiểm thử chức năng nội bộ)
Bản `app-debug.apk` đã được Gradle tự động ký bằng `debug.keystore` mặc định:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

#### Phương thức B: Ký bản Candidate Release bằng `apksigner` trước khi cài đặt
Để kiểm thử chính xác bản Release Candidate (đã qua ProGuard/R8 tối ưu và rút gọn), ký bằng test keystore hoặc debug keystore:
```powershell
# Ký candidate release bằng apksigner
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" sign `
    --ks "$env:USERPROFILE\.android\debug.keystore" `
    --ks-pass "pass:android" `
    --key-pass "pass:android" `
    --ks-key-alias "androiddebugkey" `
    --out "app\build\outputs\apk\release\app-release-signed.apk" `
    "app\build\outputs\apk\release\app-release-unsigned.apk"

# Cài đặt APK đã ký lên thiết bị
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\release\app-release-signed.apk"
```

#### Phương thức C: Cài đặt qua `bundletool` từ AAB (Mô phỏng cài đặt Play Store thực tế)
```powershell
# Sinh tập APKs từ AAB
java -jar bundletool.jar build-apks `
    --bundle="app\build\outputs\bundle\release\app-release.aab" `
    --output="app\build\outputs\bundle\release\app.apks" `
    --ks="$env:USERPROFILE\.android\debug.keystore" `
    --ks-pass="pass:android" `
    --ks-key-alias="androiddebugkey" `
    --key-pass="pass:android" `
    --mode=default

# Cài đặt tập APKs lên thiết bị kết nối
java -jar bundletool.jar install-apks --apks="app\build\outputs\bundle\release\app.apks"
```

### Bước 3: Xem logcat kiểm tra dọn dẹp LegacyPaddleCleanup
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s LegacyPaddleCleanup TScannerApplication
```
*Kỳ vọng*: Log hiển thị `LegacyPaddleCleanup` đã kiểm tra và dọn sạch các file allowlist cũ thành công.

---

## 4. Kết luận Nghiệm thu P07

- **Trạng thái cổng P07**: **CHƯA CHẠY** (Do thiếu thiết bị/emulator vật lý tại phiên làm việc).
- **Đánh giá rủi ro máy build**: Hoàn tất 100% các cổng P00 → P06 với 460 unit tests xanh, 0 lint error, release APK giảm 46.82 MB (từ 75.51 MB xuống 28.69 MB).
- **Khuyến nghị phát hành**: Chưa phát hành bản dựng ra Google Play Store hoặc production cho đến khi người phụ trách kiểm thử hoàn tất 13 ca kiểm tra trên thiết bị thực tế theo bảng tại Mục 2.
