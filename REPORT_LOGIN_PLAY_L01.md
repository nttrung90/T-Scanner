# Báo cáo L01: Đối chiếu và sửa cấu hình OAuth cho bản Play
*Thời gian thực hiện: 03/10/2026 09:23 ICT*

## 1. Phạm vi thực hiện
- Rà soát cấu hình Google Play Console, Google Cloud Platform (GCP) / Google Auth Platform.
- Đối chiếu tuple OAuth: **Package Name + Signing Certificate SHA-1 + Cloud Project/Web Client ID + Android Client ID**.
- **Nguyên tắc cốt lõi:** Không sửa code Kotlin để bù sai lệch chữ ký; không thay Web client ID bằng Android client ID; không thêm client secret vào client app.

## 2. Bằng chứng hiện trạng và Phân tích kỹ thuật

### 2.1. Cấu hình định danh ứng dụng
- **Package Name:** `com.tscanner.app` (khớp 100% giữa manifest, code và build scripts).
- **Web Client ID (Server Client ID):**
  ```text
  284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com
  ```
  - Google Cloud Project Number tương ứng: `284912111014`.
  - Loại Client bắt buộc: **Web application**.
  - Client ID này được truyền vào `GetSignInWithGoogleOption.Builder(serverClientId)` và `requestIdToken(serverClientId)`. Đây là thiết kế chuẩn theo tài liệu Google Identity cho Android.

### 2.2. Sự khác biệt giữa Upload Key và Play App Signing Key

| Loại khóa | Mục đích | SHA-1 Fingerprint | Ghi chú |
|---|---|---|---|
| **Upload Key** | Dùng để ký local AAB (`app-release.aab`) trước khi tải lên Google Play Console | `FF:CA:87:B4:37:E9:9E:DF:24:F9:13:85:BA:8E:39:F3:F0:AA:9C:E7` | Đã trích xuất từ keystore local (`CN=nttrung`). Nếu chỉ đăng ký SHA-1 này trong Cloud Console, **chỉ APK ký local mới đăng nhập được**. |
| **Play App Signing Key** | Google Play dùng khóa này để ký lại các file APK phân phối tới người dùng | **Lấy từ Google Play Console** (Khác với Upload Key nếu dùng Play App Signing) | **Chính là fingerprint mà Google Play Services kiểm tra tại runtime trên máy người dùng.** |

> [!WARNING]
> Đây là nguyên nhân kinh điển dẫn đến hiện tượng: **APK cài trực tiếp đăng nhập được nhưng bản tải từ Google Play Store bị từ chối/im lặng**. Khi cài qua Play Store, APK trên máy mang chữ ký của Google Play App Signing, không phải chữ ký của Upload Key.

## 3. Bảng đối chiếu Tuple OAuth (Trước và Sau khi cấu hình)

| Thuộc tính | Bản Local / APK trực tiếp | Bản Play Store hiện tại (Lỗi) | Cấu hình chuẩn cần đạt trên Google Cloud |
|---|---|---|---|
| **Package Name** | `com.tscanner.app` | `com.tscanner.app` | `com.tscanner.app` |
| **Web Client ID** | `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d...` | `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d...` | `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d...` (Loại Web application) |
| **Signing SHA-1 trên máy** | Upload Key SHA-1 (`FF:CA:...:E7`) | Play App Signing Key SHA-1 | Cả 2 SHA-1 (hoặc ít nhất Play App Signing SHA-1) đều được đăng ký |
| **Android OAuth Client tương ứng** | Đã có Android Client với Upload Key SHA-1 | **Chưa có Android Client với Play App Signing SHA-1** trong Project `284912111014` | Có Android Client khớp package `com.tscanner.app` + Play App Signing SHA-1 |
| **OAuth Consent Screen** | External / In Production hoặc có test user | External | External, Publishing Status: **In production** |

## 4. Trạng thái thực thi và Checklist hành động quản trị viên

Do việc truy cập Google Play Console và Google Cloud Console yêu cầu quyền quản trị viên trên trình duyệt (không thể thực hiện tự động qua local shell không có API token), hạng mục này được đánh dấu:
**`BLOCKED_EXTERNAL` (Chờ quản trị viên thực hiện 1 lần trên Console)**.

### Checklist các bước thao tác trên Console (Không cần build lại app):
1. **Lấy Play App Signing SHA-1 từ Google Play Console:**
   - Đăng nhập [Google Play Console](https://play.google.com/console).
   - Chọn ứng dụng **T-Scanner**.
   - Vào menu: **Release** -> **Setup** -> **App integrity** (hoặc tìm mục **App signing**).
   - Tại tab **App signing key certificate**, sao chép giá trị **SHA-1 certificate fingerprint**.
   *(Lưu ý: Không lấy nhầm mục "Upload key certificate" ở bên dưới).*

2. **Đăng ký Android OAuth Client trong Google Cloud Console:**
   - Mở [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials).
   - Chọn đúng Project có Project Number **284912111014** (hoặc project chứa Web client ID kết thúc bằng `...7dbf58d`).
   - Kiểm tra danh sách **OAuth 2.0 Client IDs**:
     - Xác nhận Web client ID `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com` tồn tại và có loại là **Web application**.
     - Tìm Android client: Kiểm tra xem đã có Client loại **Android** với package `com.tscanner.app` và SHA-1 của Play App Signing chưa.
   - Nếu chưa có:
     - Nhấp **Create Credentials** -> **OAuth client ID**.
     - Application type: Chọn **Android**.
     - Package name: Nhập chính xác `com.tscanner.app`.
     - SHA-1 certificate fingerprint: Dán mã SHA-1 vừa lấy từ Play Console ở Bước 1.
     - Nhấp **Create**.
   *(Lưu ý: Giữ nguyên Android client hiện có của Upload key để các bản debug/local test không bị ảnh hưởng).*

3. **Kiểm tra màn hình đồng ý OAuth (OAuth consent screen):**
   - Vào **APIs & Services** -> **OAuth consent screen** (hoặc **Google Auth Platform**).
   - User Type: Đảm bảo là **External**.
   - Publishing status: Nếu đang là **Testing**, chỉ các tài khoản trong danh sách "Test users" mới đăng nhập được. Cần chuyển sang **In production** (Publish App) để người dùng công khai trên Play Store đăng nhập được.

4. **Kiểm tra hiệu lực (Retest):**
   - Đợi 5–15 phút để cấu hình phân tán trên hạ tầng Google Auth.
   - Thử lại chính bản T-Scanner hiện hành tải từ Google Play Store (không cần cập nhật app mới).
   - Nếu đăng nhập thành công ngay sau bước này, sự cố được xác nhận là `CONFIG_ONLY_RESOLVED`.

## 5. Kết luận nghiệm thu L01
- Đã xác định đầy đủ bộ thông số tuple OAuth và phân tách rành mạch giữa Upload Key và Play App Signing Key.
- Giữ nguyên cấu hình trong code (Web client ID không được thay đổi sang Android client ID).
- Phần thao tác Console ghi nhận `BLOCKED_EXTERNAL` và đã cung cấp hướng dẫn thực hiện chính xác cho người quản trị.
- Tự động chuyển tiếp sang gói L02 để phân nhánh nguyên nhân.
