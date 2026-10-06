# BÁO CÁO KẾT QUẢ GÓI G03b — BỎ KHÓA HƯỚNG VÀ HỖ TRỢ CỬA SỔ THAY ĐỔI (NHÓM 2)

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, JDK 21.0.1, Gradle 9.7.1, AGP 9.3.0, Android Compile/Target SDK 36  
Nhóm đối tượng: Nhóm 2 — `CameraScanActivity`, `CropRotateActivity`, `IdCardComposeActivity`

---

## 1. Mục tiêu và phạm vi
- Xóa bỏ ràng buộc `android:screenOrientation="portrait"` đối với 3 Activity nhóm 2 trong `AndroidManifest.xml`.
- Đảm bảo cơ chế lưu và khôi phục trạng thái (`onSaveInstanceState` / `onCreate`) toàn vẹn khi Activity bị recreate do người dùng xoay màn hình ngang/dọc, gập/mở thiết bị hoặc chia đôi màn hình (split-screen / multi-window).
- Tuyệt đối không lưu đối tượng `Bitmap` trực tiếp vào `Bundle` để phòng ngừa `TransactionTooLargeException` và `OutOfMemoryError`.

---

## 2. Chi tiết triển khai

### 2.1. Phân tích và xử lý trạng thái từng Activity

1. **`CropRotateActivity`**:
   - **Vấn đề trước sửa đổi**: `CropRotateActivity` giữ `currentBitmap: Bitmap?` và chỉ đọc lại file ảnh gốc khi `onCreate`. Khi người dùng xoay ảnh dở (±90°, 180°, 270°) hoặc đang điều chỉnh khung cắt (`CropOverlayView`), nếu xoay màn hình làm Activity recreate thì góc xoay và khung cắt đều bị mất về mặc định.
   - **Giải pháp**:
     - Thêm phương thức `setNormalizedCropRect(normRect: RectF)` vào [`CropOverlayView.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropOverlayView.kt) để cho phép khôi phục tọa độ chuẩn hóa (0.0 đến 1.0) bất kể kích thước View thay đổi giữa chiều ngang và chiều dọc.
     - Cập nhật [`CropRotateActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt):
       - Quản lý `currentRotationAngle: Float` (cộng dồn theo các lần bấm xoay).
       - Trong `onSaveInstanceState(outState: Bundle)`: Lưu `KEY_ROTATION_DEGREES` và `KEY_NORMALIZED_CROP_RECT` (mảng float 4 phần tử: `[left, top, right, bottom]`). Tuyệt đối không lưu Bitmap vào Bundle.
       - Trong `onCreate(savedInstanceState: Bundle?)`: Đọc lại góc xoay và tọa độ khung cắt.
       - Trong `loadImage()`: Áp dụng góc xoay đã lưu ngay khi nạp ảnh từ đĩa trước khi hiển thị.
       - Trong `updateImageDisplay()`: Tái tạo khung `CropOverlayView` với `setNormalizedCropRect` đã khôi phục.

2. **`CameraScanActivity`**:
   - **Hiện trạng kiểm tra**: Đã có sẵn cơ chế `onSaveInstanceState` và khôi phục rất mạnh mẽ:
     - Lưu và khôi phục `KEY_SESSION_ID`, `KEY_CAPTURE_SEQ`, `KEY_AUTO_CROP`, `KEY_FLASH_MODE`, `KEY_PAGE_MAP_INDICES`, `KEY_PAGE_MAP_PATHS`.
     - Tự động tiếp tục xử lý các tác vụ cắt ảnh nền (in-flight captures) chưa hoàn thành khi recreate.
     - CameraX gắn với `ProcessCameraProvider` và lifecycle của Activity tự động xử lý xoay hướng cảm biến và preview.

3. **`IdCardComposeActivity`**:
   - **Hiện trạng kiểm tra**: Đã có sẵn cơ chế `onSaveInstanceState` lưu `IdCardSessionDraft` (chứa `frontImagePath`, `backImagePath`, `config` in A4 1 hoặc 2 mặt), `pendingDriveAuthAttempt`, và `vipContinuationHandler`.
   - Khi recreate, nạp lại ảnh từ các file tạm trên bộ nhớ ứng dụng mà không giữ Bitmap trong Bundle.

### 2.2. Cập nhật AndroidManifest.xml
- Gỡ bỏ thuộc tính `android:screenOrientation="portrait"` khỏi khai báo:
  - `.ui.editor.CropRotateActivity`
  - `.ui.idcard.IdCardComposeActivity`
  - `.ui.camera.CameraScanActivity`

---

## 3. Kết quả kiểm thử và nghiệm thu kỹ thuật

### 3.1. Unit Test & Test Suite
- Tạo mới bộ kiểm thử [`CropRotateStateAndMathTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/CropRotateStateAndMathTest.kt):
  - `testRotationAngles`: Kiểm tra tính toán góc xoay liên tục (90°, 180°, 270°, 360°/0°) và xoay âm (-90° -> 270°).
  - `testNormalizedCropRectRoundTrip`: Kiểm tra tính toàn vẹn chuyển đổi 2 chiều giữa tọa độ tuyệt đối và tọa độ chuẩn hóa normalized (0.0 - 1.0) khi khung nhìn thay đổi kích thước.
  - `testNormalizedCropRectClamping`: Kiểm tra giới hạn biên an toàn của khung cắt.
- Chạy toàn bộ test suite dự án:
  - Lệnh: `gradlew.bat :app:testDebugUnitTest`
  - Kết quả: **1059/1059 tests PASS** (0 failures, 0 skipped, 0 errors).

### 3.2. Biên dịch hệ thống
- Lệnh: `gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL in 24s** (tạo file `app-debug.apk` thành công).

### 3.3. Kiểm thử trên thiết bị thực tế
- Chưa có thiết bị thật/giả lập kết nối ADB (`adb devices` rỗng). Kiểm thử tương tác sensor quay camera và gesture pinch-to-crop được hoãn đến khi có thiết bị.

---

## 4. Kết luận gói G03b
- Gói G03b đã hoàn thành 100% mục tiêu kỹ thuật cho 3 Activity nhóm 2.
- Sẵn sàng chuyển tiếp sang gói **G03c** (Xử lý các ML Kit delegate và rà soát `PdfViewerActivity`, `PostScanEditorActivity`).
