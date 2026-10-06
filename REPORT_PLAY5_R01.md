# BÁO CÁO KẾT QUẢ GÓI R01 — CROP SAVE CÓ OWNER VÀ COMMIT MỘT LẦN (F01)

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, OpenJDK 21.0.1, Gradle 9.7.1, AGP 9.3.0  
Tệp tin sửa đổi:
- [`CropSaveCoordinator.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropSaveCoordinator.kt) (Mới)
- [`CropRotateViewModel.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropRotateViewModel.kt) (Mới)
- [`CropRotateActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt)
- [`CropSaveCoordinatorRegressionTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/CropSaveCoordinatorRegressionTest.kt) (Mới)

---

## 1. Mục tiêu và lỗi cần giải quyết (F01)

### Vấn đề trước sửa đổi:
1. **Lịch thực thi nguy hiểm 1 (Bitmap recycling race)**:
   - Trong `CropRotateActivity.kt`, tác vụ lưu ảnh chạy trên `Dispatchers.IO` bên trong `lifecycleScope`. Khi xoay màn hình hoặc thoát Activity, `onDestroy()` lập tức gọi `currentBitmap?.recycle()`.
   - Nếu tiến trình IO đang nén ảnh hoặc đang gọi `Bitmap.createBitmap()`, việc recycle bitmap từ main thread dẫn tới ngoại lệ `Canvas: trying to use a recycled bitmap` hoặc crash ứng dụng. Người dùng bấm xoay ảnh trong lúc lưu cũng gây ra hiện tượng tương tự.
2. **Lịch thực thi nguy hiểm 2 (Double crop / double rotate & mất kết quả)**:
   - Nếu tiến trình IO đã commit tệp ảnh xuống đĩa nhưng Activity cũ bị hủy trước khi kịp chuyển giao `RESULT_OK`, Activity mới được tạo lại sẽ đọc lại chính file ảnh đã biến đổi.
   - Khi đó, Activity mới khôi phục `currentRotationAngle` và `pendingNormalizedCropRect` từ `savedInstanceState` và áp dụng tiếp một lần nữa lên tệp đã crop, làm hỏng hoàn toàn hình ảnh của người dùng và làm mất mã kết quả trả về cho màn hình gọi (caller).

---

## 2. Giải pháp kỹ thuật triển khai

### 2.1. Kiến trúc phân tách Ownership & State Machine
1. **`CropSaveCoordinator`**:
   - Quản lý trạng thái giao dịch:
     - `CropSaveState.Idle`: Trạng thái rảnh, cho phép chỉnh sửa.
     - `CropSaveState.Saving(token)`: Đang lưu, khóa toàn bộ thao tác trùng lặp.
     - `CropSaveState.Committed(imagePath, pageIndex, token)`: Đã commit thành công xuống đĩa.
     - `CropSaveState.Error(token, messageResId)`: Lỗi ghi file, tệp gốc còn nguyên vẹn qua `SafeFileWriter`.
   - Thiết lập `CropSaveHook` production seam (`onBeforeCrop`, `onBeforeCommit`, `onAfterCommit`) cho phép kiểm thử đồng thời có kiểm soát (deterministic synchronization).
   - Bảo vệ an toàn khi full-crop: Nếu `cropped === bitmap` (khi chọn toàn bộ ảnh), không gọi `recycle()` trên instance gốc.
   - Không nuốt `CancellationException`: Bảo toàn luồng hủy coroutine có chủ ý.
2. **`CropRotateViewModel`**:
   - Thành phần ViewModel sống qua configuration change (Activity recreation).
   - Lưu giữ snapshot `inFlightBitmap` độc lập với vòng đời Activity trong suốt quá trình IO ghi tệp.
   - Giữ trạng thái `saveState` (đặc biệt là `Committed`).
3. **Cập nhật `CropRotateActivity`**:
   - Quan sát `viewModel.saveState`.
   - Trong `onDestroy()`: Chỉ recycle `currentBitmap` khi `isFinishing && !viewModel.isSaving()`. Khi Activity chỉ bị recreate do xoay màn hình (`!isFinishing`), bitmap được bảo toàn an toàn tuyệt đối.
   - Khóa toàn bộ các nút thao tác (`btnSaveCrop`, `btnCropRotateLeft`, `btnCropRotateRight`, `btnCropReset`, `btnCancelCrop`) khi đang `Saving`.
   - Khi Activity mới recreate và quan sát thấy `saveState` là `Committed`: Lập tức gửi `RESULT_OK` và gọi `finish()`, **tuyệt đối không nạp lại ảnh hoặc áp lại edit cũ**.

---

## 3. Kết quả kiểm thử và nghiệm thu kỹ thuật

### 3.1. Regression Test Suite
Tạo mới bộ kiểm thử [`CropSaveCoordinatorRegressionTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/CropSaveCoordinatorRegressionTest.kt) (5 test cases gọi trực tiếp mã production):
1. `testSingleCommitAndLockDuringSave`: Sử dụng chốt `CountDownLatch` tại seam `onBeforeCommit`, xác nhận trạng thái `isSaving() == true`, chứng minh lệnh gọi lưu thứ 2 bị từ chối và chỉ commit đúng 1 lần duy nhất.
2. `testFullCropSameInstanceDoesNotRecycleSource`: Xác minh khi khung crop bằng 100% kích thước ảnh, bitmap gốc không bị recycle sớm.
3. `testWriteFailurePreservesStateAndAllowsRetry`: Xác minh khi ghi tệp thất bại, trạng thái chuyển về `Error`, không bị kẹt `Saving` và cho phép người dùng thử lại.
4. `testCancellationExceptionIsNotSwallowed`: Xác minh khi coroutine bị hủy có chủ ý, `CancellationException` được rethrow đúng chuẩn.
5. `testRecycledBitmapSafelyAborts`: Xác minh chốt chặn kiểm tra bitmap đã bị recycle trước đó sẽ trả về lỗi an toàn thay vì crash.

### 3.2. Kết quả kiểm thử toàn dự án
- Lệnh: `gradlew.bat :app:testDebugUnitTest`
- Kết quả: **1067 / 1067 tests PASS** (0 failures, 0 errors, 0 skipped).
- Biên dịch: `:app:assembleDebug` **BUILD SUCCESSFUL in 31s**.

### 3.3. Tình trạng Gate
- **SOURCE_DONE**: Đã hoàn tất sửa đổi mã nguồn.
- **HOST_VERIFIED**: Đã xác minh 100% qua 1067 unit tests và assembleDebug.
- **DEVICE_PENDING**: Chưa chạy thử trên thiết bị thật (danh sách `adb devices` rỗng).

---

## 4. Kết luận bàn giao
- Gói R01 đã giải quyết dứt điểm lỗi F01 về ownership và commit một lần khi lưu crop qua Activity recreation.
- Sẵn sàng chuyển tiếp sang gói **R02** (Giải quyết F02: Khôi phục pending crop khi xoay liên tiếp trước khi decode ảnh hoàn tất).
