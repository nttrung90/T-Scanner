# Báo cáo L04: Sửa code/release chỉ theo lỗi đã tái hiện
*Thời gian thực hiện: 03/10/2026 09:30 ICT*

## 1. Cơ sở và Phụ thuộc
- Kế thừa kết quả từ L02 (Phân nhánh nguyên nhân) và L03 (Chẩn đoán an toàn và chuẩn hóa trạng thái kết thúc).
- Nguyên tắc: Chỉ sửa code trên nhánh có lỗi đã được chứng minh/tái hiện; không sửa tùy đoán, không nới lỏng bảo mật (auth/session/canonical owner), không đổi Web client ID thành Android client ID, không tắt R8 toàn app.

## 2. Đánh giá chi tiết từng nhánh sửa đổi

### 2.1. Nhánh cấu hình Web Client ID trong Binary
- **Trạng thái:** `NOT_NEEDED` (Không sửa trong code).
- **Lý do:** Web client ID `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com` được dùng đồng bộ, nhất quán giữa AndroidX Credential Manager (`serverClientId`) và legacy GoogleSignIn (`requestIdToken`). Việc thay Web client ID bằng Android client ID là sai theo đặc tả Google Identity. Việc sửa đổi chỉ cần thực hiện nếu Cloud Console tạo Web client mới; hiện tại Web client ID này đang gắn liền với Cloud Project và backend.

### 2.2. Nhánh R8 / ProGuard Keep Rules
- **Trạng thái:** `NOT_NEEDED` (Không sửa tùy đoán).
- **Lý do:** 
  - Thư viện `androidx.credentials:credentials:1.3.0` và `com.google.android.libraries.identity.googleid:googleid:1.1.1` đã tự động đóng gói `consumer-rules.pro` bên trong AAR để giữ các class và constructor cần thiết cho Credential Manager.
  - Tại L03, `DefaultGoogleCredentialClient` đã được bổ sung try-catch an toàn quanh `GoogleIdTokenCredential.createFrom` để ghi nhận lỗi có cấu trúc thay vì làm sập luồng.
  - Không có bằng chứng logcat hay stacktrace nào cho thấy R8 làm mất class. Việc thêm blanket keep rule hoặc tắt R8 toàn app bị nghiêm cấm theo hướng dẫn.

### 2.3. Nhánh Lifecycle / Commit / UI Logic
- **Trạng thái:** `COMPLETED_VIA_L03` (Đã hoàn thiện tối thiểu và chuẩn xác trong L03).
- **Lý do:**
  - Lỗi "im lặng" do nuốt `GetCredentialCancellationException` và thiếu log chẩn đoán đã được xử lý triệt để tại L03.
  - Đã giữ nguyên vẹn các chốt chặn an toàn:
    - Attempt token & session generation isolation (chống stale result ghi đè session mới).
    - Single terminal callback dispatch (AtomicBoolean).
    - Cancellation không tự động kích hoạt fallback mở lại chooser.
    - Đăng nhập không bị phụ thuộc vào kết quả Drive sync hay mua VIP.
  - Đã có bộ test regression 42/42 tests PASS chứng minh các chốt chặn hoạt động chính xác.

### 2.4. Nhánh Cấu hình Hạ tầng OAuth (Play App Signing SHA-1)
- **Trạng thái:** `BLOCKED_EXTERNAL` (Chờ người quản trị thực hiện trên Google Cloud Console theo L01).
- **Lý do:** Đây là nguyên nhân gốc rễ gây ra việc Google Play Services từ chối cấp credential trên bản Play Store. Việc giải quyết phụ thuộc vào Console bên ngoài, không thể giải quyết bằng cách sửa mã nguồn Kotlin.

## 3. Kết luận nghiệm thu L04
- Không có lỗi mã nguồn nào khác cần sửa đổi ngoài các cải tiến chẩn đoán và an toàn vòng đời đã thực hiện tại L03.
- Các hạng mục không cần sửa được ghi nhận rõ ràng là `NOT_NEEDED` kèm đầy đủ căn cứ kỹ thuật.
- Tiến hành chuyển sang gói L05 (Kiểm chứng host và chuẩn bị artifact).
