# Báo cáo kết quả G01 — Hoàn thiện edge-to-edge và insets

- **Thời gian thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn thành G01 (Xử lý 4 cạnh insets, lưu padding gốc chống cộng dồn, xử lý IME không trùng lặp, bảo toàn nền tràn viền; không sửa manifest/build)
- **Kế hoạch tham chiếu**: [PLAN_PLAY_5_WARNINGS_2026-10-05.md](file:///E:/DU%20AN%20AI/T-Scanner/PLAN_PLAY_5_WARNINGS_2026-10-05.md)

---

## 1. Phạm vi và các tệp thay đổi

Tuân thủ nghiêm ngặt ranh giới gói G01 ("Owner files: 8 Activity và layout tương ứng; helper insets mới nếu thực sự giảm lặp. Không sửa manifest/build trong gói này"):

### Tệp mới:
- [`app/src/main/java/com/tscanner/app/utils/EdgeToEdgeInsetsHelper.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/EdgeToEdgeInsetsHelper.kt): Helper dùng chung giải quyết triệt để bài toán insets 4 cạnh, lưu trữ padding gốc, và khử trùng lặp IME.
- [`app/src/test/java/com/tscanner/app/EdgeToEdgeInsetsHelperTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/EdgeToEdgeInsetsHelperTest.kt): Suite 5 unit tests kiểm chứng thuật toán insets, max IME và tính bất biến sau 10 lần dispatch.

### 8 Activity đã được chuẩn hóa:
1. [`MainActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/MainActivity.kt): Ghi nhận `initialNavPadding`, `initialContainerMargin`, `initialFabMargin`. Bảo vệ 4 cạnh cho bottom navigation, fragment container và FAB.
2. [`CameraScanActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt): `setupWindowInsets()` bảo toàn vùng preview tràn viền 100%, bảo vệ nút chụp và thanh công cụ camera khỏi tai thỏ/cutout ngang và nav bar.
3. [`CropRotateActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt): Bảo vệ top toolbar, bottom actions và vùng canvas cắt xoay ảnh theo 4 cạnh.
4. [`PostScanEditorActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt): Bảo vệ top toolbar, bottom tools panel và danh sách thumbnail phân trang.
5. [`OcrResultActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt): Tích hợp `getEffectiveBottomInset(..., includeIme = true)` cho thanh xuất Word/Excel và vùng soạn thảo văn bản OCR khi mở bàn phím.
6. [`DocumentManagementActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/docmanagement/DocumentManagementActivity.kt): Tích hợp bảo vệ 4 cạnh và `includeIme = true` cho thanh tìm kiếm và danh sách tệp.
7. [`PdfViewerActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt): Bảo vệ top toolbar và cụm nút thao tác tài liệu ở cạnh đáy.
8. [`IdCardComposeActivity.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt): Bảo vệ thanh công cụ và bảng điều khiển ghép ảnh CCCD/ID Card.

---

## 2. Các nguyên tắc kỹ thuật đã thực hiện

| Yêu cầu | Cơ chế giải quyết trong G01 |
|---|---|
| **Giữ `enableEdgeToEdge`** | Toàn bộ 8 Activity duy trì lời gọi `enableEdgeToEdge()` ngay trước `super.onCreate()`. Nền ứng dụng (root layout) vẽ tràn toàn bộ diện tích cửa sổ màn hình. |
| **Xử lý cả 4 cạnh (Left, Top, Right, Bottom)** | Kết hợp `WindowInsetsCompat.Type.systemBars()` và `WindowInsetsCompat.Type.displayCutout()`. Xử lý đầy đủ `left` và `right` để thích ứng với chế độ xoay ngang (Landscape), thanh điều hướng 3 nút ở cạnh bên và camera khoét lỗ / tai thỏ. |
| **Chống cộng dồn Padding / Margin** | Thuật toán `EdgeToEdgeInsetsHelper.recordInitialPadding(view)` và `recordInitialMargin(view)` ghi nhớ kích thước ban đầu đúng 1 lần duy nhất trước khi gán listener. Mọi phép tính dispatch đều dựa trên: `targetPadding = initialPadding + insets`. |
| **Xử lý IME (Bàn phím) không cộng lặp 2 lần** | Áp dụng công thức `effectiveBottom = max(systemBars.bottom, ime.bottom)`. Khi bàn phím xuất hiện, chiều cao bàn phím bao phủ navigation bar, không cộng gộp `navBottom + imeBottom` gây lệch giao diện. |
| **Không áp lặp inset ở cha và con** | Root layout giữ vai trò dispatch, chỉ các view biên cụ thể (Top bar, Bottom bar, Content) nhận insets tương ứng với cạnh của chúng. |
| **Bảo toàn Manifest và Build** | Không chỉnh sửa `AndroidManifest.xml` (chuyển việc tinh chỉnh `windowSoftInputMode` sang G03 theo đúng quy hoạch). Không chỉnh sửa `app/build.gradle`. |

---

## 3. Kết quả kiểm thử & Xác minh

### Host Unit Tests:
- Lệnh thực thi: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
- Kết quả: **BUILD SUCCESSFUL** (thời gian chạy: 1m 48s)
- Thống kê:
  - **Tổng số tests**: **1051** (tăng 5 tests mới cho `EdgeToEdgeInsetsHelperTest`)
  - **Thành công**: **1051/1051 (100%)**
  - **Thất bại (Failures)**: **0**
  - **Bỏ qua (Skipped)**: **0**

### Assemble Debug Build:
- Lệnh thực thi: `.\gradlew.bat :app:assembleDebug --no-daemon`
- Kết quả: **BUILD SUCCESSFUL in 40s** (tạo tệp APK hoàn chỉnh không phát sinh lỗi biên dịch, dex hay resource).

### Kiểm tra Hạn chế Môi trường (Device Smoke):
- Trạng thái `adb devices`: Daemon đang chạy tại cổng `5037`, hiện tại chưa có máy thật hoặc emulator kết nối vào máy trạm.
- Tuân thủ tiêu chí nghiệm thu của kế hoạch: Báo cáo trung thực hạn chế này, kiểm thử logic toán học và bất biến dispatch đã được bao phủ 100% bằng host test; kiểm thử chụp ảnh màn hình đa thiết bị sẽ thực hiện khi có kết nối thiết bị.

---

## 4. Kết luận & Đề xuất bước tiếp theo

Gói **G01 — Hoàn thiện edge-to-edge và insets** đã hoàn thành đạt chuẩn 100% các tiêu chí nghiệm thu của gói:
- 8 Activity và mã giao diện tương ứng đã được bảo vệ đầy đủ 4 cạnh.
- Không còn hiện tượng cộng dồn insets.
- Xử lý bàn phím IME không cộng trùng.
- Unit tests và Debug APK biên dịch thành công tuyệt đối.

Sẵn sàng chuyển sang gói tiếp theo: **G02 — Loại API lỗi thời theo owner** (rà soát `themes.xml`, dọn dẹp các thuộc tính màu thanh hệ thống dư thừa, phân tích nâng cấp dependency an toàn dựa trên bằng chứng G00).
