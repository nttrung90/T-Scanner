# Báo Cáo Tổng Kết Xử Lý Cảnh Báo Google Play Vòng 2 (PLAY5 Round 2 Final)

- **Ngày hoàn tất**: 05/10/2026
- **Dự án**: `E:\DU AN AI\T-Scanner`
- **Phiên bản mã nguồn**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, `versionCode = 22`, `versionName = "1.2.5"`
- **Trạng thái thực thi**: Đã hoàn thành 100% phạm vi 7 gói tuần tự từ **R00** đến **R06**.

---

## 1. Tóm tắt kết quả xử lý 5 lỗi trọng điểm (F01 – F05)

| Mã lỗi | Mức độ | Bản chất lỗi | Giải pháp kỹ thuật đã áp dụng | Trạng thái |
|---|---|---|---|---|
| **F01** | **P1** | **Crop save không có ownership an toàn qua recreation**: Xoay máy/thoát màn hình khi đang ghi file trên `Dispatchers.IO` khiến `onDestroy` recycle bitmap đang được sử dụng; nguy cơ commit/xoay lặp hai lần và mất kết quả trả về `RESULT_OK`. | Xây dựng state machine `CropSaveCoordinator` (`Idle` -> `Saving` -> `Committed` / `Error`), quản lý luồng bằng `CropRotateViewModel` độc lập với vòng đời Activity, kiểm tra cấm recycle nếu bitmap còn đang được lưu, chốt chặn commit một lần duy nhất. | **SOURCE_DONE**<br>**HOST_VERIFIED** |
| **F02** | **P2** | **Mất vùng cắt khi recreate liên tiếp lúc decode chưa xong**: Khi xoay máy 2 lần liên tiếp với ảnh độ phân giải cao, `onSaveInstanceState` lấy rect từ overlay chưa kịp khởi tạo (`isInitialized == false`), ghi đè tọa độ người dùng thành full rect mặc định `(0,0,1,1)`. | Thêm `isInitialized()` và `setImageBoundsPreservingNormalizedRect()` trong `CropOverlayView`; đưa `pendingNormalizedCropRect` và logic `resolveCropRectForSaveState` vào `CropRotateViewModel` để giữ nguyên vùng cắt qua nhiều lần xoay liên tiếp. | **SOURCE_DONE**<br>**HOST_VERIFIED** |
| **F03** | **P2** | **Request avatar cũ ghi đè icon mặc định sau khi logout**: Khi người dùng đăng xuất hoặc chuyển sang tài khoản không có ảnh, mã nguồn gọi `setImageResource` mà không hủy request Glide in-flight, dẫn đến race condition phản hồi mạng trễ ghi đè ảnh cũ lên icon mặc định. | Tạo bộ điều phối `AvatarViewBinder` tập trung: bắt buộc gọi `engine.clear(imageView)` để hủy triệt để request đang tải trước khi áp dụng drawable fallback trong `MoreFragment` và `AccountDetailDialog`. | **SOURCE_DONE**<br>**HOST_VERIFIED** |
| **F04** | **P2** | **Insets ngang OCR chưa bảo vệ nội dung tương tác**: Search bar, banner ngôn ngữ và FrameLayout chứa 3 tab OCR (Scan, Text, Table) là các sibling không được áp dụng insets ngang, dẫn đến tai thỏ hoặc navigation bar che khuất toolbar Undo/Redo, ô tìm kiếm khi xoay ngang. | Gán ID `layout_ocr_content_container` trong XML; ghi nhận padding độc lập và áp dụng `applyContentHorizontalInsets` cho cả 3 thành phần trong `OcrResultActivity`, bảo toàn nền tràn viền và tránh áp đúp ở cha/con. | **SOURCE_DONE**<br>**HOST_VERIFIED** |
| **F05** | **P2** | **Khoảng padding bị cộng thừa hai lần giữa XML và mã nguồn**: Các Activity truyền thêm hằng số `extraBottom`/`extraTop` trong mã nguồn trong khi layout XML đã có sẵn padding tương ứng, làm nhân đôi khoảng cách đáy lên 24dp – 48dp, thu hẹp workspace khi xoay ngang / mở bàn phím IME. | Rà soát toàn bộ 8 Activity; loại bỏ triệt để `extraBottom` và `extraTop` dư thừa trên 7 Activity (`CameraScan`, `CropRotate`, `OcrResult`, `PostScanEditor`, `DocManagement`, `PdfViewer`, `IdCardCompose`), giữ đúng padding thiết kế từ XML. Nâng cấp `EdgeToEdgeInsetsHelperTest` gọi trực tiếp production helper. | **SOURCE_DONE**<br>**HOST_VERIFIED** |

---

## 2. Đính chính thông số đo đạc & Chuẩn hóa Baseline (V01 & V02)

1. **Đính chính kích thước AAB thực tế (Bác bỏ con số giảm 55.4%)**:
   - Baseline gốc trên đĩa trước khi tối ưu: **17,420,226 bytes** (SHA-256 `5B8FC710A1E8FB4DF22EDC22502DDF7DFC2E17D2901A0F8216B51F0846F6CF3F`).
   - Bản dựng Release Vòng 1: **16,576,487 bytes** (SHA-256 `9A6CF12172EBC9863275A72F6266C62BF435FD479BF186BA9DE43554C6519C51`).
   - Bản dựng Release Vòng 2: **16,584,168 bytes** (SHA-256 `F30D166B0462BF67825F71202F2DA6B98FEE2D2FCB0321836CC065A315D7E154`).
   - **Mức giảm dung lượng thực tế qua R8 & Resource Shrinking là ~836 KB (-4.80%)**, loại bỏ 2,931 tài nguyên dư thừa. Con số giảm 55.4% trước đây là do so sánh sai giữa tệp APK nội bộ chưa tối ưu và gói AAB.
2. **Đính chính mức tiêu thụ RAM (Bác bỏ con số giảm 99.8%)**:
   - Phép tính số học so sánh bitmap thô 4032x3024 (~46.5 MB) với ảnh 128x128 (~64 KB) chỉ là lý thuyết về kích thước ảnh đơn lẻ; mã nguồn trước đó đã dùng Glide downsample theo view size. Do chưa có thiết bị đo lường thực tế qua `dumpsys meminfo`, chỉ số giảm RAM thực tế được ghi nhận là **DEVICE_PENDING**.
3. **Phân tích R8 Merging cho lớp `c5`**:
   - Header ánh xạ lớp `c5` là lambda của `AppAuthManager`, tuy nhiên DEX byte-code gộp cả inlining của `QrScannerHelper` và `TesseractOcrHelper.calculateInSampleSize`. Báo cáo đã cập nhật chi tiết phạm vi dòng, tránh kết luận phiến diện rằng `c5` hoàn toàn không liên quan đến pipeline bitmap.

---

## 3. Danh mục tệp đã tạo mới & Chỉnh sửa

### Tệp tạo mới:
1. `app/src/main/java/com/tscanner/app/ui/editor/CropSaveCoordinator.kt`: State machine điều phối lưu ảnh crop an toàn, chốt chặn recycle và commit một lần.
2. `app/src/main/java/com/tscanner/app/ui/editor/CropRotateViewModel.kt`: ViewModel bảo vệ trạng thái bitmap và pending crop rect qua configuration change.
3. `app/src/main/java/com/tscanner/app/utils/AvatarViewBinder.kt`: Trình liên kết avatar người dùng tập trung, hủy request cũ trước khi fallback.
4. `app/src/test/java/com/tscanner/app/CropSaveCoordinatorRegressionTest.kt`: Bộ kiểm thử 5 ca cho luồng crop save, concurrency latch, lỗi IO và bảo vệ bitmap.
5. `app/src/test/java/com/tscanner/app/CropPendingStateRegressionTest.kt`: Bộ kiểm thử 4 ca cho việc bảo toàn vùng cắt khi xoay màn hình liên tiếp lúc giải mã chậm.
6. `app/src/test/java/com/tscanner/app/AvatarFallbackStaleRequestRegressionTest.kt`: Bộ kiểm thử 2 ca cho việc hủy request avatar và chặn phản hồi mạng trễ ghi đè icon mặc định.
7. `app/src/test/java/com/tscanner/app/OcrHorizontalInsetsRegressionTest.kt`: Bộ kiểm thử 2 ca cho việc bảo vệ insets ngang trên search bar, language banner và container nội dung OCR.
8. Các báo cáo độc lập: `REPORT_PLAY5_R00.md` đến `REPORT_PLAY5_R06.md`, `REPORT_PLAY5_ROUND2_FINAL.md`.

### Tệp chỉnh sửa:
1. `app/src/main/java/com/tscanner/app/ui/editor/CropRotateActivity.kt`: Tích hợp ViewModel, bảo vệ `onDestroy`, giữ pending crop, loại bỏ padding đúp.
2. `app/src/main/java/com/tscanner/app/ui/editor/CropOverlayView.kt`: Bổ sung `isInitialized()` và `setImageBoundsPreservingNormalizedRect()`.
3. `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`: Chuyển sang `AvatarViewBinder.bindAvatar` và `clearAvatar`.
4. `app/src/main/java/com/tscanner/app/ui/dialogs/AccountDetailDialog.kt`: Chuyển sang `AvatarViewBinder.bindAvatar` và `clearAvatar`.
5. `app/src/main/res/layout/activity_ocr_result.xml`: Bổ sung ID `layout_ocr_content_container`.
6. `app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt`: Áp dụng insets ngang cho search bar, banner ngôn ngữ và content container; loại bỏ `extraBottom`.
7. `app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt`: Loại bỏ `extraTop` (8dp) và `extraBottom` (16dp).
8. `app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt`: Loại bỏ `extraBottom` (6dp).
9. `app/src/main/java/com/tscanner/app/ui/docmanagement/DocumentManagementActivity.kt`: Loại bỏ `extraBottom` (24dp).
10. `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`: Loại bỏ `extraBottom` (12dp).
11. `app/src/main/java/com/tscanner/app/ui/idcard/IdCardComposeActivity.kt`: Loại bỏ `extraBottom` (12dp).
12. `app/src/test/java/com/tscanner/app/EdgeToEdgeInsetsHelperTest.kt`: Thay thế phép toán giả lập bằng việc gọi trực tiếp production helper methods.
13. Các báo cáo Vòng 1 (`REPORT_PLAY5_G00.md`, `REPORT_PLAY5_G03c.md`, `REPORT_PLAY5_G04.md`, `REPORT_PLAY5_G05.md`, `REPORT_PLAY5_INTEGRATION_SUMMARY.md`): Đính chính hash và dung lượng baseline.

---

## 4. Bằng chứng kiểm thử & Xác minh toàn diện

Toàn bộ quy trình xác minh tự động đã hoàn tất thành công 100%:

```
> Task :app:testDebugUnitTest
1,075 tests completed, 0 failures, 0 errors, 0 skipped.

> Task :app:lintDebug
0 errors, 768 warnings.

> Task :app:assembleDebug
BUILD SUCCESSFUL

> Task :app:lintRelease
0 errors, 768 warnings.

> Task :app:bundleRelease
BUILD SUCCESSFUL in 6m 49s
Artifact: app-release.aab (16,584,168 bytes)
SHA-256: F30D166B0462BF67825F71202F2DA6B98FEE2D2FCB0321836CC065A315D7E154
```

---

## 5. Bảng phân loại Gate Nghiệm Thu

Nhằm bảo đảm tính khách quan và khoa học, toàn bộ kết quả nghiệm thu được phân loại theo từng cấp độ kiểm chứng rõ ràng:

| Tiêu chí | Phân loại Gate | Giải thích chi tiết |
|---|---|---|
| **Logic nghiệp vụ & Mã nguồn** | **SOURCE_DONE** | Toàn bộ 5 lỗi F01 – F05 đã được sửa hoàn chỉnh trong source code của ứng dụng. |
| **Kiểm thử máy chủ (Host Unit Tests & Lint)** | **HOST_VERIFIED** | 1,075 unit tests (gọi trực tiếp production code và seam) pass 100%; Lint debug và release không có lỗi (0 errors). |
| **Kiểm thử thiết bị vật lý (Real Device / Emulator)** | **DEVICE_PENDING** | Máy phát triển hiện tại không có thiết bị ADB gắn kết. Cần kiểm tra giao diện trực quan trên màn hình xoay ngang có notch/cutout thật và đo RAM bằng `dumpsys meminfo`. |
| **Cảnh báo Google Play Console** | **PLAY_PENDING** | Các cảnh báo Edge-to-edge, Khóa hướng, API cũ, Bitmap mạng, Resource shrinking trên Google Play Console chỉ được Google gỡ bỏ sau khi upload tệp `app-release.aab` lên track kiểm thử và được hệ thống phân tích Play xem xét. |
| **2 Activity phụ thuộc bên ngoài của ML Kit** | **SDK_BLOCKED** | `GmsDocumentScanningDelegateActivity` và `GmsBarcodeScanningDelegateActivity` vẫn bị khóa portrait trong merged manifest do quy định của Google Play Services SDK. Đây là hành vi do SDK kiểm soát, không tự tiện override khi chưa có khuyến cáo chính thức từ Google. |

---

## 6. Khuyến nghị các bước tiếp theo khi có thiết bị / Play Console

1. **Khi có thiết bị vật lý hoặc chạy Emulator**:
   - Cài đặt bản debug hoặc release thông qua `adb install`.
   - Thực hiện kiểm tra trực quan trên màn hình xoay ngang (Landscape): xác nhận các nút trên thanh công cụ OCR, ô tìm kiếm và banner ngôn ngữ hiển thị gọn gàng trong vùng an toàn, không bị đục lỗ camera che khuất.
   - Thử nghiệm thao tác Crop ảnh lớn và xoay thiết bị liên tục để kiểm chứng trải nghiệm mượt mà không bị giật lag hay mất vùng chọn.
2. **Khi sẵn sàng tải lên Google Play Console**:
   - Sử dụng tệp AAB đã được tối ưu hóa: `app/build/outputs/bundle/release/app-release.aab` (hoặc bản lưu trữ tại `baseline_artifacts_20261005/round2_release/app-release-round2.aab`).
   - Đính kèm tệp ProGuard mapping tương ứng: `baseline_artifacts_20261005/round2_release/mapping.txt`.
   - Đưa lên track Internal Testing để hệ thống Pre-launch Report của Google quét kiểm tra và đóng các cảnh báo Play tương ứng.
