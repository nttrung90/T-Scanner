# Kế hoạch xử lý 2 cảnh báo Play Console — giao mô hình nhỏ

Ngày: 2026-09-24. Workspace: `E:\DU AN AI\T-Scanner`.
Trạng thái: CHỈ LẬP KẾ HOẠCH; chưa sửa cấu hình/mã ứng dụng, chưa upload.

## 1. Kết luận có bằng chứng

Ảnh báo thiếu file giải mã Java/Kotlin và thiếu native debug symbols cho versionCode 16. Đây là cảnh báo hỗ trợ chẩn đoán crash/ANR; riêng ảnh này không chứng minh ứng dụng bị crash hoặc bị từ chối phát hành.

Đã kiểm tra checkout và ZIP AAB, không suy diễn từ báo cáo cũ:

| Bằng chứng | Kết luận |
|---|---|
| `app/build.gradle:13`: versionCode 16, versionName 0.9.9 | Trùng số phiên bản trong ảnh, chưa chứng minh là cùng binary đã upload |
| `app/build.gradle:24-26`: release `minifyEnabled false`, có khai báo proguardFiles | Chưa chạy R8 làm rối mã; khai báo file rules không tự bật R8. Không có mapping là phù hợp cấu hình |
| `app/build.gradle`: chưa đặt `ndk.debugSymbolLevel` | Cần cấu hình và kiểm tra output thực tế để biết symbols có tạo được không |
| `build.gradle:2`: AGP 9.3.0; `settings.gradle` chỉ include `:app` | Chỉ sửa module app chính, không sửa các project mẫu trong android/ |
| `app/release/app-release.aab`: không có metadata mapping/native symbols | Đã xác nhận thiếu trên artifact local; chưa đối chiếu artifact Play |
| AAB có 7 tên .so × 2 ABI arm64-v8a/armeabi-v7a | Native vẫn tồn tại dù đã bỏ Paddle/ONNX |

Tên thư viện thực tế: `libimage_processing_util_jni.so`, `libsurface_util_jni.so`, `liblanguage_id_l2c_jni.so`, `libtesseract.so`, `libleptonica.so`, `libjpeg.so`, `libpngx.so`.

SHA-256 AAB đã đọc: `6C81C9CFC28693B4F70EF0F79FD5C64460B31757651C34E9481C5A36A223D1F4`.

Chưa xác nhận AAR cung cấp từng .so hoặc ELF symbol sections. Không khẳng định mọi thư viện đã stripped. Chưa chạy build/test mới vì lượt này chỉ kiểm tra và lập kế hoạch; unit test debug không trả lời việc AAB có symbols hay R8 release chạy an toàn.

## 2. Quyết định phạm vi

- Ưu tiên P00 → P01 → P02 → P03 → P04. Chỉ giao một gói mỗi lượt, có báo cáo rồi dừng.
- P02 bật R8 là nhánh tùy chọn, có thay đổi hành vi build. Nếu chỉ muốn xử lý native symbols với rủi ro thấp, bỏ P02 và chấp nhận cảnh báo mapping khi release không làm rối mã. Không bật R8 chỉ để đổi màu cảnh báo mà bỏ kiểm thử release.
- Với version 16 đã upload: chỉ bổ sung mapping/symbols thực sự khớp binary đó. Không dùng mapping mới build, không tạo file rỗng/giả. Nếu trước đó không dùng R8 thì không có mapping R8 cần khôi phục.
- Cấu hình mới áp dụng cho bản build mới. Bản upload tiếp theo dùng versionCode chưa từng dùng trên Play, lớn hơn bản đã phát hành; không mặc định 17 nếu chưa kiểm tra các track.
- Working tree hiện có nhiều thay đổi, gồm build.gradle, proguard-rules.pro và AAB. Không reset/clean/checkout đè, không ghi đè AAB gốc. Không đổi dependency, ABI, SDK, ngôn ngữ, logic OCR/Drive hoặc signing để giải quyết cảnh báo này.

## P00 — Chốt baseline và nguồn từng thư viện

**Phạm vi ghi:** `docs/PLAY_SYMBOLS_P00_BASELINE.md` mới; bản lưu artifact trong thư mục bằng chứng riêng nếu cần. Chỉ đọc source/cấu hình.

1. Ghi git status, diff của build.gradle/proguard-rules, hash/kích thước AAB gốc. Đọc version trong manifest AAB bằng bundletool đã có hoặc công cụ tương đương; không dùng mỗi version trong source để nhận diện artifact.
2. Liệt kê ZIP entries của AAB: `.so`, metadata mapping, native symbols; giữ kết quả làm baseline.
3. Chạy `./gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --offline`; tìm AAR thực tế chứa từng .so từ dependency đã resolve. Không đoán nhà cung cấp chỉ theo tên thư viện.
4. Nếu có NDK llvm-readelf, đọc symbol sections/Build ID của thư viện từ AAR trên bản trích xuất riêng. Phân biệt `.dynsym` với `.symtab` và DWARF; ghi rõ công cụ thiếu nếu chưa kiểm tra được.

**Nghiệm thu:** bảng ABI/.so → dependency/version → tình trạng symbols, hash baseline, trạng thái mapping rõ ràng. Ghi riêng chưa xác minh Play version 16.
**Kiểm chứng hồi quy:** baseline phải tái hiện metadata thiếu trên AAB gốc, không thay AAB bằng một build mới rồi gọi đó là bản 16 đã phát hành.
**Dừng:** giao báo cáo P00, không sửa Gradle.

## P01 — Bổ sung cấu hình native symbols

**Phụ thuộc:** P00. **Phạm vi ghi:** `app/build.gradle` trong release và `docs/PLAY_SYMBOLS_P01_NATIVE.md`; output build mới.

Thêm vào buildTypes.release (Groovy):

```groovy
ndk {
    debugSymbolLevel 'SYMBOL_TABLE'
}
```

Giữ minifyEnabled như cũ trong gói này. SYMBOL_TABLE là bước đầu để có tên hàm; chỉ dùng FULL khi có nhu cầu dòng/file và dependency có debug info tương ứng.

Chạy `./gradlew.bat :app:bundleRelease --offline --info`, lưu log riêng. Không chạy clean làm mất artifact cũ. Nếu cache thiếu, ghi đúng lỗi môi trường; không tự nâng AGP/NDK/dependency.

Kiểm tra AAB mới ở `app/build/outputs/bundle/release/`, metadata `BUNDLE-METADATA/com.android.tools.build.debugsymbols/` và từng ABI/.so; kiểm tra `app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip` nếu task sinh file này. Không yêu cầu ZIP riêng để kết luận AAB thiếu symbols nếu metadata đã có.

**Kiểm chứng hồi quy:** so coverage artifact mới với P00, không chỉ tìm chuỗi debugSymbolLevel trong Gradle. Tìm log `Unable to extract native debug metadata ... already been stripped`; không coi build SUCCESS là đủ.

**Nghiệm thu:** mỗi .so có symbols hợp lệ hoặc có bằng chứng thiếu từ nhà cung cấp; log và bảng coverage đủ 2 ABI. Symbols phải khớp thư viện build này (đối chiếu Build ID khi có và nguồn build).

**Nếu bị stripped từ AAR:** cấu hình không thể khôi phục dữ liệu đã mất. Ghi dependency/version/ABI cần symbols từ nhà cung cấp đúng binary. Không đổi tên .so thành .sym, không zip .so đã stripped rồi tuyên bố hoàn tất, không thêm doNotStrip/keepDebugSymbols cho toàn bộ thư viện. Đề xuất nâng/thay/rebuild dependency thành công việc riêng nếu thật sự cần; chưa thực hiện trong gói này.
**Dừng:** báo COMPLETE hoặc PARTIAL với thư viện thiếu cụ thể; không hứa cảnh báo sẽ hết trước kiểm tra Play.

## P02 — Bật R8 và kiểm chứng mapping (tùy chọn)

**Điều kiện:** chủ dự án chọn nhánh release có R8. **Phạm vi ghi:** `app/build.gradle`, `app/proguard-rules.pro`, `docs/PLAY_SYMBOLS_P02_R8.md`.

1. Đổi release `minifyEnabled true`. Giữ debug false, chưa bật shrinkResources; không trộn tối ưu dung lượng khác.
2. Giữ rules hiện có làm baseline. Kiểm tra consumer rules của dependency, JNI Tesseract, reflection, WorkManager và serialization thực dùng. Không thêm `-keep class ** { *; }`, `-ignorewarnings` hoặc dontwarn toàn cục để che lỗi build. Không xóa hàng loạt rules trong gói này.
3. Build `./gradlew.bat :app:bundleRelease :app:assembleRelease --offline`. Xử lý từng missing-class theo nguồn thực, không sao chép mù mọi generated missing_rules.
4. Kiểm tra `app/build/outputs/mapping/release/mapping.txt` có mapping thật và AAB có `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`; so nội dung/hash mapping trong AAB với file cùng build.
5. Dùng Retrace và một frame thực sự có trong mapping để kiểm tra khôi phục tên. Đây là kiểm tra mapping, chưa thay thế kiểm tra crash/runtime.

**Kiểm chứng hồi quy:** AAB P00 không có mapping, candidate R8 phải có và khớp build; mapping sai/thiếu là không đạt. P03 bắt buộc trước phát hành vì debug tests không kiểm tra tác động của R8.
**Nghiệm thu:** build release qua, mapping hợp lệ, không có suppression mới thiếu lý do. Nếu không giải quyết được lỗi trong phạm vi rules, ghi blocker và dừng; không tự sửa logic ứng dụng.
**Dừng:** bàn giao P02; chưa upload hoặc tuyên bố release an toàn.

## P03 — Kiểm tra candidate và lưu bộ artifact cùng build

**Phạm vi ghi:** `docs/PLAY_SYMBOLS_P03_VALIDATION.md`, thư mục lưu candidate riêng; không sửa source. Nhánh không R8 ghi mapping = NOT_APPLICABLE, không báo thiếu là build lỗi.

Chạy một lần sau khi cấu hình đã ổn định:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
./gradlew.bat :app:bundleRelease :app:assembleRelease --offline
```

Lưu exit code và kết quả thực; lỗi Gradle cache/quyền ghi phải phân biệt với lỗi code. Sau bất kỳ sửa cấu hình nào làm đổi candidate, kiểm tra lại artifact tương ứng.

Trên thiết bị, cài bản release ký hợp lệ hoặc APK set sinh từ chính candidate AAB bằng bundletool, kiểm tra:

- Khởi động; camera/chụp/crop; PDF nhập/xuất/xem.
- Tesseract tiếng Việt; ML Kit Latin/Trung; nhận diện ngôn ngữ.
- Mở tài liệu OCR cũ, sửa/lưu/mở lại, xuất DOCX/XLSX.
- Đăng nhập và Drive với tài khoản thử; WorkManager backup thực chạy.
- Cả arm64-v8a và armeabi-v7a nếu tiếp tục phát hành cả hai. Thiếu thiết bị phải ghi NOT_RUN.

So với baseline cùng điều kiện để phân biệt lỗi có sẵn với lỗi mới do R8. Không đọc/in mật khẩu keystore; signing và certificate cần phù hợp luồng thử nghiệm. Không gỡ app làm mất dữ liệu để vượt qua lỗi chữ ký. Nếu thiếu signing/device: báo chưa nghiệm thu runtime.

**Nghiệm thu:** host checks và smoke release có bằng chứng; không có regression mới. Lưu AAB, mapping nếu có, native symbols nếu có, SHA-256, versionCode, variant, AGP, trạng thái source dirty/diff để truy nguồn chính xác cùng build. Không commit keystore hay secrets.
**Dừng:** bộ candidate có thể review; chỉ P04 mới xác minh cảnh báo phía Play.

## P04 — Xác minh Play Console

**Phụ thuộc:** P03; thao tác tài khoản/upload chỉ khi chủ dự án yêu cầu thực hiện. Kế hoạch hiện tại không phải lệnh upload/phát hành.
**Phạm vi:** `docs/PLAY_SYMBOLS_P04_CONSOLE.md` và thao tác bản thử nghiệm được cho phép.

- Nhánh sửa dữ liệu version 16: chỉ upload file đã chứng minh khớp artifact gốc. Nếu không có symbols đúng binary thì ghi không thể sửa hồi tố bằng build mới.
- Nhánh bản mới: xác định versionCode khả dụng; đổi version trong bước chuẩn bị bản phát hành được chấp thuận, build lại và lặp kiểm tra P03 cho chính artifact cuối. Không lấy candidate cũ có versionCode 16 để upload lại như bản mới.
- Trong App bundle explorer chọn đúng version, Downloads/Assets để xem mapping/native symbols. Với AAB có metadata, kiểm tra Play đã nhận tự động; upload thủ công chỉ khi cần và có file đúng.
- Lưu ảnh trạng thái sau xử lý: mapping đã nhận hoặc không áp dụng vì chưa bật R8; native symbols coverage và cảnh báo còn lại. Không dùng trạng thái version khác làm bằng chứng.

**Nghiệm thu:** từng cảnh báo có kết quả xác thực: RESOLVED, NOT_APPLICABLE với lý do, hoặc PARTIAL/BLOCKED theo nhà cung cấp. Không tuyên bố hết cả hai nếu còn cảnh báo native hoặc chưa vào Console.
**Dừng:** báo kết quả; không tự promote production.

## 3. Prompt giao việc từng lượt

Thay `<Pxx>` bằng đúng MỘT mã gói, bắt đầu P00:

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_PLAY_DEBUG_SYMBOLS_SMALL_MODEL_2026-09-24.md.
Thực hiện duy nhất gói <Pxx>, trong đúng phạm vi và điều kiện ghi ở gói đó.
Đọc báo cáo gói trước, kiểm tra lại checkout hiện tại, giữ mọi thay đổi có sẵn.
Không làm gói kế tiếp, không nâng dependency, không sửa logic OCR/Drive,
không upload/phát hành khi chưa được yêu cầu riêng.
Kiểm chứng trên artifact thực, không chỉ kiểm tra text cấu hình.
Nếu thư viện đã stripped hoặc thiếu công cụ/device/signing, ghi rõ phần chưa đạt;
không tạo symbols giả hoặc dùng mapping khác build.
Báo cáo: file thay đổi, diff thuộc gói, lệnh/exit code, bằng chứng artifact,
kết quả test/runtime, phần chưa kiểm tra, tiêu chí đạt/chưa đạt. Sau đó dừng.
```

## 4. Nguồn chính thức đã đối chiếu

- [Android Developers — Include native symbols](https://developer.android.com/build/include-native-symbols): mức symbols, metadata AAB và giới hạn dependency đã stripped.
- [Google Play — Deobfuscate or symbolicate crash stack traces](https://support.google.com/googleplay/android-developer/answer/9848633?hl=en): mapping/symbols theo đúng phiên bản, tự lấy metadata AAB và vị trí upload thủ công.

Hai trang đang ghi giới hạn kích thước symbols khác nhau; kế hoạch không dựa vào con số đó. Nếu phát sinh file lớn, đối chiếu giới hạn Console thực tế tại thời điểm upload.
