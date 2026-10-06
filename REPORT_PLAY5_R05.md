# Báo cáo kết quả Gói R05: Loại Khoảng Padding Cộng Thừa Giữa XML và Mã Nguồn (F05)

- **Ngày thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn tất phần mã nguồn & kiểm thử logic máy chủ (`SOURCE_DONE`, `HOST_VERIFIED`)
- **Phạm vi xử lý**: Bug F05 (P2) trên toàn bộ 8 Activity và nâng cấp `EdgeToEdgeInsetsHelperTest.kt`.

---

## 1. Bản chất lỗi F05 đã giải quyết

### Lỗ hổng trước khi sửa:
- Helper `EdgeToEdgeInsetsHelper` được thiết kế chuẩn mực với phương thức `recordInitialPadding(view)` lưu trữ toàn bộ giá trị padding đã khai báo trong tệp XML layout.
- Tuy nhiên, trong quá trình di chuyển mã (migration) trước đó, các hằng số đệm cũ trong mã nguồn (`extraBottom`, `extraTop`) vẫn được truyền vào các hàm `applyBottomBarInsets` và `applyTopBarInsets`.
- Công thức tính toán của helper là:
  `paddingBottom = initial.bottom + bottomInset + extraBottom`
- Do các tệp layout XML đã có sẵn padding thiết kế (ví dụ: `paddingVertical="12dp"`, `padding="12dp"`, `paddingBottom="24dp"`), việc cộng thêm `extraBottom` trong code khiến:
  + `CropRotateActivity`: `12dp (XML) + 12dp (code) + inset` = **24dp + inset** (bị cộng đúp 12dp).
  + `OcrResultActivity`: `12dp (XML) + 12dp (code) + inset` = **24dp + inset** (bị cộng đúp 12dp).
  + `CameraScanActivity`: top thành **20dp + inset** (12dp XML + 8dp code), bottom thành **40dp + inset** (24dp XML + 16dp code).
  + `PostScanEditorActivity`: bottom thành **12dp + inset** (6dp XML + 6dp code).
  + `DocumentManagementActivity`: bottom thành **48dp + inset** (24dp XML + 24dp code).
  + `PdfViewerActivity`: bottom thành **24dp + inset** (12dp XML + 12dp code).
  + `IdCardComposeActivity`: bottom thành **24dp + inset** (12dp XML + 12dp code).
- Hậu quả: Không gian hiển thị (workspace) bị thu hẹp bất hợp lý, nút bấm và nội dung bị đẩy xa mép dưới quá mức, đặc biệt khi xoay ngang màn hình (landscape) hoặc khi mở bàn phím ảo IME.

---

## 2. Giải pháp kỹ thuật

1. **Chuẩn hóa nguồn padding duy nhất (Single Source of Truth - XML Layout)**:
   - Rà soát toàn bộ 8 Activity và loại bỏ toàn bộ các hằng số `extraBottom`/`extraTop` được cộng thêm trong mã nguồn:
     + `CameraScanActivity.kt`: Loại bỏ `extraTop` (8dp) và `extraBottom` (16dp).
     + `CropRotateActivity.kt`: Loại bỏ `extraBottom` (12dp).
     + `OcrResultActivity.kt`: Loại bỏ `extraBottom` (12dp).
     + `PostScanEditorActivity.kt`: Loại bỏ `extraBottom` (6dp).
     + `DocumentManagementActivity.kt`: Loại bỏ `extraBottom` (24dp).
     + `PdfViewerActivity.kt`: Loại bỏ `extraBottom` (12dp).
     + `IdCardComposeActivity.kt`: Loại bỏ `extraBottom` (12dp).
     + `MainActivity.kt`: Đã chuẩn sẵn từ trước (`extraBottom = 0`).
   - Giữ nguyên các giá trị padding khởi tạo ban đầu được định nghĩa trong XML và chỉ cộng thêm khoảng insets thực tế của hệ thống (`bottomInset = sysInsets.bottom` hoặc `effectiveBottom`).

2. **Nâng cấp bộ kiểm thử `EdgeToEdgeInsetsHelperTest.kt` (Giải quyết phản biện V01)**:
   - Loại bỏ các vòng lặp tự cộng số độc lập không gọi production code.
   - Thay thế bằng việc gọi trực tiếp các phương thức production:
     + `EdgeToEdgeInsetsHelper.applyTopBarInsets`
     + `EdgeToEdgeInsetsHelper.applyBottomBarInsets`
     + `EdgeToEdgeInsetsHelper.recordInitialPadding`
   - Bổ sung test case `testApplyTopAndBottomBarInsets_productionCalls_withoutDoublePadding` khẳng định: Khi gọi production helper với `extraBottom = 0`, padding đáy bằng đúng `initial.bottom (XML) + bottomInset`, hoàn toàn không có padding thừa.

---

## 3. Bằng chứng kiểm thử & Xác minh

### Unit & Regression Test:
- Chạy toàn bộ suite `EdgeToEdgeInsetsHelperTest.kt` và suite 1075 unit tests:
  1. `testCalculateEffectiveBottom_withoutIme_usesSystemBarsOnly`: PASS.
  2. `testCalculateEffectiveBottom_withIme_usesMaxAndNeverDoubleCounts`: PASS.
  3. `testCalculateEffectiveBottom_withImeClosed_usesSystemBarBottom`: PASS.
  4. `testApplyTopAndBottomBarInsets_productionCalls_withoutDoublePadding`: PASS (gọi trực tiếp production helper).
  5. `testInitialPaddingRecord_andMultipleDispatchInvariance_callingProductionHelper`: PASS (gọi trực tiếp production helper qua 10 lần dispatch).

### Kết quả chạy lệnh:
- `:app:testDebugUnitTest`: **1075 tests completed, 0 failures, 0 errors, 0 skipped**.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.

---

## 4. Phân loại Gate nghiệm thu

| Gate | Trạng thái | Ghi chú |
|---|---|---|
| Mã nguồn & Logic | **SOURCE_DONE** | Đã sửa 7 Activity và nâng cấp `EdgeToEdgeInsetsHelperTest.kt`. |
| Kiểm thử máy chủ (Host) | **HOST_VERIFIED** | 1075 unit tests pass, compilation sạch. |
| Kiểm thử thiết bị thực | **DEVICE_PENDING** | Cần đo đạc visual spacing thực tế trên thiết bị thật / emulator ở portrait, landscape và IME. |
| Play Console Warning | **PLAY_PENDING** | Thuộc nhóm cảnh báo Edge-to-edge. |
