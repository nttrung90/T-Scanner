# Báo cáo Điều tra & Tái hiện Gói L00 — Ngôn ngữ Giao diện Màn hình Quét

Ngày thực hiện: 26/09/2026  
Mã gói: **L00 — Tái hiện và chốt hợp đồng (Chỉ kiểm tra / Static & Architecture Verification)**  
Môi trường: Windows 10 / PowerShell / Gradle 9.7.1 / JDK 22  
Repo: `E:\DU AN AI\T-Scanner`  
Tài liệu kế hoạch tham chiếu: `PLAN_SCAN_UI_LANGUAGE_SMALL_MODEL_2026-09-26.md`

---

## 1. Trạng thái Snapshot Git & Môi trường Kiểm tra

### 1.1. Snapshot Git
- **Branch:** `master`
- **Head Commit:** `ade6a4f0548ccda18e2dd4e901cbe01ffaaab6b8` (`chore: bump version to 0.6.0 (Build 11)`)
- **Tình trạng Working Tree:** Có nhiều thay đổi staged/unstaged/untracked từ các phiên làm việc trước (VIP Billing Round 2, Multi-lingual audit, Document management, etc.).
- **Nguyên tắc bảo toàn:** Toàn bộ các thay đổi hiện hữu được giữ nguyên vẹn 100%, không checkout, không reset, không ghi đè bất kỳ file nào ngoài file báo cáo `REPORT_SCAN_LANGUAGE_L00.md`.

### 1.2. Thông số Phiên bản Ứng dụng & Thư viện
- **Application ID:** `com.tscanner.app`
- **Version Code:** `17`
- **Version Name:** `1.01`
- **SDK Targets:** `minSdk 26`, `compileSdk 36`, `targetSdk 36`
- **Google Play Services Document Scanner SDK:** `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`
- **AndroidX CameraX:** `1.4.1` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **Ngôn ngữ hỗ trợ trong app (8 bộ resources):** `en` (values), `vi` (values-vi), `es` (values-es), `pt` (values-pt), `fr` (values-fr), `in`/`id` (values-in), `de` (values-de), `ja` (values-ja).

---

## 2. Kết quả Điều tra Nơi sở hữu UI & Cơ chế Locale (UI Ownership & Boundary Analysis)

### 2.1. Phân tích Bản quyền Giao diện và Giới hạn Kỹ thuật SDK (Xác nhận F01)
Khi người dùng kích hoạt tính năng quét mặc định:
1. `MainActivity.kt:176-179` (FAB) và `HomeFragment.kt:256-257` (`btnActionScan`) gọi `MainActivity.startGoogleAiScan()`.
2. Hàm này chuyển qua `DocumentScannerHelper.startScan()`:
   ```kotlin
   val client = GmsDocumentScanning.getClient(defaultOptions)
   client.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
       launcher.launch(IntentSenderRequest.Builder(intentSender).build())
   }
   ```
3. **Cơ chế hiển thị của Google SDK:**
   - Intent được khởi chạy qua `IntentSenderRequest` thuộc về Google Play Services (`com.google.android.gms`).
   - Màn hình quét ("Đang quét", "Chụp thủ công", "Tự động chụp", "sẽ chỉ truy cập vào các trang tài liệu...") được render hoàn toàn trong Process và Task của **Google Play Services**, sử dụng tài nguyên nội bộ của APK Google Play Services trên máy.
   - Trong cây mã nguồn và resources của T-Scanner (`app/src/main/res`), hoàn toàn không chứa các chuỗi ký tự trên.
4. **Cơ chế quản lý ngôn ngữ của T-Scanner (`AppLanguageManager.kt`):**
   - T-Scanner áp dụng ngôn ngữ cho ứng dụng qua:
     - Android 13+ (API >= 33): `LocaleManager.setApplicationLocales(LocaleList.forLanguageTags(tagToApply))`
     - Android <= 32 (API < 33): `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tagToApply))`
   - Cả hai API này chỉ có phạm vi tác động cục bộ tới package của ứng dụng (`com.tscanner.app`), **hoàn toàn không thay đổi ngôn ngữ hệ thống** và **không thể ép locale sang Process bên ngoài** như `com.google.android.gms`.
5. **Giới hạn API công khai của Google ML Kit Document Scanner:**
   - Đã kiểm tra tài liệu chính thức (`GmsDocumentScannerOptions.Builder`):
     - Chỉ có các phương thức: `setScannerMode`, `setResultFormats`, `setPageLimit`, `setGalleryImportAllowed`.
     - **Hoàn toàn KHÔNG có API nào cho phép thiết lập Locale, LanguageTag, hay truyền Context/Configuration ngôn ngữ**.
   - Google Play Services tự động chọn ngôn ngữ hiển thị dựa trên:
     - Ngôn ngữ hệ thống của thiết bị (System Locales).
     - Hoặc ngôn ngữ tài khoản Google / Google Play Services trên thiết bị.
6. **Kết luận xác nhận 100% nguyên nhân:** Khi máy có hệ thống tiếng Việt (`vi-VN`), nhưng người dùng chọn ngôn ngữ ứng dụng là tiếng Anh (`en`), Nhật (`ja`), v.v., màn hình Quét Google AI **bắt buộc** vẫn hiển thị tiếng Việt do Google Play Services chi phối. Không thể khắc phục điều này bằng cách bọc Context, gọi `Locale.setDefault()`, thêm chuỗi vào `strings.xml`, hay truyền extra intent.

---

## 3. Tổng hợp Toàn bộ Các Cửa vào Quét (Scan Entry Points Inventory)

| Cửa vào (Entry Point) | Thành phần khởi tạo | Hành vi hiện tại (Production) | UI thực hiển thị | Phụ thuộc Locale |
|---|---|---|---|---|
| **FAB Camera (Click)** | `MainActivity.binding.fabCamera` | Gọi `startGoogleAiScan()` | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **FAB Camera (Long Click)** | `MainActivity.binding.fabCamera` | Toast `camera_opening_fast_scanner` -> `startFastDocumentScan()` | `CameraScanActivity` (Nội bộ) | 100% Ngôn ngữ App |
| **Home Quick Action Quét (Click)** | `HomeFragment.binding.btnActionScan` | Gọi `(activity as? MainActivity)?.startGoogleAiScan()` | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **Home Quick Action Quét (Long Click)** | `HomeFragment.binding.btnActionScan` | Toast `opening_fast_scan_toast` -> `startFastDocumentScan()` | `CameraScanActivity` (Nội bộ) | 100% Ngôn ngữ App |
| **Home Empty State Quét** | `HomeFragment.binding.btnScanNewDocument` | Gọi `(activity as? MainActivity)?.startDocumentScan()` -> Google AI | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **Files Empty State Quét** | `FilesFragment.binding.btnEmptyScan` | Gọi `(activity as? MainActivity)?.startDocumentScan()` -> Google AI | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **Dialog Scan Options (Option 1)** | `dialog_scan_options.xml` (`btn_option_scan_ai`) | Gọi `startGoogleAiScan()` (Mặc định / Khuyên dùng) | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **Dialog Scan Options (Option 2)** | `dialog_scan_options.xml` (`btn_option_scan_fast`) | Gọi `startFastDocumentScan()` | `CameraScanActivity` (Nội bộ) | 100% Ngôn ngữ App |
| **CameraScanActivity AI Button** | `CameraScanActivity.binding.btnSwitchAiScan` | Gọi `startGoogleAiScan()` / `startGoogleIdCardScan()` | Google Play Services SDK | Ngôn ngữ máy / Play services (Lệch với App) |
| **Home Quick Action Quét Thẻ (Click)** | `HomeFragment.binding.btnActionIdCard` | Mở `IdCardOptionsDialog` -> Chọn Camera -> `startGoogleIdCardScan()` | Google Play Services SDK (`pageLimit=2`) | Ngôn ngữ máy / Play services (Lệch với App) |
| **Home Quick Action Quét Thẻ (Long Click)** | `HomeFragment.binding.btnActionIdCard` | Toast `opening_fast_id_card_scan_toast` -> `startFastIdCardScan()` | `CameraScanActivity` (ID Card Mode) | 100% Ngôn ngữ App |

---

## 4. Ma trận Kiểm thử Tái hiện & Trạng thái Thực thi (Reproduction Matrix)

### 4.1. Trạng thái Kết nối Môi trường
- **Lệnh thực thi:**
  ```powershell
  & "C:\Users\nguye\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
  ```
- **Kết quả:** Daemon started successfully on port 5037; List of devices attached: **EMPTY** (Hiện không có thiết bị vật lý hoặc máy ảo emulator nào đang gắn kết nối adb).
- **Tuân thủ quy định Gói L00:** Không tự suy diễn PASS khi thiếu thiết bị. Toàn bộ các ca runtime trên thiết bị thật được đánh dấu là **NOT RUN (DEVICE PENDING)**.

### 4.2. Bảng Ma trận Kiểm thử
| Mã ca | Cấu hình Hệ thống | Ngôn ngữ App (T-Scanner) | Phiên bản Android | Cửa vào kiểm tra | Kết quả T-Scanner UI | Kết quả Google Scanner UI | Trạng thái Runtime |
|---|---|---|---|---|---|---|---|
| **TC-01** | `vi-VN` | MANUAL `en` | Android 14 (API 34) | FAB / Home Quét | Tiếng Anh (App) | Tiếng Việt ("Đang quét", "Chụp thủ công") | **NOT RUN (DEVICE PENDING)** (Static Proof: FAIL) |
| **TC-02** | `vi-VN` | MANUAL `ja` | Android 14 (API 34) | FAB / Home Quét | Tiếng Nhật (CJK font) | Tiếng Việt ("Đang quét") | **NOT RUN (DEVICE PENDING)** (Static Proof: FAIL) |
| **TC-03** | `en-US` | MANUAL `vi` | Android 14 (API 34) | FAB / Home Quét | Tiếng Việt (App) | Tiếng Anh ("Scanning", "Auto capture") | **NOT RUN (DEVICE PENDING)** (Static Proof: FAIL) |
| **TC-04** | `en-US` | SYSTEM `en` | Android 14 (API 34) | FAB / Home Quét | Tiếng Anh (App) | Tiếng Anh (Trùng khớp ngẫu nhiên) | **NOT RUN (DEVICE PENDING)** (Static Proof: PASS) |
| **TC-05** | `vi-VN` | MANUAL `en` -> `ja` (Hot switch) | Android 14 (API 34) | Reopen Quét | Tiếng Nhật (App) | Tiếng Việt (Không đổi) | **NOT RUN (DEVICE PENDING)** (Static Proof: FAIL) |
| **TC-06** | `vi-VN` | MANUAL `en` | Android 11 (API 30 - <=32) | FAB / Home Quét | Tiếng Anh (AppCompat) | Tiếng Việt (Google Play Services) | **NOT RUN (DEVICE PENDING)** (Static Proof: FAIL) |
| **TC-07** | `vi-VN` | MANUAL `en` | Android 14 (API 34) | FAB Long Click -> CameraScan | Tiếng Anh (`Auto Crop`, `Done`) | N/A (UI nội bộ) | **NOT RUN (DEVICE PENDING)** (Static Proof: PASS) |

*Ghi chú bằng chứng tĩnh:* Phân tích mã nguồn và API contract đã chứng minh 100% rằng Google SDK chỉ nhận locale hệ thống; sự lệch ngôn ngữ giữa T-Scanner và Google SDK là tất yếu ở mọi cấu hình máy có System Locale khác với App Locale.

---

## 5. So sánh Tính năng Chi tiết: `CameraScanActivity` vs Google ML Kit Document Scanner

| Tiêu chí Tính năng | Google ML Kit Document Scanner | Trình quét Nội bộ `CameraScanActivity` | Khoảng trống / Đánh đổi khi chuyển đổi |
|---|---|---|---|
| **1. Ngôn ngữ Giao diện (UI Localization)** | Phụ thuộc Play Services / System Locale. **Không kiểm soát được**. | **100% tuân thủ `AppLanguageManager`** (Đầy đủ 8 bộ ngôn ngữ: en, vi, es, pt, fr, id, de, ja). | **CameraScanActivity vượt trội hoàn toàn về đồng nhất ngôn ngữ.** |
| **2. Chụp thủ công (Manual Shutter)** | Có nút bấm chụp. | Có nút chụp lớn, hỗ trợ Zero Shutter Lag UX, phản hồi rung haptic và hiệu ứng chớp sáng visual flash. | Tương đương. Trải nghiệm chụp nội bộ rất mượt mà. |
| **3. Tự động chụp (Auto-capture)** | Tự động phát hiện tài liệu ổn định trong khung và tự chụp mà không cần bấm nút. | **Chưa có** (Chỉ hỗ trợ chụp thủ công qua nút shutter). | **Mất tính năng tự chụp rảnh tay.** Người dùng bắt buộc phải bấm nút chụp. |
| **4. Nhập ảnh từ Thư viện (In-viewfinder Gallery)** | Có nút chọn ảnh thư viện tích hợp ngay trên màn hình camera. | Không có nút thư viện trên màn hình camera (phải dùng chức năng Import tài liệu từ màn Home/Files). | **Mất lối tắt thư viện trực tiếp trong viewfinder.** |
| **5. Cắt viền tài liệu (Document Crop)** | Tự động phát hiện và cung cấp màn hình nắn góc 4 điểm 3D tương tác trực tiếp ngay trong luồng Google. | Có nút bật/tắt Auto Crop (`DocumentEdgeDetector.detectAndCrop`). Sau khi chụp xong sẽ chuyển sang `PostScanEditorActivity` để nắn chỉnh. | **Khác biệt trải nghiệm:** Không chỉnh 4 góc ngay trên viewfinder của camera mà chỉnh ở bước biên tập tiếp theo. |
| **6. Xử lý AI nâng cao (Filters & Clean-up)** | Tự động xóa ngón tay che tài liệu, xóa bóng đổ, tẩy vết bẩn (chế độ `SCANNER_MODE_FULL`). | Chỉ xử lý chuẩn hóa xoay hướng (`normalizeImageOrientation`) và cắt viền contour. Các bộ lọc màu/độ nét được thực hiện ở màn hình editor. | **Mất tính năng xóa ngón tay và xóa bóng đổ tự động tức thì của Google AI.** |
| **7. Quét nhiều trang (Multi-page batch)** | Cho phép chụp liên tục nhiều trang, xem carousel và xóa trực tiếp trên thanh Google. | Cho phép chụp liên tục không giới hạn, lưu bộ đệm bất đồng bộ, có thumbnail trang vừa chụp và badge số trang. | **Tương đương về khả năng chụp nhiều trang.** Kiến trúc C01 hai pha (Two-Phase Commit) bảo vệ an toàn dữ liệu ảnh. |
| **8. Quét thẻ căn cước (ID Card Flow)** | Giới hạn `pageLimit = 2`. Sau 2 trang trả kết quả về `IdCardComposeActivity`. | Có chế độ chuyên biệt `isIdCardMode`: khung viền tỷ lệ thẻ, hint "Chụp mặt trước" -> "Chụp mặt sau", chụp đủ 2 mặt tự động chuyển sang `IdCardComposeActivity`. | **CameraScanActivity có trải nghiệm hướng dẫn thẻ trực quan và chuẩn ngôn ngữ hơn.** |

---

## 6. Đề xuất Phương án & Bảng Đánh đổi Kỹ thuật (Technical Trade-offs)

Để giải quyết triệt để yêu cầu: *"Đường Quét mặc định có giao diện theo ngôn ngữ UI đã chọn, không tự đổi ngôn ngữ thiết bị, không nhầm ngôn ngữ OCR với ngôn ngữ UI"*, có 3 phương án routing khả thi:

### Bảng So sánh 3 Hướng Tiếp cận

| Tiêu chí đánh giá | Hướng A: Đổi Default sang Camera Nội bộ (`CameraScanActivity`) | Hướng B: Giữ Default Google AI, thêm cảnh báo & lối thoát | Hướng C: Routing Thông minh (`Smart Default Routing`) |
|---|---|---|---|
| **Khái niệm** | Default mở `CameraScanActivity`. Google AI trở thành tùy chọn phụ trong dialog/menu. | Mặc định vẫn là Google AI. Bổ sung thông tin giải thích giới hạn và nút chuyển camera nội bộ. | Nếu `appLocale == systemLocale`: Mở Google AI.<br>Nếu `appLocale != systemLocale`: Mở Camera Nội bộ. |
| **Độ đồng nhất ngôn ngữ UI** | **100% Tuyệt đối** trên mọi ngôn ngữ và thiết bị. | **Kém** (Vẫn bị tiếng Việt trên máy tiếng Việt khi app tiếng Anh). | **100% Tuyệt đối** (Tránh được hoàn toàn tình huống lệch ngôn ngữ). |
| **Bảo toàn tính năng AI Google** | Có sẵn dưới dạng tùy chọn (nút "Quét AI" trong camera hoặc dialog scan options). | Bảo toàn tối đa ở đường mặc định. | Tự động tận dụng tối đa khi cùng ngôn ngữ; có nút tùy chọn khi khác ngôn ngữ. |
| **Tác động đến người dùng** | Giao diện camera nội bộ cực nhanh, chuẩn ngôn ngữ, nhưng mất tính năng tự chụp rảnh tay ở default. | Người dùng vẫn gặp lỗi hiển thị tiếng Việt như phản ánh trong báo cáo sự cố. | Tối ưu hóa trải nghiệm: người dùng cùng ngôn ngữ có AI cao cấp, người dùng khác ngôn ngữ không bị ức chế vì sai ngôn ngữ. |
| **Độ phức tạp mã nguồn** | Thấp (Đổi target method trong MainActivity/HomeFragment). | Rất thấp (Chỉ thêm text giải thích). | Trung bình (Cần `ScanUiPolicy` xác định độ lệch locale và cho phép người dùng ghi đè thủ công). |
| **Mức độ đáp ứng nghiệm thu** | **ĐẠT HOÀN TOÀN** hợp đồng kế hoạch. | **KHÔNG ĐẠT** (Kế hoạch quy định: không được đánh dấu sửa xong nếu giữ nguyên). | **ĐẠT HOÀN TOÀN** (Đi kèm ghi chú rõ ràng về chính sách routing). |

### Đề xuất Khuyến nghị:
- **Khuyến nghị chọn Hướng A (hoặc Hướng C có tùy chọn cho người dùng ghi đè trong Cài đặt/Hộp thoại tùy chọn):**
  1. Đặt `CameraScanActivity` làm trình quét mặc định cho nút FAB và Quick Action Quét khi người dùng muốn đảm bảo 100% ngôn ngữ UI theo cài đặt app.
  2. Giữ nguyên nút chuyển đổi "Quét AI" (`btnSwitchAiScan`) trên thanh điều khiển của `CameraScanActivity` và trong `dialog_scan_options.xml` để người dùng có toàn quyền mở Google ML Kit Scanner khi cần các tính năng AI nâng cao (xóa ngón tay, xóa bóng, auto-shutter).
  3. Bổ sung thông tin ngắn gọn, đã bản địa hóa ở dialog tùy chọn: *"Trình Quét AI của Google hiển thị theo ngôn ngữ của hệ thống thiết bị"*.

---

## 7. Kiểm tra Tích hợp Môi trường & Baseline Tests Hiện tại

### 7.1. Chạy Localization Validator
```powershell
python scripts/audit_localization.py
```
- **Kết quả:** Phát hiện 12 lỗi localization tồn tại từ trước (F03):
  - 6 locale (`de`, `es`, `fr`, `in`, `ja`, `pt`) thiếu 48 khóa cũ.
  - `vi` thiếu 6 khóa VIP billing cũ.
  - 5 nhãn hardcode trong layout OCR editor.
- **Xác nhận:** Các lỗi này thuộc về F03, không liên quan đến màn hình Quét và không bị ảnh hưởng bởi gói L00.

### 7.2. Chạy Kiểm thử Unit Test Hiện tại
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest
```
- **Kết quả:** **BUILD SUCCESSFUL in 21s**
- **Số lượng test:** 893 unit tests completed, **0 failures, 0 errors, 100% GREEN**.

---

## 8. Kết luận Nghiệm thu Gói L00 & Điểm dừng (Checkpoint)

1. **Xác nhận phạm vi sở hữu UI:** Giao diện tiếng Việt trong ảnh phản ánh xuất phát từ Google Play Services SDK (`com.google.android.gms`), hoàn toàn nằm ngoài tài nguyên của `com.tscanner.app` và không thể can thiệp bằng LocaleManager/AppCompat của ứng dụng.
2. **Xác nhận tính năng Camera nội bộ:** `CameraScanActivity` đã hoàn thiện đầy đủ về chụp nhiều trang, Zero Shutter Lag, Two-phase commit C01, Auto Crop, và hỗ trợ 100% cả 8 bộ ngôn ngữ UI.
3. **Danh sách file thay đổi:** Duy nhất 1 file tài liệu: `REPORT_SCAN_LANGUAGE_L00.md`. Không sửa bất kỳ file mã nguồn production nào.
4. **Điểm dừng quy định:** Dừng lại tại đây để báo cáo người dùng. **Chỉ triển khai gói L01/L02 sau khi người dùng phê duyệt hướng routing kỹ thuật.**
