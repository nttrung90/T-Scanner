# BÁO CÁO KẾT QUẢ GÓI R00 — CHUẨN HÓA BẰNG CHỨNG VÀ BASELINE

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, OpenJDK 21.0.1, Gradle 9.7.1, AGP 9.3.0  
Phạm vi: Chuẩn hóa bằng chứng, số liệu đo lường, phân tích bytecode R8 và bảo toàn artifact. Tuyệt đối không thay đổi mã nguồn ứng dụng trong gói này.

---

## 1. Mục tiêu và phạm vi
- Sửa chữa và đính chính các sai lệch về số liệu đo lường kích thước artifact, mã băm SHA-256 và các phép tính lý thuyết chưa được kiểm chứng trong các báo cáo vòng 1.
- Bảo toàn nguyên vẹn bản phát hành AAB và mapping vòng 1 vào thư mục an toàn trước khi thực hiện các gói sửa đổi tiếp theo.
- Phân tích sâu hiện tượng R8 Class Merging / Inlining đối với lớp làm mờ `c5` để tránh kết luận vội vàng về các cảnh báo.
- Thiết lập hệ thống phân loại trạng thái kiểm định rõ ràng theo từng Gate: `SOURCE_DONE`, `HOST_VERIFIED`, `DEVICE_PENDING`, `PLAY_PENDING`, `SDK_BLOCKED`.

---

## 2. Kết quả kiểm chứng và đính chính số liệu Artifact

### 2.1. Đối chiếu kích thước và mã băm SHA-256 thực tế

| Tệp tin | Vị trí lưu trữ | Kích thước (Bytes) | Mã băm SHA-256 chính xác | Ghi chú đính chính |
|---|---|---|---|---|
| **AAB Baseline** | `baseline_artifacts_20261005/app-release.aab` | **17,420,226** (~16.61 MB) | `5B8FC710A1E8FB4DF22EDC22502DDF7DFC2E17D2901A0F8216B51F0846F6CF3F` | Tách biệt hoàn toàn với mã băm của mapping (`313EE3...`). Dung lượng không phải 37.2 MB. |
| **AAB Release Vòng 1** | `app/build/outputs/bundle/release/app-release.aab` | **16,576,487** (~15.81 MB) | `9A6CF12172EBC9863275A72F6266C62BF435FD479BF186BA9DE43554C6519C51` | Bản build vòng 1 sau khi bật `shrinkResources true`. |
| **Chênh lệch thực tế** | So sánh giữa 2 tệp trên | **-843,739 bytes** (~824 KB, **-4.84%**) | — | Không sử dụng con số 55.4% (vốn do nhầm lẫn với bản artifact khác). Hai bản khác thời điểm build nên mức giảm 4.84% không được quy toàn bộ duy nhất cho shrinking. |
| **Tài nguyên bị loại bỏ** | `app/build/outputs/mapping/release/resources.txt` | 571,811 bytes | — | Ghi nhận chính xác **2,931** tài nguyên `is not reachable` bị loại bỏ bởi Resource Shrinker. |

### 2.2. Bảo toàn Artifact vòng 1
Toàn bộ tệp xuất xưởng của vòng 1 đã được sao lưu vào:
- Thư mục: [`baseline_artifacts_20261005/round1_release/`](file:///E:/DU%20AN%20AI/T-Scanner/baseline_artifacts_20261005/round1_release/)
- Tệp bao gồm: `app-release-round1.aab`, `mapping.txt`, `resources.txt`, `configuration.txt`, `seeds.txt`, `usage.txt`.

---

## 3. Phân tích kỹ thuật sâu về R8 Class Merging (`c5`)

- **Hiện tượng**: Trong tệp `mapping.txt`, lớp làm mờ `c5` có tiêu đề là `AppAuthManager$$ExternalSyntheticLambda2 -> c5:`. Tuy nhiên, cơ chế tối ưu hóa của R8 (Class Merging & Inlining) đã tổng hợp và gộp nhiều khối lệnh khác nhau vào cùng lớp này:
  - Header: Synthetic Lambda của `AppAuthManager`.
  - Dòng 21-30: Inlined method `QrScannerHelper.startCameraScan$lambda$2`.
  - Các dòng 457619-457632: Inlined methods `TesseractOcrHelper.calculateInSampleSize` và `access$calculateInSampleSize` đi vào `invokeSuspend`.
- **Kết luận kỹ thuật**: Không thể chỉ nhìn vào tên class header để khẳng định `c5.invokeSuspend` thuần túy là Google Auth flow. R8 đã gộp cả các hàm xử lý tính toán sample size của Bitmap OCR vào chung block obfuscated này. Nhận định trong báo cáo cũ đã được đính chính lại để bảo đảm tính trung thực học thuật và kỹ thuật.

---

## 4. Thiết lập trạng thái các Gate kiểm định

| Gate | Trạng thái hiện tại | Mô tả điều kiện |
|---|---|---|
| **SOURCE_DONE** | **ĐẠT (Vòng 1)** | Mã nguồn đã được sửa theo thiết kế. Các lỗi mới (F01-F05) đang được chuyển giao sang R01-R05. |
| **HOST_VERIFIED** | **ĐẠT (1,062 tests)** | Biên dịch Gradle (`assembleDebug`, `bundleRelease`) và toàn bộ unit test host thành công 100%. |
| **DEVICE_PENDING** | **MỞ (OPEN)** | Chưa có thiết bị thật hoặc máy ảo kết nối ADB (`adb devices` danh sách trống). Tất cả kiểm thử xoay màn hình động, độ mượt UI và IME phải chờ thiết bị. |
| **PLAY_PENDING** | **MỞ (OPEN)** | Chưa upload AAB lên Google Play Console; chưa có kết quả quét mới từ hệ thống của Google Play. |
| **SDK_BLOCKED** | **MỞ (OPEN)** | Thuộc tính `screenOrientation="portrait"` trên 2 delegate của ML Kit là do Google Play Services SDK quy định. Giữ nguyên theo khuyến nghị để bảo đảm an toàn scan intent. |

---

## 5. Kết luận gói R00
- Gói R00 đã hoàn tất: Các báo cáo `G00`, `G03c`, `G04`, `G05` và `INTEGRATION_SUMMARY` đã được rà soát và đính chính toàn diện.
- Sẵn sàng chuyển tiếp sang gói **R01** (Giải quyết F01: Quản lý ownership và commit một lần cho Crop-Save qua Recreation).
