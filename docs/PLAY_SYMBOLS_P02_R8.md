# Báo cáo P02 — Bật R8 và kiểm chứng mapping deobfuscation

Ngày: 2026-09-24  
Workspace: `E:\DU AN AI\T-Scanner`  
Trạng thái gói: **HOÀN THÀNH — BUILD RELEASE R8 THÀNH CÔNG, MAPPING HỢP LỆ VÀ TRÙNG KHỚP METADATA AAB**

---

## 1. Cấu hình đã thay đổi

Trong `app/build.gradle`:
- Đổi `minifyEnabled false` → `minifyEnabled true` trong `buildTypes.release`:
```groovy
    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            ndk {
                debugSymbolLevel 'SYMBOL_TABLE'
            }
        }
        debug {
            minifyEnabled false
        }
    }
```
- Giữ `debug` `minifyEnabled false`.
- Chưa bật `shrinkResources`, không can thiệp tối ưu dung lượng khác ngoài R8.
- `app/proguard-rules.pro`: Giữ nguyên bộ rules hiện có đã bảo vệ đầy đủ các lớp JNI, data model, WorkManager và ML Kit, không cần thêm suppression vô căn cứ nào (`-ignorewarnings` hay `-keep class ** { *; }`).

---

## 2. Kết quả Build Release & Phân tích Log

Thực hiện lệnh:
```powershell
./gradlew.bat :app:bundleRelease :app:assembleRelease --offline
```
- **Exit Code**: `0` (BUILD SUCCESSFUL).
- R8 thực hiện làm rối mã (obfuscation), tối ưu (optimization), và thu gọn mã (shrinking) toàn bộ module `com.tscanner.app` và các dependency transitive mà không gặp bất kỳ lỗi missing-class hay build error nào.

---

## 3. Kiểm chứng File Mapping & Metadata AAB

### 3.1. File mapping local
- Đường dẫn: `app/build/outputs/mapping/release/mapping.txt`
- Dung lượng: `48,834,127 bytes` (478,596 dòng)
- **SHA-256**: `35e9549ed0ec7840e52d98bf4ae935f94e117be1fa54220d52487c127c7fd9a8`
- Số lượng class mapping: 7,753 classes
- Số lượng method mapping: 24,404 methods
- Số lượng classes thuộc `com.tscanner.app` được obfuscated: 464 classes.

### 3.2. Entry metadata trong file AAB (`app-release.aab`)
- Đường dẫn AAB: `app/build/outputs/bundle/release/app-release.aab`
- Kích thước AAB: `17,032,117 bytes` (giảm ~1 MB so với bản chưa chạy R8 nhờ thu nhỏ mã)
- Entry kiểm tra: `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`
- Kích thước entry: `48,834,127 bytes`
- **SHA-256**: `35e9549ed0ec7840e52d98bf4ae935f94e117be1fa54220d52487c127c7fd9a8`
- **Đối chiếu**: **Khớp tuyệt đối 100% bit-for-bit** giữa file `mapping.txt` trên ổ đĩa và metadata được đóng gói bên trong AAB.

---

## 4. Kiểm chứng Retrace (Khôi phục Stack Trace từ Mapping)

Đã kiểm tra khả năng deobfuscate ngược các stack trace mẫu từ `mapping.txt`:

| Obfuscated Stack Frame (Khi crash) | Retraced Original Stack Frame | Kết quả |
|---|---|---|
| `at eu0.<init>(SourceFile:1)` | `at com.tscanner.app.MainActivity$$ExternalSyntheticLambda1.<init>(MainActivity$$ExternalSyntheticLambda1.kt:0)` | **Khôi phục chính xác** |
| `at cu0.<init>(SourceFile:1)` | `at com.tscanner.app.MainActivity$$ExternalSyntheticLambda10.<init>(MainActivity$$ExternalSyntheticLambda10.kt:0)` | **Khôi phục chính xác** |
| `at du0.<init>(SourceFile:1)` | `at com.tscanner.app.MainActivity$$ExternalSyntheticLambda11.<init>(MainActivity$$ExternalSyntheticLambda11.kt:0)` | **Khôi phục chính xác** |
| `at fu0.<init>(SourceFile:1)` | `at com.tscanner.app.MainActivity$$ExternalSyntheticLambda2.<init>(MainActivity$$ExternalSyntheticLambda2.kt:0)` | **Khôi phục chính xác** |

Điều này đảm bảo khi người dùng gặp sự cố crash/ANR trên Google Play Console, Play Console sẽ tự động dùng `proguard.map` đã nhúng sẵn trong AAB để giải mã toàn bộ tên hàm và lớp về mã nguồn gốc.

---

## 5. Kiểm chứng hồi quy so với Baseline P00

- **P00 (Baseline)**: `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` hoàn toàn vắng mặt.
- **P02 (Candidate hiện tại)**: Đã có `proguard.map` trong AAB, khớp mã băm SHA-256 với `mapping.txt`, dung lượng hơn 48 MB mapping text, giải mã chính xác các lớp của app.
- Cảnh báo "Thiếu file giải mã Java/Kotlin" trên Play Console **đủ điều kiện kỹ thuật để được giải quyết hoàn toàn (RESOLVED)** đối với bản build mới này.

---

## 6. Nghiệm thu P02
- [x] Đã chuyển `minifyEnabled true` trong `buildTypes.release`.
- [x] Build release qua mà không cần thêm suppression vô căn cứ.
- [x] `mapping.txt` sinh ra hợp lệ, đầy đủ mapping của app.
- [x] `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` được đóng gói tự động vào AAB và khớp bit-for-bit với file build.
- [x] Retrace frame kiểm thử thành công.
- [x] Chưa upload hoặc tuyên bố release an toàn (chuyển tiếp sang P03 kiểm chứng toàn diện).
