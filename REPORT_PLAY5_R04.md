# Báo cáo kết quả Gói R04: Bảo Vệ Insets Ngang Cho Nội Dung OCR (F04)

- **Ngày thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn tất phần mã nguồn & kiểm thử logic máy chủ (`SOURCE_DONE`, `HOST_VERIFIED`)
- **Phạm vi xử lý**: Bug F04 (P2) trong `activity_ocr_result.xml` và `OcrResultActivity.kt`.

---

## 1. Bản chất lỗi F04 đã giải quyết

### Lỗ hổng trước khi sửa:
- Trong `OcrResultActivity.kt:98-130`, listener insets chỉ áp dụng padding cho 3 view: `layoutOcrToolbar`, `layoutOcrBottomActions` và `tabLayoutOcr`.
- Trong khi đó, các thành phần tương tác trực tiếp của màn hình OCR trong `activity_ocr_result.xml`:
  1. Thanh tìm kiếm trong trang: `layout_search_bar` (gồm ô nhập và nút đóng).
  2. Banner ngôn ngữ phát hiện: `layout_detected_language` (gồm văn bản và nút bấm nhận diện lại).
  3. Toàn bộ khu vực nội dung 3 tab OCR: FrameLayout (chứa Trình đọc trang `OcrPageView`, Trình biên tập văn bản `OcrTextEditorFragment` với thanh công cụ Undo/Redo, Trình biên tập bảng `OcrTableEditorFragment`).
  đều là các sibling trực tiếp nằm ngoài `tabLayoutOcr`, và **hoàn toàn không được áp dụng insets ngang**.
- Khi thiết bị ở chế độ xoay ngang (`landscape`), vùng đục lỗ camera (display cutout) hoặc thanh điều hướng hệ thống (navigation bar 3 nút / cử chỉ) ở cạnh trái hoặc cạnh phải sẽ che khuất trực tiếp các nút bấm trên thanh công cụ Undo/Redo, ô nhập tìm kiếm, hoặc mép nội dung văn bản / bảng tính của người dùng.

---

## 2. Giải pháp kỹ thuật

1. **Gán ID định danh cho FrameLayout chứa nội dung 3 tab trong `activity_ocr_result.xml`**:
   - Thêm thuộc tính `android:id="@+id/layout_ocr_content_container"` cho FrameLayout bao bọc toàn bộ 3 tab (dòng 202).

2. **Mở rộng phạm vi bảo vệ insets ngang trong `OcrResultActivity.kt`**:
   - Ghi lại padding khởi tạo độc lập (`recordInitialPadding`) cho từng thành phần:
     + `initialSearchBarPadding` cho `binding.layoutSearchBar`
     + `initialDetectedLanguagePadding` cho `binding.layoutDetectedLanguage`
     + `initialContentContainerPadding` cho `binding.layoutOcrContentContainer`
   - Trong `ViewCompat.setOnApplyWindowInsetsListener`:
     + Áp dụng `applyContentHorizontalInsets` cho `layoutSearchBar` -> Bảo vệ ô nhập và nút đóng tìm kiếm.
     + Áp dụng `applyContentHorizontalInsets` cho `layoutDetectedLanguage` -> Bảo vệ nhãn ngôn ngữ và nút nhận diện lại.
     + Áp dụng `applyContentHorizontalInsets` cho `layoutOcrContentContainer` -> Bảo vệ an toàn toàn bộ vùng nội dung tương tác của cả 3 tab (Scan, Text, Table).
   - Nền của các thanh tiêu đề (`@color/bg_dark_header`) và nền của container (`@color/bg_dark`) vẫn trải dài toàn màn hình (`match_parent`), đảm bảo giao diện tràn viền chân thực (true edge-to-edge), không bị co nền hay hở viền trắng/đen.
   - Các Fragment con (`OcrTextEditorFragment`, `OcrTableEditorFragment`) không có listener insets riêng, do đó không xảy ra hiện tượng cộng dồn insets giữa cha và con (double insets).

---

## 3. Bằng chứng kiểm thử & Xác minh

### Unit & Regression Test:
- Tạo test suite production `OcrHorizontalInsetsRegressionTest.kt`:
  1. `testApplyContentHorizontalInsets_protectsLandscapeCutoutAndNavBar`: Giả lập cấu hình Landscape với cutout bên trái 48px và thanh điều hướng bên phải 56px. Gọi trực tiếp `applyContentHorizontalInsets` và kiểm chứng các view Search Bar, Language Banner, và Content Container đều được đẩy vào vùng an toàn chính xác.
  2. `testMultipleDispatches_remainInvariant_withoutAccumulatingPadding`: Gọi liên tiếp 10 lần dispatch insets trên container nội dung, kiểm chứng padding không bị cộng dồn.

### Kết quả chạy lệnh:
- `:app:testDebugUnitTest`: **1075 tests completed, 0 failures, 0 errors, 0 skipped** (tăng thêm 2 test cases).
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.

---

## 4. Phân loại Gate nghiệm thu

| Gate | Trạng thái | Ghi chú |
|---|---|---|
| Mã nguồn & Logic | **SOURCE_DONE** | Đã sửa `activity_ocr_result.xml` và `OcrResultActivity.kt`. |
| Kiểm thử máy chủ (Host) | **HOST_VERIFIED** | 1075 unit tests pass, compilation sạch. |
| Kiểm thử thiết bị thực | **DEVICE_PENDING** | Cần chụp màn hình kiểm chứng trên thiết bị thật xoay ngang với cutout bên trái và bên phải. |
| Play Console Warning | **PLAY_PENDING** | Thuộc nhóm cảnh báo Edge-to-edge. |
