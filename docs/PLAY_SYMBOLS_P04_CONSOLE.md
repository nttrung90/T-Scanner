# Báo cáo P04 — Đánh giá, xác minh Play Console & Hướng dẫn phát hành

Ngày: 2026-09-24  
Workspace: `E:\DU AN AI\T-Scanner`  
Trạng thái gói: **HOÀN THÀNH — ĐÃ XÁC THỰC BẢN CHẤT KỸ THUẬT 2 CẢNH BÁO, CUNG CẤP LỘ TRÌNH VÀ BẰNG CHỨNG XỬ LÝ PLAY CONSOLE**

---

## 1. Kết luận kỹ thuật đối với 2 Cảnh báo Play Console

Dựa trên toàn bộ kết quả kiểm chứng nhị phân thực tế từ các gói P00, P01, P02 và P03:

### 1.1. Cảnh báo "Thiếu file giải mã Java/Kotlin" (Deobfuscation Mapping)

| Phạm vi | Kết quả xác thực | Căn cứ kỹ thuật |
|---|---|---|
| **Bản versionCode 16 đã upload** | **NOT_APPLICABLE (Không áp dụng)** | Bản 16 được build với `minifyEnabled false`. Ứng dụng không chạy R8 nên mã nguồn không bị xáo trộn; không có mapping nào bị "mất" hay cần khôi phục. Nghiêm cấm tạo file mapping giả hoặc dùng mapping của bản build khác để upload hồi tố. |
| **Bản build mới (versionCode 17+)** | **RESOLVED (Đã giải quyết hoàn toàn)** | Cấu hình release đã bật `minifyEnabled true`. AGP và R8 đã biên dịch thành công, tự động trích xuất và nhúng `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` (48.8 MB) vào thẳng file AAB. Khi upload AAB mới lên Play Console, Console sẽ **tự động nhận diện mapping 100%**, không cần thao tác upload file thủ công. |

---

### 1.2. Cảnh báo "Thiếu Native Debug Symbols" (Mã gỡ lỗi gốc)

| Phạm vi | Kết quả xác thực | Căn cứ kỹ thuật |
|---|---|---|
| **Bản versionCode 16 đã upload** | **CANNOT_RETROFIT (Không thể sửa hồi tố)** | Không thể trích xuất symbols cho binary cũ vì binary đó không còn file symbols gốc khớp GNU Build ID. |
| **Bản build mới (versionCode 17+)** | **PARTIAL / BLOCKED THEO NHÀ CUNG CẤP** | Đã cấu hình chuẩn `ndk { debugSymbolLevel 'SYMBOL_TABLE' }`. Tuy nhiên, toàn bộ 14/14 file `.so` (2 ABI) thuộc 3 thư viện bên thứ 3 (`androidx.camera:camera-core`, `com.google.mlkit:language-id`, `cz.adaptech.tesseract4android`) **đều đã bị chính Google và Adaptech stripped sạch `.symtab` và DWARF** trước khi xuất bản lên Maven. AGP thông báo chính xác: `native debug metadata has already been stripped`. Do đó, AGP không thể trích xuất symbols cho các thư viện này. |

> **Lưu ý quan trọng**: Cảnh báo Native Debug Symbols trên Play Console là cảnh báo hỗ trợ chẩn đoán (diagnostic warning), **hoàn toàn KHÔNG chặn duyệt hoặc từ chối phát hành app** (không vi phạm Google Play Policy). Khi các thư viện native là prebuilt của bên thứ ba đã stripped, việc Play Console ghi nhận cảnh báo này là điều bình thường trong hệ sinh thái Android.

---

## 2. Quy trình chuẩn bị phát hành bản mới (Khi có yêu cầu upload)

Vì kế hoạch P04 quy định *"thao tác tài khoản/upload chỉ khi chủ dự án yêu cầu thực hiện"*, dưới đây là quy trình thực hiện khi tiến hành phát hành:

1. **Cập nhật versionCode**:
   - Trong `app/build.gradle`: tăng `versionCode` lên giá trị lớn hơn 16 và chưa từng sử dụng trên bất kỳ track nào của Play Console (ví dụ `17`).
   - Cập nhật `versionName` (ví dụ `0.9.10` hoặc `1.0.0`).

2. **Biên dịch và ký số phát hành (Release Signing)**:
   - Chạy lệnh build:
     ```powershell
     ./gradlew.bat :app:bundleRelease
     ```
   - Ký AAB với keystore phát hành chính thức của T-Scanner (nếu cấu hình signingConfig trong Gradle hoặc ký ngoài qua `jarsigner`).

3. **Kiểm tra trên Play Console**:
   - Truy cập Google Play Console → Chọn ứng dụng **T-Scanner**.
   - Vào mục **App bundle explorer** (Trình khám phá gói ứng dụng) → Chọn bản phát hành mới.
   - Chuyển sang tab **Downloads** (Tệp tải xuống) / **Assets** (Tài sản):
     - Xác nhận mục **Deobfuscation file (ReTrace)** hiển thị trạng thái đã nhận diện tự động từ bundle.
     - Cảnh báo về native symbols của các thư viện bên thứ 3 (nếu có hiển thị) có thể bỏ qua một cách an toàn mà không ảnh hưởng tới tiến độ xuất bản.

---

## 3. Nghiệm thu P04 & Kết thúc kế hoạch
- [x] Đã đánh giá và kết luận rõ ràng cho từng cảnh báo với bằng chứng kỹ thuật xác thực.
- [x] Đã phân định minh bạch giữa bản cũ (version 16) và bản phát hành mới (version 17+).
- [x] Cung cấp tài liệu và hướng dẫn đầy đủ cho chủ dự án.
- [x] Không tự ý upload hay promote production khi chưa có yêu cầu riêng.
