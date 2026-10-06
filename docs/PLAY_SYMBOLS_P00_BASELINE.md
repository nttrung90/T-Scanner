# Báo cáo P00 — Chốt baseline và nguồn từng thư viện native/mapping

Ngày: 2026-09-24  
Workspace: `E:\DU AN AI\T-Scanner`  
Trạng thái gói: **HOÀN THÀNH (P00 — CHỈ ĐỌC / KHÔNG SỬA CODE & GRADLE)**

---

## 1. Trạng thái Git & Working Tree

### 1.1. Git status
Working tree đang giữ nguyên toàn bộ các thay đổi từ các đợt hoàn thiện trước đó (OCR Reader/Editor, dọn dẹp Paddle/ONNX, VIP backup, localization):
- Modified tracked files: `app/build.gradle`, `app/proguard-rules.pro`, các file strings và tests liên quan.
- Không thực hiện `git checkout`, `git reset`, hay `git clean`.

### 1.2. Diff của cấu hình build (`app/build.gradle` & `app/proguard-rules.pro`)
- `app/build.gradle`:
  - `versionCode 16`
  - `versionName "0.9.9"`
  - `ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }`
  - `buildTypes.release`:
    - `minifyEnabled false`
    - `proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'`
    - Chưa cấu hình `ndk.debugSymbolLevel` (hoặc bất kỳ khối debug symbols nào).
- `app/proguard-rules.pro`:
  - Đã loại bỏ các rule cũ của ONNX/Paddle.
  - Vẫn giữ consumer rules cho ML Kit, Tesseract4Android, v.v.

---

## 2. Định danh và kiểm chứng Artifact AAB gốc

### 2.1. Thông tin file và Hash AAB gốc
- Đường dẫn artifact: `app/release/app-release.aab`
- Kích thước: `18,056,112 bytes` (17.22 MB)
- SHA-256: `6C81C9CFC28693B4F70EF0F79FD5C64460B31757651C34E9481C5A36A223D1F4`
- Bản lưu trữ bảo toàn bằng chứng: `app/build/baseline_play_symbols/app-release-v16-original.aab` (SHA-256 khớp tuyệt đối).

### 2.2. Trích xuất thông tin Manifest từ chính binary AAB
Đã đọc và giải mã protobuf `base/manifest/AndroidManifest.xml` trực tiếp từ bên trong file AAB (không chỉ dựa vào source code):
- **Package**: `com.tscanner.app`
- **versionCode**: `16`
- **versionName**: `0.9.9`
- **compileSdkVersion**: `36`
- **minSdkVersion**: `26`
- **targetSdkVersion**: `36`

> **Lưu ý đối chiếu Play Console**: Artifact local này mang `versionCode 16`, khớp với hash được ghi nhận tại thời điểm build release. Tuy nhiên, việc artifact này có khớp bit-for-bit với file đã upload lên Google Play Console trước đó hay không chỉ có thể được kiểm chứng trên Console tại gói P04.

---

## 3. Kiểm kê ZIP entries của AAB (Metadata & Native)

### 3.1. Phân mục `BUNDLE-METADATA/`
| Tên Entry | Kích thước giải nén | Kích thước nén | Nhận xét |
|---|---|---|---|
| `BUNDLE-METADATA/com.android.tools.build.gradle/app-metadata.properties` | 56 B | 52 B | Metadata AGP |
| `BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb` | 13,459 B | 8,026 B | Danh sách dependency dạng protobuf |
| `BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof` | 3,413 B | 3,418 B | Baseline profile |
| `BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm` | 243 B | 248 B | Baseline profile metadata |
| `BUNDLE-METADATA/com.android.tools/d8.json` | 328 B | 227 B | D8 compiler metadata |

- **Mapping / Obfuscation (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`)**: **HOÀN TOÀN KHÔNG CÓ**.  
  *Nguyên nhân*: Do `minifyEnabled false`, R8 không chạy làm rối mã nên không sinh file mapping.
- **Native Debug Symbols (`BUNDLE-METADATA/com.android.tools.build.debugsymbols/`)**: **HOÀN TOÀN KHÔNG CÓ**.  
  *Nguyên nhân*: Do `app/build.gradle` chưa khai báo `ndk.debugSymbolLevel`.

---

## 4. Nguồn gốc từng thư viện Native từ Resolved Dependencies

Đã chạy kiểm tra dependency chính thức:
```powershell
./gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline
```
Kết quả: Exit code 0, build thành công offline.

Từ classpath đã resolve và kiểm tra trực tiếp các file `.aar` trong cache Gradle (`~/.gradle/caches/modules-2/files-2.1/`), 7 thư viện `.so` (trên 2 ABI: `arm64-v8a` và `armeabi-v7a`, tổng cộng 14 file `.so`) xuất phát từ đúng 3 dependency AAR sau:

1. **`androidx.camera:camera-core:1.4.1`** (`camera-core-1.4.1.aar`):
   - `libimage_processing_util_jni.so`
   - `libsurface_util_jni.so`
2. **`com.google.mlkit:language-id:17.0.6`** (`language-id-17.0.6.aar`):
   - `liblanguage_id_l2c_jni.so`
3. **`cz.adaptech.tesseract4android:tesseract4android:4.9.0`** (`tesseract4android-4.9.0.aar`):
   - `libjpeg.so`
   - `libleptonica.so`
   - `libpngx.so`
   - `libtesseract.so`

Không có thư viện native nào do project tự biên dịch bằng C/C++ (không có `externalNativeBuild` hay `CMakeLists.txt`/`Android.mk` trong module app).

---

## 5. Bảng phân tích chi tiết ELF Sections, Symbols & Build ID

### 5.1. Công cụ phân tích
- `llvm-readelf` của NDK không có sẵn trong thư mục Android SDK (`C:\Users\nguye\AppData\Local\Android\Sdk\ndk` không tồn tại).
- Đã sử dụng bộ parser ELF nhị phân độc lập phân tích trực tiếp ELF Header (32-bit & 64-bit), Section Headers (`.shstrtab`), Note descriptor (`.note.gnu.build-id`), Dynamic Symbol Table (`.dynsym`), Static Symbol Table (`.symtab`), và DWARF sections (`.debug_*`).

### 5.2. Bảng tổng hợp đối chiếu ABI/.so → Dependency → Trạng thái Symbols & Hash

Tất cả 14 file `.so` trong AAB gốc đều **khớp 100% từng byte (kích thước, SHA-256, Build ID)** với binary đóng gói sẵn bên trong các file AAR chính thức từ Maven:

| ABI | Tên thư viện (.so) | AAR cung cấp & Version | Kích thước (bytes) | SHA-256 (rút gọn) | GNU Build ID | .dynsym | .symtab (Static) | DWARF (.debug_*) |
|---|---|---|---|---|---|---|---|---|
| **arm64-v8a** | `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.4.1` | 29,008 | `3e0071afcd...` | `e667d7f16c83b70a2cec5d8de25bd854a1a2ba53` | Có (23) | **Không (0)** | **Không** |
| **arm64-v8a** | `libsurface_util_jni.so` | `androidx.camera:camera-core:1.4.1` | 4,832 | `ee3f0d56f8...` | `964a1cbe387c160f0a6286713d31d752e35c6801` | Có (10) | **Không (0)** | **Không** |
| **arm64-v8a** | `liblanguage_id_l2c_jni.so` | `com.google.mlkit:language-id:17.0.6` | 1,019,560 | `4479b044bb...` | `4e1cbc7b2a12b63992ad6bcaaa139a2b` | Có (126) | **Không (0)** | **Không** |
| **arm64-v8a** | `libjpeg.so` | `cz.adaptech.tesseract4android:4.9.0` | 251,888 | `0e751fcd26...` | `b5bb3de680c71c6581ebbdff824d2c3bc9ce7e8a` | Có (191) | **Không (0)** | **Không** |
| **arm64-v8a** | `libleptonica.so` | `cz.adaptech.tesseract4android:4.9.0` | 2,878,240 | `00436d8562...` | `c4410d83eade134fa06fab22eda1fef6ba6944b0` | Có (2,970) | **Không (0)** | **Không** |
| **arm64-v8a** | `libpngx.so` | `cz.adaptech.tesseract4android:4.9.0` | 219,880 | `e70d48e405...` | `221b8739ec3dae6456e0142750ca554a3fa32e20` | Có (422) | **Không (0)** | **Không** |
| **arm64-v8a** | `libtesseract.so` | `cz.adaptech.tesseract4android:4.9.0` | 4,164,360 | `bce8dcca15...` | `b931663aba7c68ff4f1677e990c753cda52734eb` | Có (6,690) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.4.1` | 20,380 | `e48e356bbe...` | `12e91d3874791a1d92920ec94bc2690acebe48aa` | Có (30) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libsurface_util_jni.so` | `androidx.camera:camera-core:1.4.1` | 3,440 | `f110d6a305...` | `9c4bf428125edd1dc6796d75abaa3aede32de4b5` | Có (11) | **Không (0)** | **Không** |
| **armeabi-v7a** | `liblanguage_id_l2c_jni.so` | `com.google.mlkit:language-id:17.0.6` | 609,636 | `1c1a8baa63...` | `d250e2344ceb27911dda26240956ad8d` | Có (136) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libjpeg.so` | `cz.adaptech.tesseract4android:4.9.0` | 187,096 | `2b2c8a048f...` | `0c866f9bc3540f2168665bb27f50391319bb4024` | Có (192) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libleptonica.so` | `cz.adaptech.tesseract4android:4.9.0` | 2,160,740 | `309d92453c...` | `26d6e721a9f50122a95f3067b234ef447df7a664` | Có (2,972) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libpngx.so` | `cz.adaptech.tesseract4android:4.9.0` | 162,104 | `f92d0ba22b...` | `dfb7ced177c559b84cf38fac69f8caf51ac6d51a` | Có (424) | **Không (0)** | **Không** |
| **armeabi-v7a** | `libtesseract.so` | `cz.adaptech.tesseract4android:4.9.0` | 3,004,948 | `b1c543bcbc...` | `f7006d6bdf01a91e2d0c91501d5a7b8ad097c7de` | Có (6,695) | **Không (0)** | **Không** |

### 5.3. Chi tiết SHA-256 đầy đủ của từng file `.so` trong AAB và AAR
- `base/lib/arm64-v8a/libimage_processing_util_jni.so`: `3e0071afcdc7e321f839e196d266014182f18d0988e4455acc9857a085b2184e`
- `base/lib/arm64-v8a/libjpeg.so`: `0e751fcd26f2a60e8b87c2da274473c6c2a26352e5a9071033f1f88a93b39308`
- `base/lib/arm64-v8a/liblanguage_id_l2c_jni.so`: `4479b044bbdad9a938fea15698dfa3473e73bd6bab9f8e0468fc3cc6069c137e`
- `base/lib/arm64-v8a/libleptonica.so`: `00436d8562bc833a22111a1451cf46147f05aecf8a257751e127689a1ee6e3c4`
- `base/lib/arm64-v8a/libpngx.so`: `e70d48e405b56f1df06921c4ebbee0612140a8f2c7b0a2641fbbf6fea53eda4c`
- `base/lib/arm64-v8a/libsurface_util_jni.so`: `ee3f0d56f81cc0a301db32dc4fd51274c3216733b4c40172768e38bc5049a265`
- `base/lib/arm64-v8a/libtesseract.so`: `bce8dcca1544d9f8708ac126c1e920bfa92481509672d8472470e8c2eebcd43c`
- `base/lib/armeabi-v7a/libimage_processing_util_jni.so`: `e48e356bbe1902ae9a66178a4a0141c993b82ee9f70dde52ad83e4af3eca984f`
- `base/lib/armeabi-v7a/libjpeg.so`: `2b2c8a048f8a10bbaab41e90cbd2db3eececde70899e3f3a2a3d36208ccd5f76`
- `base/lib/armeabi-v7a/liblanguage_id_l2c_jni.so`: `1c1a8baa63ecc473e611902a8aa010fa526f2a35594c302836f7279d1c73aa17`
- `base/lib/armeabi-v7a/libleptonica.so`: `309d92453c04479def3f54e4a319271766ad11cc5a0995b34e80764bd505680e`
- `base/lib/armeabi-v7a/libpngx.so`: `f92d0ba22b90600b3efce1568e1ce7d7e960927cec4afe500fb5514e1bf323c3`
- `base/lib/armeabi-v7a/libsurface_util_jni.so`: `f110d6a305fef5be5664d58cfb054366b002bc516066b4ec880c38a68428756f`
- `base/lib/armeabi-v7a/libtesseract.so`: `b1c543bcbc128cb89bdbe2eaca4d89d34c448195aad464c92fc6103ff1e4e0ab`

---

## 6. Kết luận chuyên môn từ Baseline P00

1. **Về cảnh báo thiếu file giải mã Java/Kotlin**:
   - Hoàn toàn chính xác với cấu hình: `buildTypes.release` có `minifyEnabled false`.
   - AGP/R8 không sinh ra file mapping (`mapping.txt` hay `proguard.map`). Việc thiếu file mapping trong bản build này là hệ quả tự nhiên và bình thường khi không làm rối mã.
   - Không có file mapping nào bị "mất" hay cần khôi phục cho binary này, vì chưa từng được sinh ra.

2. **Về cảnh báo thiếu Native Debug Symbols**:
   - `app/build.gradle` hoàn toàn chưa bật `ndk.debugSymbolLevel`. Do đó, AGP không chạy task trích xuất symbols vào `BUNDLE-METADATA/com.android.tools.build.debugsymbols/`.
   - **Thực tế quan trọng về binary**: Toàn bộ 14 file `.so` (từ Google CameraX, Google ML Kit, và Tesseract4Android) **đã bị nhà phát hành (upstream vendor) stripped sạch `.symtab` và DWARF `.debug_*`** trước khi phát hành lên Maven. Chúng chỉ còn giữ lại `.dynsym` (bảng dynamic symbols chứa các hàm JNI export).
   - Khi chuyển sang **P01** và bật `debugSymbolLevel 'SYMBOL_TABLE'`, AGP sẽ cố gắng trích xuất symbols từ các file `.so` này. Nếu AGP yêu cầu `.symtab` hoặc ghi log `already been stripped`, đây là giới hạn vật lý từ phía nhà cung cấp thư viện, cấu hình build không thể tự chế tạo ra symbols đã bị xóa từ nguồn.

---

## 7. Nghiệm thu gói P00 & Dừng

- [x] Đã ghi nhận git status, diff `app/build.gradle` và `app/proguard-rules.pro`.
- [x] Đã xác định hash SHA-256 (`6C81C9CFC28693B4F70EF0F79FD5C64460B31757651C34E9481C5A36A223D1F4`), kích thước (18,056,112 bytes), và bảo toàn artifact tại `app/build/baseline_play_symbols/`.
- [x] Đã trích xuất thông tin manifest bên trong AAB gốc: `com.tscanner.app`, `versionCode 16`, `versionName 0.9.9`.
- [x] Đã kiểm kê toàn bộ ZIP entries: xác nhận thiếu `proguard.map` và `debugsymbols`.
- [x] Đã chạy `dependencies --configuration releaseRuntimeClasspath --offline` và chỉ ra chính xác 3 AAR gốc của 7 thư viện.
- [x] Đã phân tích ELF sections/Build ID của tất cả 14 `.so`: phân biệt rõ `.dynsym` (có), `.symtab` (không), DWARF (không).
- [x] Đã ghi nhận rõ: chưa xác minh trực tiếp artifact trên Play Console (chờ P04).
- [x] **Dừng lại tại P00, không sửa Gradle, không làm P01.**
