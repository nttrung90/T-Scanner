# Báo cáo V00: Baseline và khoanh cấu hình Google Play

- Ngày: 25/09/2026
- Repository: `E:\DU AN AI\T-Scanner`
- Module: `:app`
- Gói thực hiện: V00

---

## 1. Trạng thái mã nguồn và Baseline Git Checkout

### 1.1. Hiện trạng checkout (`git status --short`)
Khu vực xác thực và sao lưu đám mây có các file đang được sửa sẵn, cần bảo toàn nguyên vẹn:
- `M app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
- `M app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
- `M app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
- `M app/src/main/java/com/tscanner/app/utils/CloudBackupManager.kt`

### 1.2. Tóm tắt nội dung thay đổi có sẵn trong phạm vi auth
- **`AppAuthManager.kt`**: Đã bổ sung giải quyết Canonical ID ưu tiên `sub` từ ID Token JWT (`resolveCanonicalGoogleId`, `extractSubFromIdToken`), di chuyển định danh cũ (`migrateLegacyIdentity`), quản lý thế hệ phiên (`sessionGeneration`) để hủy job sao lưu rò rỉ khi đổi tài khoản.
- **`MoreFragment.kt`**: Đã tách riêng `driveAuthorizationLauncher` và `googleSignInLauncher` (tuy nhiên `googleSignInLauncher` vẫn đang chặn chỉ nhận `RESULT_OK`), cập nhật chuỗi đa ngôn ngữ và badge trạng thái tài khoản/VIP.
- **`VipUpgradeDialog.kt`**: Cập nhật văn bản thoại gia hạn VIP, các callback giao diện.
- **`CloudBackupManager.kt`**: Bổ sung bộ chống trùng lặp tải tài liệu (`single-flight coalescing`), kiểm tra `sessionGeneration` và xác thực quyền sở hữu `ownerId`.

### 1.3. Cấu hình phiên bản trong mã nguồn (`app/build.gradle`)
- `applicationId`: `com.tscanner.app`
- `compileSdk`: 36, `targetSdk`: 36, `minSdk`: 26
- `versionCode`: 16
- `versionName`: `"0.9.9"`
> *Lưu ý*: Phiên bản cài đặt thực tế trên máy người dùng gặp lỗi chưa được xác định từ log thiết bị, không tự động suy đoán là `0.9.9` (versionCode 16).

---

## 2. Kiểm tra thiết bị runtime (`adb devices`)

- Lệnh thực thi: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices`
- Kết quả:
```text
List of devices attached
(trống - không có thiết bị kết nối)
```
- Kết luận: Không có thiết bị Android vật lý hoặc máy ảo nào đang kết nối.
- Dữ liệu runtime (bước thất bại, status code chính xác, logcat auth) hiện ở trạng thái **PENDING (chưa thu thập được trực tiếp)**.

---

## 3. Ma trận đối chiếu định danh và cấu hình OAuth

| Hạng mục | Giá trị ghi nhận từ mã nguồn | Giá trị kỳ vọng trên Google Play / GCP | Trạng thái đối chiếu | Ghi chú kỹ thuật |
|---|---|---|---|---|
| **Package Name** | `com.tscanner.app` | `com.tscanner.app` | **CONFIRMED (Repo)** | Cấu hình tại `app/build.gradle:10` |
| **Installed Version** | Chưa có log máy | Bản phát hành trên Play Store | **PENDING** | Chờ log thiết bị hoặc phản hồi từ người dùng |
| **Web OAuth Client ID** | `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com` | Web Application Client ID trong project `t-scanner-508413` | **CONFIRMED (Repo)** / **PENDING (GCP)** | Khai báo tại `AppAuthManager.kt:42`. Cần quyền GCP để xác thực trạng thái kích hoạt |
| **GCP Project** | `t-scanner-508413` | Dự án sở hữu OAuth Client | **CONFIRMED (Ghi chú mã)** / **PENDING (GCP)** | Chú thích tại `AppAuthManager.kt:40` |
| **Android OAuth Client (SHA-1)** | Chưa xác định từ repo | Play App Signing Certificate SHA-1 (Play Console -> App integrity) | **PENDING** | Không dùng debug/upload keystore SHA-1 để đối chiếu cho bản cài Play Store |
| **OAuth Consent Screen** | Bắt buộc cho Google Sign-In | Chế độ Testing (cần Test Users) hoặc In Production | **PENDING** | Nếu ở trạng thái Testing và tài khoản người dùng không thuộc Test Users, đăng nhập sẽ bị từ chối |
| **Google Drive API** | Yêu cầu scope `drive.file` | Google Drive API enabled trong project `t-scanner-508413` | **PENDING** | Cần bật trong GCP Console |
| **Runtime Status Code** | Không rõ (bị gate RESULT_OK nuốt chửng tại `MoreFragment.kt:45`) | Cần mã lỗi (ví dụ 10: DEVELOPER_ERROR, 12500, 12501) | **PENDING** | Được giải quyết phần tiếp nhận ở gói V01 |

---

## 4. Phân tích nguyên nhân và khoanh vùng kỹ thuật

1. **Lỗi mã nguồn chắc chắn tồn tại (L01):**
   Tại `MoreFragment.kt:44–58`, `googleSignInLauncher` chỉ kiểm tra `result.resultCode == Activity.RESULT_OK`. Khi người dùng cài bản Play và đăng nhập thất bại (kết quả trả về `RESULT_CANCELED` nhưng Intent mang `ApiException` với status code, ví dụ code 10 do SHA-1 hoặc code 12500), ứng dụng hoàn toàn không xử lý `result.data`, không hiển thị lỗi và không kích hoạt bất kỳ luồng hỗ trợ nào.
2. **Không kết luận vội vã về cấu hình SHA-1:**
   Mặc dù mã lỗi 10 thường gặp liên quan đến chứng thư SHA-1 hoặc test users, việc tự ý đổi Client ID hoặc kết luận SHA-1 sai khi chưa có logcat/Console là vi phạm nguyên tắc an toàn.
3. **Mục tiêu tiếp theo:**
   Thiếu thiết bị/Console không chặn việc khắc phục các lỗi logic tiếp nhận kết quả (V01) và điều phối Credential Manager (V02). Khi V01 hoàn thành, mọi mã lỗi từ Google Play Services sẽ được bóc tách và hiển thị minh bạch cho người dùng/logcat.

---

## 5. Bàn giao cho gói V01

- **Trạng thái phụ thuộc của V01**: Đã sẵn sàng triển khai (V00 đã hoàn thành lập hồ sơ baseline).
- **Phạm vi cho phép của V01**:
  - `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
  - `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt` (chỉ phần chuyển kết quả và thông báo lỗi)
  - Seam production `GoogleSignInResultRouter.kt` (nếu cần tách để kiểm thử unit test chuẩn)
  - `app/src/test/java/com/tscanner/app/GoogleSignInResultRouterTest.kt`
  - Resource strings liên quan đến thông báo lỗi đăng nhập trung tính.
- **Ranh giới cấm trong V01**:
  - Không sửa Web Client ID, không đổi scope `drive.file`.
  - Không sửa cơ chế VIP, không đụng vào luồng Demo hay Cloud Backup.
  - Không thay đổi dependency Gradle.
