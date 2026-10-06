# Báo cáo L00: Khóa đúng bản lỗi và thu lỗi SDK
*Thời gian thực hiện: 03/10/2026 09:20 ICT*

## 1. Phạm vi thực hiện
- Điều tra, thu thập bằng chứng hiện trạng, đối chiếu artifact và phân tích luồng code runtime.
- Chưa thay đổi logic đăng nhập trong mã nguồn.

## 2. Bằng chứng artifact và phiên bản

| Thành phần | versionCode | versionName | Package | Trạng thái chữ ký / Ghi chú |
|---|---|---|---|---|
| **Source checkout** | 19 | 1.1.0 | `com.tscanner.app` | Khớp `app/build.gradle` (release `minifyEnabled true`) |
| **Local AAB (`app/release/app-release.aab`)** | 19 | 1.1.0 | `com.tscanner.app` | Ký bằng Upload Key (`CN=nttrung`), SHA-1: `FF:CA:87:B4:37:E9:9E:DF:24:F9:13:85:BA:8E:39:F3:F0:AA:9C:E7`, có embedded R8 mapping |
| **Local APK (`app/release/app-release.apk`)** | 8 | 0.2.6 | `com.tscanner.app` | `DOES NOT VERIFY - Missing META-INF/MANIFEST.MF` (File local cũ, không phải bản build release hợp lệ hiện hành) |
| **Local `output-metadata.json`** | 1 | 0.1.0 | `com.tscanner.app` | Metadata cũ không đồng bộ với file APK/AAB |
| **Web client ID trong DEX AAB** | - | - | - | Khớp `284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com` |

> [!IMPORTANT]
> Bản APK local hiện có tại `app/release/app-release.apk` có `versionCode 8` và hỏng chữ ký, không thể là căn cứ đại diện cho APK người dùng đã thử đăng nhập thành công. Không tự suy diễn APK local này chính là bản người dùng thử.

## 3. Tình trạng kết nối thiết bị và Runtime Capture

- Đã chạy kiểm tra ADB thông qua: `C:\Users\nguye\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l`.
- Kết quả: `List of devices attached` **trống** (Không có thiết bị hoặc máy ảo nào đang kết nối).
- **Trạng thái:** `BLOCKED_EXTERNAL` đối với việc kéo trực tiếp base APK từ máy lỗi và logcat runtime trên thiết bị thật.

### Bảng dữ liệu runtime cần thu thập từ thiết bị thực tế khi có máy kết nối:
1. `adb shell dumpsys package com.tscanner.app | grep -E "versionCode|versionName|installerPackageName"`: Xác nhận đúng versionCode phân phối từ Google Play (`com.android.vending`), không phải sideload.
2. `adb shell getprop ro.build.version.release` & `adb shell dumpsys package com.google.android.gms | grep versionName`: Phiên bản Android & Google Play Services trên máy gặp sự cố.
3. `adb shell pm path com.tscanner.app` -> `adb pull <path> base.apk` -> `apksigner verify --print-certs base.apk`: Đọc SHA-1 thực tế của APK cài trên máy người dùng (chứng chỉ Google Play App Signing).
4. `adb logcat -v time -s AppAuthManager GoogleSignInRouter CredentialManager CredentialProvider MoreFragment AndroidRuntime`: Ghi nhận log ngắn đúng thời điểm bấm nút Google Sign-In và chọn Gmail.

## 4. Phân tích Stage và Cơ chế gây "Chọn Gmail xong im lặng"

Quy trình đăng nhập trong mã nguồn chia thành các giai đoạn rõ ràng:
1. **REQUEST Stage:**
   - Người dùng bấm nút trong `MoreFragment` -> gọi `AppAuthManager.signInWithGoogle(activity, ...)`.
   - `GoogleCredentialRequestFactory.createExplicitSignInRequest(webClientId)` tạo request chuẩn chỉ chứa `GetSignInWithGoogleOption`.
2. **PROVIDER Stage:**
   - Gọi `credentialClient.getCredential(activity, request)`.
   - Google Play Services mở giao diện Credential Manager bottom sheet để người dùng chọn tài khoản Google.
   - Khi người dùng chọn tài khoản, Google Play Services thực hiện kiểm tra bảo mật OAuth client (đối chiếu `package name` + `SHA-1 certificate` của ứng dụng gọi với danh sách Android OAuth Client trong Google Cloud Project sở hữu `serverClientId`).
   - **Nếu SHA-1 của Play App Signing chưa được đăng ký trong Cloud Console:** Google Play Services từ chối cấp token/ủy quyền và đóng bottom sheet hoặc trả về ngoại lệ phía client.
   - AndroidX Credential Manager khi bottom sheet bị hủy/đóng bởi framework hoặc người dùng sẽ ném ra `GetCredentialCancellationException`.
3. **EXCEPTION & DISPATCH Stage:**
   - Trong `AppAuthManager.kt` (dòng 895–905):
     ```kotlin
     } catch (e: GetCredentialCancellationException) {
         Log.d(TAG, "User cancelled Google Sign-In via Credential Manager")
         synchronized(authStateLock) {
             clearActiveAttemptIfMatchingLocked(attemptToken)
         }
         withContext(mainDispatcher) {
             if (!activity.isFinishing && !activity.isDestroyed) {
                 onCancelled()
             }
         }
         return@launch
     }
     ```
   - Nhánh `catch` này:
     - Gán nhãn cứng `Log.d` là "User cancelled".
     - Không ghi nhận `e.type` hoặc `e.message` (dù đã được sanitize).
     - Không kích hoạt fallback sang Intent legacy GoogleSignIn vì phân loại đây là cancellation.
     - Gọi `onCancelled()`.
4. **UI Stage:**
   - Trong `MoreFragment.kt` (dòng 344–347):
     ```kotlin
     onCancelled = {
         pendingSignInAttempt = null
         vipContinuationHandler.onSignInCancelled()
     }
     ```
   - Callback `onCancelled` không hiển thị thông báo lỗi, không toast, không dialog (vì coi là người dùng chủ động bấm Back/hủy).
   - **Kết quả người dùng nhận thấy:** Sau khi chạm chọn Gmail, bottom sheet biến mất và ứng dụng quay về màn hình cũ trong im lặng hoàn toàn, không có phản hồi hay thông báo lỗi.

## 5. Kết luận nghiệm thu L00
- Đã xác định rõ cấu trúc artifact và chứng minh bản local APK hỏng/cũ không thể làm căn cứ so sánh.
- Đã chỉ ra chính xác đường code giải thích toàn bộ hiện tượng im lặng tại runtime.
- Thu thập logcat/cert từ máy vật lý tạm thời `BLOCKED_EXTERNAL` do không có thiết bị kết nối ADB; sẵn sàng chuyển tiếp sang gói L01 đối chiếu cấu hình OAuth.
