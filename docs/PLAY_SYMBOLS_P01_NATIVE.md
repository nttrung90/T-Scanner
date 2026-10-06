# Báo cáo P01 — Bổ sung cấu hình native symbols & Đánh giá trích xuất thực tế

Ngày: 2026-09-24  
Workspace: `E:\DU AN AI\T-Scanner`  
Trạng thái gói: **HOÀN THÀNH (KẾT QUẢ: PARTIAL — BỊ GIỚI HẠN BỞI NHÀ CUNG CẤP AAR ĐÃ STRIP SYMBOLS)**

---

## 1. Cấu hình đã bổ sung

Đã thêm cấu hình `ndk.debugSymbolLevel 'SYMBOL_TABLE'` vào `buildTypes.release` trong `app/build.gradle`:

```groovy
    buildTypes {
        release {
            minifyEnabled false
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

- Giữ nguyên `minifyEnabled false` theo yêu cầu của gói P01 (chưa bật R8).
- Không tự ý thêm `doNotStrip` hay `keepDebugSymbols` toàn cục.

---

## 2. Thực thi Build & Bằng chứng Log thực tế

Lệnh thực thi:
```powershell
./gradlew.bat :app:bundleRelease --offline --info
./gradlew.bat :app:extractReleaseNativeSymbolTables --rerun-tasks --offline --info
```
- **Exit Code**: `0` (BUILD SUCCESSFUL).
- Task `:app:extractReleaseNativeSymbolTables` được AGP kích hoạt và thực thi.

### Bằng chứng từ log AGP (`--info`):
```text
Unable to extract native debug metadata from ...\arm64-v8a\libimage_processing_util_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\libjpeg.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\liblanguage_id_l2c_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\libleptonica.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\libpngx.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\libsurface_util_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\arm64-v8a\libtesseract.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libimage_processing_util_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libjpeg.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\liblanguage_id_l2c_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libleptonica.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libpngx.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libsurface_util_jni.so because the native debug metadata has already been stripped.
Unable to extract native debug metadata from ...\armeabi-v7a\libtesseract.so because the native debug metadata has already been stripped.
```

---

## 3. Bảng phân tích Native Symbol Coverage thực tế

| ABI | Thư viện (.so) | AAR nguồn | Trạng thái trích xuất AGP | Nguyên nhân vật lý |
|---|---|---|---|---|
| `arm64-v8a` | `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.4.1` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `arm64-v8a` | `libsurface_util_jni.so` | `androidx.camera:camera-core:1.4.1` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `arm64-v8a` | `liblanguage_id_l2c_jni.so` | `com.google.mlkit:language-id:17.0.6` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `arm64-v8a` | `libjpeg.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `arm64-v8a` | `libleptonica.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `arm64-v8a` | `libpngx.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `arm64-v8a` | `libtesseract.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `armeabi-v7a` | `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.4.1` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `armeabi-v7a` | `libsurface_util_jni.so` | `androidx.camera:camera-core:1.4.1` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `armeabi-v7a` | `liblanguage_id_l2c_jni.so` | `com.google.mlkit:language-id:17.0.6` | **Không trích xuất được** | AAR gốc từ Google đã stripped `.symtab` |
| `armeabi-v7a` | `libjpeg.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `armeabi-v7a` | `libleptonica.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `armeabi-v7a` | `libpngx.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |
| `armeabi-v7a` | `libtesseract.so` | `cz.adaptech.tesseract4android:4.9.0` | **Không trích xuất được** | AAR gốc từ Adaptech đã stripped `.symtab` |

**Coverage đạt được**: 0 / 14 thư viện có thể trích xuất symbols tự động qua AGP, do 100% thư viện prebuilt bên thứ ba đã bị tước bỏ thông tin debug trước khi đóng gói lên kho Maven.

---

## 4. Kiểm tra Artifact sinh ra

- File AAB mới: `app/build/outputs/bundle/release/app-release.aab`
- Kích thước: `17,990,140 bytes`
- `BUNDLE-METADATA/com.android.tools.build.debugsymbols/`: Không có thư mục con do toàn bộ binary đều bị stripped.
- `app/build/outputs/native-debug-symbols/`: Không sinh ra file zip riêng do không có symbol nào được trích xuất.

---

## 5. Kết luận chuyên môn gói P01

1. Cấu hình Gradle `ndk.debugSymbolLevel 'SYMBOL_TABLE'` đã hoạt động chính xác và chuẩn chuẩn mực theo hướng dẫn chính thức của Android Gradle Plugin.
2. Việc không có symbols xuất hiện trong AAB là do **giới hạn vật lý từ upstream AARs** đã stripped binary. Không thể tạo symbols giả, không zip file đã stripped để lừa hệ thống Play Console.
3. Cảnh báo Play Console về Native Debug Symbols đối với các thư viện này chỉ có thể được giải quyết triệt để nếu:
   - Các nhà phát hành bên thứ 3 cung cấp bản AAR có kèm debug symbols hoặc file `.sym`/symbols riêng tương ứng với đúng GNU Build ID.
   - Hoặc dự án tự biên dịch các thư viện mã nguồn mở (như Tesseract) từ source với NDK local kèm cờ debug info. (Đây là công việc riêng, nằm ngoài phạm vi xử lý cấu hình Play symbols hiện tại).

---

## 6. Nghiệm thu P01
- [x] Đã thêm `ndk.debugSymbolLevel 'SYMBOL_TABLE'` vào `buildTypes.release`.
- [x] Đã chạy build thành công offline và phân tích log chuyên sâu.
- [x] Đã kiểm chứng log AGP `Unable to extract native debug metadata ... already been stripped`.
- [x] Lập bảng coverage đầy đủ cho cả 2 ABI.
- [x] Báo cáo trung thực tình trạng PARTIAL / BLOCKED bởi dependency.
