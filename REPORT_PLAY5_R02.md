# Báo cáo kết quả Gói R02: Restore Crop Khi Decode Chưa Xong (F02)

- **Ngày thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn tất phần mã nguồn & kiểm thử logic máy chủ (`SOURCE_DONE`, `HOST_VERIFIED`)
- **Phạm vi xử lý**: Bug F02 (P2) trong `CropRotateActivity`, `CropOverlayView`, `CropRotateViewModel`.

---

## 1. Bản chất lỗi F02 đã giải quyết

### Lỗ hổng trước khi sửa:
- Khi người dùng điều chỉnh vùng cắt (crop rect) tùy chỉnh rồi xoay thiết bị, `CropRotateActivity` trải qua quá trình hủy và tái tạo (`recreation`).
- Trong quá trình tái tạo, coroutine `loadImage()` giải mã ảnh `BitmapFactory.decodeFile` bất đồng bộ trên `Dispatchers.IO`.
- Nếu thiết bị tiếp tục xoay một lần nữa (hoặc cấu hình thay đổi) trong khi tác vụ giải mã ảnh nền chưa kịp kết thúc:
  - `CropOverlayView` chưa có kích thước ảnh thực tế (`imageBounds.isEmpty == true`, `width <= 0`).
  - Hàm `onSaveInstanceState` cũ gọi trực tiếp `cropOverlayView.getNormalizedCropRect()`, và hàm này trả về hình chữ nhật toàn màn hình mặc định `RectF(0f, 0f, 1f, 1f)`.
  - Kết quả là `pendingNormalizedCropRect` tùy chỉnh trước đó của người dùng bị **ghi đè hoàn toàn** thành toàn ảnh, làm mất dữ liệu vùng cắt của người dùng.

---

## 2. Giải pháp kỹ thuật

1. **Bổ sung trạng thái khởi tạo và bảo tồn tỉ lệ chuẩn hóa trong `CropOverlayView`**:
   - Thêm phương thức `isInitialized(): Boolean` kiểm tra `imageBounds.width() > 0 && imageBounds.height() > 0`.
   - Bổ sung `setImageBoundsPreservingNormalizedRect(bounds: RectF)` nhằm bảo toàn tọa độ chuẩn hóa hiện tại khi kích thước view thay đổi sau đó (như khi resize window hoặc multi-window).

2. **Quản lý trạng thái vùng cắt chờ xử lý trong `CropRotateViewModel`**:
   - Đưa thuộc tính `pendingNormalizedCropRect: RectF?` vào `CropRotateViewModel` (sống sót an toàn qua cấu hình thay đổi / Activity recreation).
   - Bổ sung phương thức nghiệp vụ:
     ```kotlin
     fun resolveCropRectForSaveState(isOverlayInitialized: Boolean, currentOverlayRect: RectF): RectF {
         return if (isOverlayInitialized) {
             currentOverlayRect
         } else {
             pendingNormalizedCropRect ?: currentOverlayRect
         }
     }
     ```
   - Nếu `isOverlayInitialized == false`, ưu tiên giữ nguyên `pendingNormalizedCropRect` đã khôi phục thay vì để overlay chưa sẵn sàng ghi đè thành full rect.

3. **Cập nhật vòng đời trong `CropRotateActivity`**:
   - `onSaveInstanceState`: Lưu `viewModel.resolveCropRectForSaveState(...)` vào bundle để bảo vệ trạng thái khi process death.
   - `onCreate`: Khôi phục `KEY_NORMALIZED_CROP_RECT` vào `viewModel.pendingNormalizedCropRect`.
   - `updateImageDisplay`: Khi ảnh đã giải mã xong và container hoàn tất layout:
     - Nếu có `pendingNormalizedCropRect`: áp dụng vào overlay bằng `setNormalizedCropRect(...)` rồi xóa pending (`null`).
     - Nếu không có pending: gọi `setImageBoundsPreservingNormalizedRect(...)`.
   - `saveCroppedImage`: Dùng `viewModel.resolveCropRectForSaveState(...)` để lấy tọa độ an toàn tuyệt đối.
   - `btnCropReset`: Đặt `viewModel.pendingNormalizedCropRect = null` và gọi `resetToFull()`.

---

## 3. Bằng chứng kiểm thử & Xác minh

### Unit & Regression Test:
- Tạo test suite production `CropPendingStateRegressionTest.kt`:
  1. `testPendingCropPreserved_acrossMultipleRecreations_beforeDecodeCompletes`: Xác minh vùng crop tùy chỉnh được bảo toàn qua 3 lần recreate liên tiếp khi overlay chưa khởi tạo.
  2. `testPendingCropConsumed_whenOverlayBecomesInitialized`: Xác minh sau khi layout hoàn tất, pending crop được tiêu thụ và các thay đổi sau đó từ người dùng được ghi nhận chính xác.
  3. `testSlowDecodeWithLatch_pendingCropNotLostDuringInFlightDecode`: Dùng `CountDownLatch` giả lập IO decode bị giữ trong 5 giây, thực hiện vòng lặp 5 lần recreation liên tục; kiểm chứng vùng cắt không bao giờ bị rơi về mặc định.
  4. `testResetClearsPendingCrop_andReturnsFullRect`: Xác minh nút Reset xóa sạch pending crop và trả về đúng toàn bộ ảnh.

### Kết quả chạy lệnh:
- `:app:testDebugUnitTest`: **1071 tests completed, 0 failures, 0 errors, 0 skipped** (tăng thêm 4 test cases).
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.

---

## 4. Phân loại Gate nghiệm thu

| Gate | Trạng thái | Ghi chú |
|---|---|---|
| Mã nguồn & Logic | **SOURCE_DONE** | Đã sửa `CropOverlayView`, `CropRotateViewModel`, `CropRotateActivity`. |
| Kiểm thử máy chủ (Host) | **HOST_VERIFIED** | 1071 unit tests pass, compilation sạch. |
| Kiểm thử thiết bị thực | **DEVICE_PENDING** | Cần kiểm tra xoay màn hình liên tục với ảnh độ phân giải cao trên thiết bị vật lý / emulator. |
| Play Console Warning | **PLAY_PENDING** | Thuộc nhóm cảnh báo Edge-to-edge / Khóa hướng. |
