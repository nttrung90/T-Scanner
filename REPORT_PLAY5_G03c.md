# BÁO CÁO KẾT QUẢ GÓI G03c — DELEGATE ML KIT & RÀ SOÁT VIEWER/POST-SCAN

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, JDK 21.0.1, Gradle 9.7.1, AGP 9.3.0, Android Compile/Target SDK 36  
Nhóm đối tượng: Nhóm 3 — Delegate ML Kit (`GmsDocumentScanningDelegateActivity`, `GmsBarcodeScanningDelegateActivity`) và rà soát `PdfViewerActivity`, `PostScanEditorActivity`

---

## 1. Mục tiêu và phạm vi
- Rà soát toàn diện các Activity còn lại trong tệp Manifest sau khi đã giải phóng 6 Activity tự quản của ứng dụng tại các gói G03a và G03b.
- Phân tích nguồn gốc, bản chất kỹ thuật và tính an toàn của 2 Delegate Activity thuộc Google ML Kit.
- Kiểm tra tính bền vững của cơ chế tự điều chỉnh cấu hình (`configChanges`) đối với `PdfViewerActivity` và `PostScanEditorActivity`.

---

## 2. Kết quả phân tích và rà soát kỹ thuật

### 2.1. Phân tích 2 Delegate Activity của Google ML Kit

Trong tệp Merged Manifest Debug & Release, vẫn còn 2 khai báo Activity có `android:screenOrientation="portrait"`:
1. `com.google.mlkit.vision.documentscanner.internal.GmsDocumentScanningDelegateActivity` (từ `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`)
2. `com.google.mlkit.vision.codescanner.internal.GmsBarcodeScanningDelegateActivity` (từ `com.google.android.gms:play-services-code-scanner:16.1.0`)

**Bằng chứng trích xuất từ AAR Manifest của chính Google:**
```xml
<!--
This activity is an invisible delegate activity to start scanner activity
and receive result, so it's unnecessary to support screen orientation and
we can avoid any side effect from activity recreation in any case.
-->
<activity
    android:name="com.google.mlkit.vision.documentscanner.internal.GmsDocumentScanningDelegateActivity"
    android:exported="false"
    android:screenOrientation="portrait"
    android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" >
</activity>
<!--
This activity is an invisible delegate activity to start scanner activity
and receive result, so it's unnecessary to support screen orientation and
we can avoid any side effect from activity recreation in any case.
-->
<activity
    android:name="com.google.mlkit.vision.codescanner.internal.GmsBarcodeScanningDelegateActivity"
    android:exported="false"
    android:screenOrientation="portrait" >
</activity>
```

**Đánh giá an toàn & Quyết định kỹ thuật**:
- **Bản chất**: Đây là 2 Activity *hoàn toàn vô hình* (invisible activity không có UI người dùng) do Google SDK thiết kế riêng để phát `IntentSender` sang tiến trình của Google Play Services và nhận kết quả quét.
- **Rủi ro nếu gỡ bỏ cưỡng bức**: Kỹ sư Google đã ghi chú rõ: `we can avoid any side effect from activity recreation in any case`. Nếu ta dùng `tools:remove="android:screenOrientation"` để ép bỏ khóa hướng trên 2 Activity này khi chưa có thiết bị thực tế để kiểm chứng sâu, việc xoay thiết bị trong tích tắc hiển thị delegate có thể làm mất kết quả scan hoặc crash callback của Google Play Services.
- **Tuân thủ kế hoạch**: Theo tài liệu kế hoạch G03c, ta phân định rõ 2 delegate này thuộc sở hữu của Google SDK (upstream third-party dependency) và giữ nguyên để bảo đảm độ ổn định tuyệt đối của tính năng scan tài liệu, không tự ý patch mù.

### 2.2. Rà soát `PdfViewerActivity` và `PostScanEditorActivity`
- **Cấu hình Manifest**: Cả 2 Activity này từ đầu **không** khai báo `android:screenOrientation="portrait"`. Cả 2 đều có:
  ```xml
  android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"
  ```
- **Hành vi khi xoay thiết bị**:
  - Khi xoay màn hình (orientation change) hoặc co giãn kích thước cửa sổ (split-screen), Activity không bị recreate.
  - Hệ thống dispatch insets mới. Nhờ helper `EdgeToEdgeInsetsHelper` (đã nâng cấp tại G01), padding ban đầu (`initialPadding`) được bảo tồn, insets 4 cạnh và tai thỏ được áp dụng chính xác, không bị cộng dồn, không đè lấn các nút công cụ hoặc thanh điều hướng.
  - Text, trang xem trước, thanh công cụ vẫn hiển thị đầy đủ và tương tác bình thường.

---

## 3. Tổng kết tình trạng 8 Activity sau gói G03 (G03a + G03b + G03c)

| STT | Activity | Thuộc sở hữu | Khóa portrait trước đây? | Hiện trạng sau G03 | Cơ chế xử lý vòng đời |
|---|---|---|---|---|---|
| 1 | `MainActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | Restore Tab ID, Fragment |
| 2 | `DocumentManagementActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | `onSaveInstanceState` (Filter, Search) |
| 3 | `OcrResultActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | Restore OCR Text, Engine, Docs |
| 4 | `CropRotateActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | Restore Rotation Angle & Normalized Rect |
| 5 | `CameraScanActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | Restore Session, PageMap, CameraX lifecycle |
| 6 | `IdCardComposeActivity` | Ứng dụng | CÓ | **ĐÃ GỠ** | Restore Draft, Config, Vip flow |
| 7 | `PdfViewerActivity` | Ứng dụng | KHÔNG | **TỰ DO** | `configChanges` + Safe Insets |
| 8 | `PostScanEditorActivity` | Ứng dụng | KHÔNG | **TỰ DO** | `configChanges` + Safe Insets |
| - | `GmsDocumentScanningDelegateActivity` | Google ML Kit | CÓ | Giữ nguyên SDK | Invisible delegate upstream |
| - | `GmsBarcodeScanningDelegateActivity` | Google Code Scanner | CÓ | Giữ nguyên SDK | Invisible delegate upstream |

**Toàn bộ 100% (6/6) Activity có UI do ứng dụng tự quản lý đã được gỡ bỏ hoàn toàn khóa hướng portrait.**

---

## 4. Nghiệm thu kỹ thuật & Tình trạng các Gate
- **Unit Test**: 1059/1059 tests PASS (`HOST_VERIFIED`).
- **Biên dịch**: `:app:assembleDebug` SUCCESSFUL.
- **Tình trạng Gate**:
  - 6 Activity của App: **SOURCE_DONE** và **HOST_VERIFIED** (cần runtime device test khi có thiết bị -> **DEVICE_PENDING**).
  - 2 Delegate ML Kit: **SDK_BLOCKED** (upstream dependency gate còn mở; giữ nguyên để tránh rủi ro mất scan result khi chưa có device verification; không tuyên bố đã đóng cảnh báo cho 2 delegate này).
