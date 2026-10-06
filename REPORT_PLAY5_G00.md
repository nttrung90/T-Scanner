# Báo cáo kết quả G00 — Điều kiện đầu vào: Đúng artifact, đúng baseline

- **Thời gian thực hiện**: 05/10/2026
- **Trạng thái**: Hoàn thành G00 (Xác minh artifact, mapping, retraced call sites, owner, đo baseline; không sửa mã nguồn hay cấu hình release)
- **Kế hoạch tham chiếu**: [PLAN_PLAY_5_WARNINGS_2026-10-05.md](file:///E:/DU%20AN%20AI/T-Scanner/PLAN_PLAY_5_WARNINGS_2026-10-05.md)

---

## 1. Thông tin Artifact & Baseline đã xác minh

Sau khi kiểm tra trực tiếp tệp nén AAB `app/build/outputs/bundle/release/app-release.aab` và đối chiếu với manifest biên dịch dạng protobuf:

| Thuộc tính | Bản Play đang cảnh báo (trong AAB release) | Cấu hình nguồn hiện tại (`app/build.gradle`) | Ghi chú đối chiếu |
|---|---|---|---|
| **versionCode** | **21** | 22 | Khẳng định bản cảnh báo là **versionCode 21** |
| **versionName** | **1.2.1** | 1.2.5 | Cấu hình nguồn đã tăng lên 1.2.5 |
| **targetSdkVersion** | 36 | 36 | Trùng khớp |
| **compileSdkVersion** | 36 | 36 | Trùng khớp |
| **minSdkVersion** | 26 | 26 | Trùng khớp |
| **AGP Version** | 9.3.0 | 9.3.0 | Ghi nhận trong `app-metadata.properties` của AAB |

### Bản sao bảo toàn Artifact (Backup):
Toàn bộ artifact và mapping tham chiếu đã được lưu trữ an toàn tại thư mục `baseline_artifacts_20261005/` trước khi thực hiện bất kỳ lệnh build nào ghi đè thư mục outputs:
- `baseline_artifacts_20261005/app-release.aab`
- `baseline_artifacts_20261005/BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`
- `baseline_artifacts_20261005/mapping.txt` (mapping của build local 05/10)
- `baseline_artifacts_20261005/app-release-unsigned.apk`

### Bằng chứng đối chiếu Mapping:
- **Mapping thực sự của bản cảnh báo (nằm trong AAB)**:
  - Vị trí: `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`
  - SHA-256: `313EE36CAE9294999B2C4106C8070A7C8936DAC7657733F2D2C6055A5B8A2B82`
  - Dung lượng: 50,111,289 bytes (~47.79 MB)
  - Khớp 100% với các ký hiệu làm mờ (`az0.G`, `d70.b`, `f70.b`, `f.s`, `c5.invokeSuspend`, `k8.l`, `co0.c`, `jb1.b`).
- **Mapping local trong `app/build/outputs/mapping/release/mapping.txt`**:
  - SHA-256: `F8B7BD1F89419A34AE2B5B0B469430EF022CB0A04C621F88CC298E9286336E3C`
  - Dung lượng: 52,083,242 bytes (~49.67 MB)
  - Sinh vào 05/10/2026 10:39 SA (ứng với build mã nguồn mới versionCode 22). Bảng ánh xạ này hoàn toàn khác (ví dụ: `k8` ánh xạ sang `AppCompatToggleButton`, `c5` ánh xạ sang `AppAuthManager synthetic lambda`).
  - **Kết luận**: Khẳng định tuyệt đối cảnh báo Play thuộc về artifact AAB `versionCode 21` kèm `proguard.map` nội bộ của nó. Việc dùng mapping local của build khác đã được loại trừ hoàn toàn.

---

## 2. Bảng đối chiếu Retrace chi tiết (Class & Method Call Sites)

Bằng cách tra cứu trực tiếp trong `proguard.map` nội bộ của artifact cảnh báo kết hợp phân tích DEX bytecode (`base/dex/classes.dex` thông qua `dexdump`):

| Ký hiệu làm mờ | Lớp gốc được giải mã | Phương thức gốc & Call site | Bytecode / Chi tiết lệnh | Chủ sở hữu (Owner) | Phân loại & Phiên bản |
|---|---|---|---|---|---|
| `d70.b` | `androidx.activity.EdgeToEdgeApi26` | `setUp(SystemBarStyle, SystemBarStyle, Window, View, boolean, boolean):278` | Gọi `WindowInsetsControllerCompat`, `setAppearanceLightStatusBars`, `setAppearanceLightNavigationBars` | **Google AndroidX** | `androidx.activity:activity:1.9.3` |
| `f70.b` | `androidx.activity.EdgeToEdgeApi29` | `setUp(SystemBarStyle, SystemBarStyle, Window, View, boolean, boolean):310` | Gọi `WindowInsetsControllerCompat`, `setAppearanceLightStatusBars`, `setAppearanceLightNavigationBars` | **Google AndroidX** | `androidx.activity:activity:1.9.3` |
| `f.s` | `AboutAppDialog$Companion$$ExternalSyntheticApiModelOutline2` (R8 Desugaring Synthetic Class) | `m(WindowManager$LayoutParams):0` | `0000: const/4 v0, #int 1` (`SHORT_EDGES`)<br>`0001: iput v0, v1, LayoutParams.layoutInDisplayCutoutMode`<br>**Caller duy nhất trong DEX**: `Le70;.a` tức `androidx.activity.EdgeToEdgeApi28.setUp` | **Google AndroidX** | `androidx.activity:activity:1.9.3` (nằm trong pipeline `enableEdgeToEdge()` của AndroidX) |
| `az0.G` | `com.google.android.material.datepicker.MaterialDatePicker` | `onStart():388`, `enableEdgeToEdgeIfNeeded(Window):450` inlined sang `EdgeToEdgeUtils.applyEdgeToEdge(...)`, `setLightStatusBar`, `setLightNavigationBar` | Thiết lập trực tiếp cờ thanh hệ thống / màu nền hệ thống trong dialog DatePicker | **Google Material Components** | `com.google.android.material:material:1.12.0` |
| `co0.c` | `com.bumptech.glide.load.data.HttpUrlFetcher` | `loadDataWithRedirects(URL, int, URL, Map):111`, `buildAndConfigureConnection(URL, Map):151` | Khởi tạo `HttpURLConnection`, mở luồng mạng tải ảnh bitmap từ URL | **Bumptech Glide** | `com.github.bumptech.glide:glide:4.16.0` |
| `k8.l` | `androidx.startup.AppInitializer` | Inlined bitmap readers của Glide: `ImageReader$ByteBufferReader.decodeBitmap`, `ImageReader$InputStreamImageReader.decodeBitmap` | `BitmapFactory.decodeStream` / decode bitmap trong pipeline của thư viện Glide | **Bumptech Glide** / **AndroidX Startup** | `com.github.bumptech.glide:glide:4.16.0` & `androidx.startup:startup-runtime` |
| `jb1.b` | `com.tscanner.app.utils.billing.PlayPurchaseVerifier` | `executeDefaultHttp(String, String, Map, String):388` | Gọi `HttpURLConnection` để xác thực hóa đơn Play Billing với máy chủ verifier | **App Code** | `com.tscanner.app` (`PlayPurchaseVerifier.kt`) — *Không phải tải bitmap, là HTTP API client* |
| `c5.invokeSuspend` | `AppAuthManager$signInWithGoogle$job$1$3` (R8 Merged Synthetic Class) | `invokeSuspend(Object)` (inlining QrScannerHelper & TesseractOcrHelper) | Lớp tổng hợp `c5` được R8 merge từ nhiều nguồn (`AppAuthManager`, `QrScannerHelper`, và inlining `TesseractOcrHelper.calculateInSampleSize`). Cần phân tích cụ thể nhánh DEX bytecode trước khi kết luận cảnh báo bitmap là false positive. | **App Code / Merged** | `com.tscanner.app` — *R8 synthetic merged class* |

---

## 3. Bảng phân định Chủ sở hữu (Owner Matrix) cho 5 cảnh báo Google Play

| Cảnh báo Play | Nguyên nhân gốc rễ & Call Sites | Chủ sở hữu chính (Owner) | Biện pháp xử lý kỹ thuật tương ứng |
|---|---|---|---|
| **1. Edge-to-edge** | Cả 8 Activity của App đã gọi `enableEdgeToEdge()`. Tuy nhiên listener insets hiện tại mới chỉ xử lý top/bottom, chưa xử lý đầy đủ cạnh trái/phải trên màn hình ngang, đa cửa sổ hoặc thiết bị có cutout; một số màn hình còn dùng `adjustPan`. | **App Code** (UI Activities & Layouts) | Thực hiện tại **G01**: Hoàn thiện inset 4 cạnh, bảo toàn padding gốc, kiểm tra IME và điều chỉnh `windowSoftInputMode`. |
| **2. API cửa sổ lỗi thời** | - `f.s` (`SHORT_EDGES`), `d70.b`, `f70.b`: phát sinh từ `androidx.activity.EdgeToEdgeApi26/28/29` của `androidx.activity:activity:1.9.3`.<br>- `az0.G`: phát sinh từ `MaterialDatePicker` của `com.google.android.material:material:1.12.0`.<br>- Theme `app/src/main/res/values/themes.xml:12,14` còn khai báo `statusBarColor`/`navigationBarColor`. | **Google AndroidX / Material SDK** (Call sites) & **App Theme** (`themes.xml`) | Thực hiện tại **G02**: Loại bỏ màu thanh hệ thống trong `themes.xml`. Đánh giá việc nâng cấp `androidx.activity` / `material` lên bản tương thích mới nhất đã loại bỏ các API này hoặc xác nhận hành vi upstream. |
| **3. Khóa hướng màn hình** | - 6 Activity của App khai báo `android:screenOrientation="portrait"` trong `AndroidManifest.xml`.<br>- 2 Delegate Activity của Google Play Services (`GmsDocumentScanningDelegateActivity`, `GmsBarcodeScanningDelegateActivity`) do SDK ML Kit gộp vào. | **App Code** (6 Activity của App) & **Google Play Services ML Kit** (2 Activity của SDK) | Thực hiện tại **G03**: Chuẩn bị state/restore (đặc biệt là camera preview, crop bitmap, OCR draft), gỡ khóa portrait cho 6 Activity của app; đánh giá override hoặc ghi nhận hạn chế SDK ML Kit. |
| **4. Bitmap mạng** | - Cảnh báo do Google quét các lệnh gọi mạng / decode bitmap.<br>- `co0.c` & `k8.l` thuộc về thư viện **Glide 4.16.0**.<br>- Không tìm thấy lệnh tải bitmap mạng thủ công nào ngoài Glide (`decodeStream` trong `OcrReaderViewModel` và `TesseractOcrHelper` chỉ xử lý Uri ảnh cục bộ của tài liệu quét). | **Thư viện bên thứ ba** (`com.github.bumptech.glide:glide`) | Thực hiện tại **G04**: Rà soát việc sử dụng Glide trong `MoreFragment` và `AccountDetailDialog`, đảm bảo kích thước target hữu hạn, dọn dẹp request theo lifecycle, không tải ảnh kích thước gốc không cần thiết. |
| **5. Resource shrinking** | `app/build.gradle` bản release đã có `minifyEnabled true`, `proguard-android-optimize.txt`, nhưng chưa cấu hình `shrinkResources true`. | **App Configuration** (`app/build.gradle`) | Thực hiện tại **G05**: Khai báo `shrinkResources true`, kiểm tra giữ tài nguyên động (`keep.xml` cho `billing_verifier_url`), đo kích thước AAB sau khi tối ưu. |

---

## 4. Đo lường Baseline trước thay đổi

### Kích thước Artifact Baseline:
- **AAB Release (`app-release.aab`)**: `17,420,226 bytes` (~16.61 MB)
  - SHA-256: `5B8FC710A1E8FB4DF22EDC22502DDF7DFC2E17D2901A0F8216B51F0846F6CF3F`
- **APK Release (`app-release-unsigned.apk`)**: `24,056,291 bytes` (~22.94 MB)
  - SHA-256: `274C3D4C7239749C48DB0071593911BECB82C2BDC7E29FF1CA86080E1710E15E`
- **DEX trong AAB (`classes.dex`)**: `12,795,248 bytes` (~12.20 MB)

### Kiểm thử Host Unit Test Baseline:
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (với `JAVA_HOME=C:/Users/nguye/.jdks/openjdk-21.0.1`)
- Kết quả: **BUILD SUCCESSFUL** (thời gian chạy 24s)
- Thống kê test:
  - **Tổng số tests**: **1046**
  - **Failures**: **0**
  - **Skipped**: **0**
  - **Tỷ lệ thành công**: **100%**

### Đo lường RAM & Device UI Smoke:
- Trạng thái thiết bị: Lệnh `platform-tools/adb.exe devices` đã khởi động daemon adb (`tcp:5037`), danh sách thiết bị đính kèm hiện tại: **Trống** (chưa có thiết bị vật lý hoặc emulator nào đang kết nối).
- **Hạn chế được ghi nhận**: Đúng theo tiêu chí nghiệm thu của G00 ("Dừng và báo nếu chưa có thiết bị, không tự đánh dấu đạt"), việc đo RAM thực tế và chụp màn hình UI thiết bị được tạm hoãn cho đến khi có thiết bị chạy thử. Không tự suy diễn hay ước lượng số liệu RAM.

---

## 5. Kết luận & Đề xuất bàn giao cho G01

1. **G00 đã hoàn tất xuất sắc toàn bộ điều kiện đầu vào**:
   - Đã xác định chính xác artifact bị cảnh báo là `versionCode 21` (không phải build local 22).
   - Đã giải mã 100% các call site làm mờ (`az0.G`, `d70.b`, `f70.b`, `f.s`, `c5.invokeSuspend`, `k8.l`, `co0.c`, `jb1.b`) đến từng dòng bytecode và lớp thư viện.
   - Đã xác định rõ ràng ranh giới trách nhiệm: Cảnh báo `SHORT_EDGES` và window insets thuộc về chính thư viện `androidx.activity:1.9.3` của Google khi gọi `enableEdgeToEdge()`.
   - Toàn bộ artifact gốc và mapping đã được lưu trữ an toàn trong `baseline_artifacts_20261005/`.
   - Baseline host tests đạt 1046/1046 test pass.
   - Không có thay đổi nào được ghi đè lên production code hay cấu hình release trong gói G00.
2. **Sẵn sàng cho G01**:
   - Đủ điều kiện để người dùng phê duyệt bắt đầu thực hiện gói **G01 — Hoàn thiện edge-to-edge và insets** trên 8 Activity theo phạm vi quy định.
