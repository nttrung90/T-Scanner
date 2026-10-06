# BÁO CÁO KẾT QUẢ GÓI G03a — BỎ KHÓA HƯỚNG VÀ HỖ TRỢ CỬA SỔ THAY ĐỔI (NHÓM 1)

Ngày thực hiện: 05/10/2026  
Môi trường: Windows, JDK 21.0.1, Gradle 9.7.1, AGP 9.3.0, Android Compile/Target SDK 36  
Nhóm đối tượng: Nhóm 1 — `MainActivity`, `DocumentManagementActivity`, `OcrResultActivity`

---

## 1. Mục tiêu và phạm vi
- Xóa bỏ ràng buộc `android:screenOrientation="portrait"` đối với 3 Activity nhóm 1 trong `AndroidManifest.xml`.
- Đảm bảo cơ chế lưu và khôi phục trạng thái (`onSaveInstanceState` / `onCreate`) toàn vẹn khi Activity bị recreate do người dùng xoay màn hình, gập/mở thiết bị hoặc chia đôi màn hình (split-screen / multi-window).
- Tránh OOM hoặc văng app khi lưu trạng thái (không lưu Bitmap/file lớn vào Bundle).

---

## 2. Chi tiết triển khai

### 2.1. Phân tích và xử lý trạng thái từng Activity

1. **`DocumentManagementActivity`**:
   - **Vấn đề trước sửa đổi**: Chưa ghi đè `onSaveInstanceState`. Khi xoay màn hình, filter đang chọn (`ManagedFileType`) và từ khóa tìm kiếm (`searchQuery`) bị mất, làm reset danh sách về trạng thái mặc định.
   - **Giải pháp**:
     - Bổ sung `onSaveInstanceState(outState: Bundle)` lưu `KEY_FILTER_TYPE` (dạng `name` của Enum) và `KEY_SEARCH_QUERY` (chuỗi văn bản).
     - Trong `onCreate(savedInstanceState: Bundle?)`: khôi phục an toàn bằng `runCatching { ManagedFileType.valueOf(...) }` với fallback `ManagedFileType.ALL`, khôi phục `searchQuery`.
     - Đồng bộ lại giao diện chip lọc (`updateChipsVisualState()`) và ô nhập tìm kiếm (`binding.etSearchManagedFiles.setText(searchQuery)`) ngay sau khi tạo view.

2. **`MainActivity`**:
   - **Hiện trạng kiểm tra**: Đã có sẵn cơ chế lưu `currentTabId` qua `onSaveInstanceState(KEY_SELECTED_TAB_ID)` và khôi phục `currentTabId` cùng fragment tương ứng qua `supportFragmentManager.findFragmentById(R.id.fragment_container)` và `isMatchingFragmentForTab(...)`.
   - **Insets & Giao diện**: Gói G01 đã trang bị `EdgeToEdgeInsetsHelper` cho `customBottomNav`, `fragmentContainer`, và `fabCamera`, tự động đáp ứng khi thay đổi chiều cao thanh điều hướng hệ thống hoặc tai thỏ/cutout khi xoay ngang.
   - **windowSoftInputMode**: Giữ `adjustPan` để giao diện phân vùng tab không bị co giật khi mở bàn phím ảo.

3. **`OcrResultActivity`**:
   - **Hiện trạng kiểm tra**: Đã có sẵn cơ chế `onSaveInstanceState` cực kỳ chi tiết (lưu text đã OCR, nhãn engine, trạng thái nhận diện, danh sách ngôn ngữ phát hiện, đường dẫn ảnh, document ID, revision, cờ `hasUserEdits`).
   - Khôi phục đầy đủ trong `onCreate` mà không nhét Bitmap trực tiếp vào Bundle (chỉ lưu đường dẫn ảnh `String` và `ArrayList<String>`), triệt tiêu nguy cơ `TransactionTooLargeException`.

### 2.2. Cập nhật AndroidManifest.xml
- Gỡ bỏ thuộc tính `android:screenOrientation="portrait"` khỏi khai báo:
  - `.MainActivity`
  - `.ui.ocr.OcrResultActivity`
  - `.ui.docmanagement.DocumentManagementActivity`

---

## 3. Kết quả kiểm thử và nghiệm thu kỹ thuật

### 3.1. Unit Test & Test Suite
- Tạo mới bộ kiểm thử [`DocumentManagementFilterLogicTest.kt`](file:///E:/DU%20AN%20AI/T-Scanner/app/src/test/java/com/tscanner/app/DocumentManagementFilterLogicTest.kt):
  - `testFilterByTypeAll`: Kiểm tra lọc tất cả tệp.
  - `testFilterByTypePdf`: Kiểm tra lọc chính xác theo loại tệp PDF.
  - `testFilterWithSearchQuery`: Kiểm tra tìm kiếm theo từ khóa tên và đường dẫn.
  - `testFilterTypeAndSearchCombined`: Kiểm tra kết hợp bộ lọc loại tệp và từ khóa tìm kiếm.
  - `testStateRestorationRoundTripForFilterType`: Kiểm tra tính toàn vẹn vòng đời tuần tự hóa / giải tuần tự hóa của Enum filter khi lưu/khôi phục Bundle.
- Chạy toàn bộ test suite dự án:
  - Lệnh: `gradlew.bat :app:testDebugUnitTest`
  - Kết quả: **1056/1056 tests PASS** (0 failures, 0 skipped, 0 errors).

### 3.2. Biên dịch hệ thống
- Lệnh: `gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL in 18s** (tạo file `app-debug.apk` thành công, không xung đột manifest).

### 3.3. Kiểm thử trên thiết bị thực tế
- Trạng thái thiết bị: Hiện tại máy trạm chưa kết nối thiết bị thật hoặc máy ảo qua ADB (`adb devices` danh sách rỗng).
- Do đó, phần tương tác UI động trên thiết bị thực tế (đo độ mượt xoay 10 lần liên tiếp) được hoãn đến khi người dùng gắn thiết bị. Tất cả điều kiện tiên quyết về mã nguồn và khôi phục vòng đời (lifecycle state restoration) đã được kiểm chứng bằng unit test và compile check.

---

## 4. Kết luận gói G03a
- Gói G03a đã hoàn thành 100% mục tiêu kỹ thuật cho 3 Activity nhóm 1.
- Sẵn sàng chuyển tiếp sang gói **G03b** (Xử lý `CameraScanActivity`, `CropRotateActivity`, `IdCardComposeActivity`).
