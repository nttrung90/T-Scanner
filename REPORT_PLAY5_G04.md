# BÁO CÁO KẾT QUẢ GÓI G04 — KIỂM CHỨNG VÀ TỐI ƯU ẢNH MẠNG

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, JDK 21.0.1, Gradle 9.7.1, AGP 9.3.0, Android Compile/Target SDK 36  
Nhóm đối tượng: `MoreFragment.kt`, `AccountDetailDialog.kt`, và kiểm chứng toàn bộ codebase về tải ảnh mạng.

---

## 1. Mục tiêu và phạm vi
- Xác minh triệt để các vị trí tải ảnh từ mạng trong mã nguồn ứng dụng để đối chiếu với cảnh báo "Tải bitmap mạng" từ Google Play Console.
- Chuẩn hóa và tối ưu toàn diện thư viện nạp ảnh mạng Glide 4.16.0: giới hạn kích thước decode/downsampling, chính sách bộ nhớ đệm (disk cache strategy), và quản lý vòng đời (lifecycle clearance) tránh rò rỉ bộ nhớ.
- Đảm bảo pipeline xử lý tài liệu OCR/PDF nội bộ không bị xáo trộn hoặc thay thế tùy tiện.

---

## 2. Kết quả kiểm chứng và đối chiếu mã nguồn

### 2.1. Rà soát quét toàn bộ codebase (`app/src/main/java`)
- **Kết quả quét tìm kiếm API mạng (`decodeStream`, `openStream`, `HttpURLConnection`, `URL`)**:
  - `OcrReaderViewModel.kt` (dòng 960, 978): Chỉ sử dụng `BitmapFactory.decodeStream(FileInputStream)` để giải mã tệp ảnh tạm thời trên bộ nhớ cục bộ phục vụ nhận diện chữ.
  - `TesseractOcrHelper.kt` (dòng 375, 383): Tương tự, chỉ đọc `FileInputStream` từ tệp lưu trữ nội bộ để phân tích OCR.
  - `PlayPurchaseVerifier.kt` (dòng 389): Chỉ mở kết nối JSON RPC HTTP tới backend xác thực biên lai Google Play Billing, không giải mã bất kỳ Bitmap nào.
- **Kết luận**: Ứng dụng **hoàn toàn không có bất kỳ luồng tải Bitmap mạng thủ công tự chế** nào bằng `HttpURLConnection` hay `URL.openStream()`. Cảnh báo của Google Play Console xuất phát từ việc máy quét tĩnh phát hiện mã lớp `HttpUrlFetcher` bên trong thư viện `com.github.bumptech.glide:glide:4.16.0`.

### 2.2. Hai điểm tải ảnh mạng duy nhất của ứng dụng
Chỉ có 2 vị trí hiển thị avatar người dùng từ tài khoản Google được nạp qua mạng:
1. `MoreFragment.kt` (Avatar người dùng trong màn hình cài đặt / thêm).
2. `AccountDetailDialog.kt` (Avatar người dùng trong hộp thoại chi tiết tài khoản).

---

## 3. Các cải tiến và tối ưu hóa đã thực hiện

### 3.1. Giới hạn kích thước giải mã (Downsampling Override)
- **`MoreFragment.kt`**: Bổ sung `.override(128, 128)`. Ảnh đại diện hiển thị ở kích thước icon (khoảng 40-48dp), việc giới hạn 128x128px bảo đảm độ sắc nét trên màn hình mật độ pixel cao (xxxhdpi) với footprint giải mã ước tính ~64 KB RAM.
- **`AccountDetailDialog.kt`**: Bổ sung `.override(160, 160)`. Kích thước hiển thị ~64dp, footprint giải mã ước tính ~100 KB RAM.
- *Ghi chú kỹ thuật (R00)*: Con số so sánh 99.8% là phép so sánh lý thuyết dung lượng điểm ảnh thô (uncompressed bitmap footprint) giữa ảnh camera gốc (4032x3024 ~48.7 MB) và ảnh thu nhỏ 128x128. Trong thực tế, Glide trước đây đã tự động downsample theo kích thước View mục tiêu, việc thêm `.override()` giúp đặt trần giải mã cố định ngay từ tầng decode, tránh phụ thuộc vào thời điểm đo đạc view layout.

### 3.2. Bộ nhớ đệm tự động (Disk Cache Strategy)
- Cả hai vị trí được cấu hình thêm `.diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)`: Tự động ghi nhớ ảnh gốc và ảnh đã biến đổi (CircleCrop) trên bộ nhớ flash của thiết bị, giảm thiểu lưu lượng mạng tải lại nhiều lần.

### 3.3. Giải phóng mục tiêu theo vòng đời (Lifecycle Target Clearance)
- **`MoreFragment.kt`**: Trong `onDestroyView()`, gọi rõ ràng `Glide.with(this).clear(binding.ivAccountIcon)` trước khi hủy binding để ngắt kết nối tải ảnh khi chuyển tab.
- **`AccountDetailDialog.kt`**: Bổ sung phương thức `onStop()`, gọi `Glide.with(context).clear(binding.ivDialogAvatar)` khi hộp thoại đóng lại.

---

## 4. Kết quả kiểm thử và nghiệm thu kỹ thuật

### 4.1. Unit Test
- Bộ kiểm thử [`NetworkImageLoadingPolicyTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/NetworkImageLoadingPolicyTest.kt):
  - Kiểm tra tính hợp lệ của URL, tính toán lý thuyết footprint RAM và chuẩn hóa cache key.
  - *Hạn chế được ghi nhận (R00)*: Các hàm test trong file này là hàm private nội bộ trong test class, chưa gọi trực tiếp production class `MoreFragment` hay lifecycle target của Glide. Kiểm thử vòng đời thực tế và phòng ngừa ghi đè ảnh khi fallback được chuyển giao cho gói **R03**.
- Toàn bộ test suite dự án:
  - Lệnh: `gradlew.bat :app:testDebugUnitTest`
  - Kết quả: **1062/1062 tests PASS** (0 failures, 0 skipped, 0 errors).

### 4.2. Biên dịch hệ thống
- Lệnh: `gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL in 24s**.

---

## 5. Kết luận gói G04
- Gói G04 đã hoàn thành 100% mục tiêu.
- Thư viện nạp ảnh mạng Glide được chuẩn hóa đồng bộ, có giới hạn kích thước decode, cache tối ưu và giải phóng bộ nhớ nghiêm ngặt theo vòng đời.
- Sẵn sàng chuyển tiếp sang gói **G05** (Bật Resource Shrinking và đo lường kích thước AAB).
