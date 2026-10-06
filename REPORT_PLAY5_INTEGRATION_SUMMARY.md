# BÁO CÁO TỔNG KẾT NGHIỆM THU TÍCH HỢP — XỬ LÝ 5 CẢNH BÁO GOOGLE PLAY CONSOLE

Ngày hoàn thành: 05/10/2026  
Môi trường thực hiện: Windows, OpenJDK 21.0.1, Gradle 9.7.1, AGP 9.3.0, Android Compile/Target SDK 36  
Tài liệu kế hoạch gốc: [`PLAN_PLAY_5_WARNINGS_2026-10-05.md`](file:///E:/DU%20AN%20AI/T-Scanner/PLAN_PLAY_5_WARNINGS_2026-10-05.md)

---

## 1. Bảng đối chiếu hiện trạng 5 cảnh báo Google Play Console

| STT | Cảnh báo Play Console | Mốc phân tích ban đầu | Kết quả xử lý & Giải pháp kỹ thuật | Tình trạng kiểm chứng hiện tại |
|---|---|---|---|---|
| **1** | **Edge-to-edge** (Tràn viền & Insets) | 8 Activity đã gọi `enableEdgeToEdge`, nhưng listener chỉ lấy top/bottom, thiếu 4 cạnh và cutout. | Tạo [`EdgeToEdgeInsetsHelper.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/EdgeToEdgeInsetsHelper.kt), lưu `initialPadding`/`margin`, cộng gộp systemBars + displayCutout, lấy `max(systemBars.bottom, ime.bottom)`. Cập nhật 8 Activity. | **SOURCE_DONE** / **HOST_VERIFIED**<br>*(DEVICE_PENDING: Chờ kiểm thử thiết bị)* |
| **2** | **Window APIs lỗi thời** (Màu thanh & SHORT_EDGES) | `themes.xml` còn `statusBarColor`/`navigationBarColor`. Bytecode có `f.s`, `d70.b`, `az0.G`. | Xóa bỏ màu thanh hệ thống trong `themes.xml`. Chứng minh 0 call site trong mã app; các call site trong DEX thuộc `androidx.activity:1.9.3` và `material:1.12.0` phục vụ tương thích ngược API 26-29. | **SOURCE_DONE** / **HOST_VERIFIED**<br>*(PLAY_PENDING: Chờ phân tích Play Console)* |
| **3** | **Khóa hướng màn hình** (Portrait orientation lock) | 6 Activity của app và 2 delegate ML Kit bị khóa portrait trong Manifest. | - Gỡ bỏ hoàn toàn `screenOrientation="portrait"` trên **6/6 Activity** của app.<br>- Xử lý khôi phục trạng thái an toàn cho `DocumentManagementActivity` và `CropRotateActivity`.<br>- Phân định 2 delegate ML Kit vô hình (invisible) do Google Play Services quản lý. | **App (6 Activity): SOURCE_DONE / HOST_VERIFIED** *(DEVICE_PENDING)*<br>**ML Kit (2 Delegate): SDK_BLOCKED** *(Giữ nguyên SDK)* |
| **4** | **Tải Bitmap qua mạng** (Network bitmap decode) | Play Console quét thấy `HttpUrlFetcher` từ Glide. Nghi ngờ có code tải mạng thủ công. | Quét toàn bộ codebase: 0 hàm tải mạng thủ công. Hai vị trí hiển thị avatar Google (`MoreFragment`, `AccountDetailDialog`) được tối ưu hóa: downsampling override (128x128 & 160x160), disk cache, dọn dẹp target theo lifecycle. | **SOURCE_DONE** / **HOST_VERIFIED**<br>*(Vòng 2 F03/R03 đang giải quyết completion race)* |
| **5** | **Thu gọn tài nguyên** (Resource shrinking) | Bản phát hành chưa bật `shrinkResources true`. | Bổ sung `shrinkResources true` trong `app/build.gradle`. Tạo [`keep.xml`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/main/res/raw/keep.xml) bảo vệ tài nguyên chuỗi động. Kích thước AAB giảm từ **17.42 MB xuống 16.58 MB** (-843 KB, ~4.84%), loại bỏ **2,931** tài nguyên dư thừa. | **SOURCE_DONE** / **HOST_VERIFIED**<br>*(PLAY_PENDING: Chờ kiểm chứng Play Console)* |

---

## 2. Danh mục báo cáo chi tiết theo từng gói

Mỗi gói đều được lập báo cáo độc lập đi kèm bằng chứng kiểm thử và biên dịch:
1. [`REPORT_PLAY5_G00.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G00.md): Điều kiện đầu vào, bảo toàn Artifact baseline, giải mã bytecode 8 call sites và Owner Matrix (đã cập nhật R8 merged methods).
2. [`REPORT_PLAY5_G01.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G01.md): Chuẩn hóa Edge-to-Edge, helper tính toán insets 4 cạnh, chống lặp IME.
3. [`REPORT_PLAY5_G02.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G02.md): Loại bỏ API cửa sổ lỗi thời, theme cleanup, rà soát bytecode dependency.
4. [`REPORT_PLAY5_G03a.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G03a.md): Bỏ khóa hướng nhóm 1 (`MainActivity`, `DocumentManagementActivity`, `OcrResultActivity`).
5. [`REPORT_PLAY5_G03b.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G03b.md): Bỏ khóa hướng nhóm 2 (`CameraScanActivity`, `CropRotateActivity`, `IdCardComposeActivity`).
6. [`REPORT_PLAY5_G03c.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G03c.md): Phân tích delegate Google ML Kit (`SDK_BLOCKED`) và rà soát `PdfViewerActivity`, `PostScanEditorActivity`.
7. [`REPORT_PLAY5_G04.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G04.md): Kiểm chứng và tối ưu tải ảnh mạng Glide 4.16.0 (đính chính footprint lý thuyết vs runtime).
8. [`REPORT_PLAY5_G05.md`](file:///E:/DU%20AN%20AI/T-Scanner/REPORT_PLAY5_G05.md): Kích hoạt Resource Shrinking, đo lường giảm kích thước AAB và loại bỏ tài nguyên (đính chính số đo baseline chuẩn).

---

## 3. Tổng kết số liệu kỹ thuật

- **Tổng số Unit Tests**: **1,062 / 1,062 tests PASS** (100% thành công, 0 lỗi, 0 bỏ qua trên môi trường host dev).
- **Kích thước gói phát hành AAB Release**:
  - Baseline (`baseline_artifacts_20261005/app-release.aab`): `17,420,226 bytes` (~16.61 MB), SHA-256: `5B8FC710A1E8FB4DF22EDC22502DDF7DFC2E17D2901A0F8216B51F0846F6CF3F`
  - Bản release tối ưu G05 (`app/build/outputs/bundle/release/app-release.aab`): `16,576,487 bytes` (~15.81 MB), SHA-256: `9A6CF12172EBC9863275A72F6266C62BF435FD479BF186BA9DE43554C6519C51`
  - **Chênh lệch đo được**: **-843,739 bytes (~4.84%)**
  - **Số tài nguyên lược bỏ**: **2,931** tài nguyên không dùng ghi nhận trong `resources.txt`.
- **Tình trạng các Gate kiểm định**:
  - `HOST_VERIFIED`: Đã đạt cho tất cả các gói code source/build.
  - `DEVICE_PENDING`: Chưa kiểm thử trên thiết bị thật (danh sách `adb devices` rỗng). Cần chạy smoke UI/rotation khi có thiết bị.
  - `PLAY_PENDING`: Cần upload AAB lên Play Console để xác nhận cảnh báo chính thức biến mất.
  - `SDK_BLOCKED`: Khóa portrait của 2 delegate ML Kit phụ thuộc vào bản cập nhật upstream từ Google.
- **Kế hoạch sửa lỗi vòng 2**:
  - Đang tiến hành thực hiện các gói **R01 đến R06** theo kế hoạch `PLAN_PLAY5_ROUND2_GEMINI_2026-10-05.md` để giải quyết triệt để các vấn đề F01 - F05 được chỉ ra trong đợt kiểm tra độc lập.
