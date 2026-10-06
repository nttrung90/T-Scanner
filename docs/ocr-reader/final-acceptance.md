# BIÊN BẢN NGHIỆM THU VÀ ĐÁNH GIÁ CHỨC NĂNG OCR READER/EDITOR (VÒNG 2 & CÁC GÓI MỞ RỘNG G1–G7)
## TÍNH NĂNG OCR DOCUMENT READER, EDITOR & OOXML EXPORTER

**Dự án:** T-Scanner Android App  
**Tài liệu tham chiếu:** `PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md`, `RECHECK_OCR_READER_EDITOR_2026-09-23.md`, `RECHECK_OCR_READER_EDITOR_ROUND2_2026-09-23.md`  
**Ngày cập nhật nghiệm thu:** 23/09/2026  
**Kết luận tổng thể:** **ĐÃ HOÀN TẤT VÀ ĐẠT TOÀN DIỆN CẢ 9 NHÓM SỬA LỖI N01–N09 VÀ 7 GÓI NÂNG CẤP G1–G7**  
Toàn bộ 387 bài kiểm thử đơn vị tự động cùng 7 ca kiểm thử reaudit mở rộng đều **PASS 100% (0 failure, 0 error)**.  
Bản dựng Release APK và AAB hoàn thành xuất sắc với mức tăng dung lượng chỉ **+144.009 bytes (~140,6 KB)**, nằm an toàn dưới trần ngân sách 10 MB (chỉ tiêu tốn **1,44%** hạn mức cho phép).

---

## 1. Kết Quả Đo Lường Kích Thước (Size Gate Audit)

*Được đo lường độc lập bằng công cụ tự động `scripts/measure_ocr_size.ps1 -Json` trên bản dựng release chính thức (`minifyEnabled false`, 2 ABI: `arm64-v8a`, `armeabi-v7a`):*

| Chỉ số | Baseline (S00) | Final Candidate (Hoàn tất G1–G7) | Mức chênh lệch (Delta) | Hạn mức cho phép (Budget) | Trạng thái Gate |
|---|---|---|---|---|:---:|
| **Kích thước APK Release** | **75.347.472 bytes** | **75.491.481 bytes** | **+144.009 bytes** (+0,191%) | **10.000.000 bytes** (10 MB) | **PASS** |
| **Kích thước AAB** | 44.641.170 bytes | 44.788.839 bytes | **+147.669 bytes** | 10.000.000 bytes (10 MB) | **PASS** |
| **Dung lượng còn trống** | — | — | **9.855.991 bytes** (~9,86 MB) | Tỷ lệ sử dụng: **1,44%** | **PASS** |

### Phân rã chi tiết thành phần APK (Compressed bytes):
- **dex:** 6.697.916 → 6.834.731 bytes (+136.815 bytes — Logic chuẩn hóa đa trang, bảo vệ CAS, khôi phục merge table, bộ đệm byte budget)
- **native (arm64-v8a):** 26.576.896 → 26.576.896 bytes (+0 bytes)
- **native (armeabi-v7a):** 19.261.156 → 19.261.156 bytes (+0 bytes)
- **assets:** 20.550.442 → 20.550.441 bytes (-1 byte)
- **res & resources.arsc:** 1.890.438 → 1.902.705 bytes (+12.267 bytes — Vector chevron, background chọn ô, chuỗi bản địa hóa đa ngôn ngữ)

---

## 2. Báo Cáo Khắc Phục Các Điểm Phát Hiện Vòng 2 (N01 – N09)

| Mã lỗi | Mức độ | Mô tả & Nguyên nhân gốc | Giải pháp khắc phục đã triển khai | Trạng thái |
|---|:---:|---|---|:---:|
| **N01** | P1 | Mọi trang trả từ engine giữ `pageIndex=1`, sửa trang đầu ghi đè các trang sau. | Chuẩn hóa `pageIndex` theo vị trí `pNum = i + 1` khi aggregate; hỗ trợ `pageId` ưu tiên trong command và reducer. | **PASS** |
| **N02** | P1 | Undo/Redo trong khi lưu vẫn bị bản save cũ ghi đè do không tăng `editGeneration`. | Tăng `editGeneration++` đồng bộ cho cả `undo()` và `redo()`; commit cũ không ghi đè trạng thái RAM mới. | **PASS** |
| **N03** | P1 | Hủy autosave sau commit làm revision trong RAM bị cũ, lần lưu sau bị Conflict vĩnh viễn. | Theo dõi `hasUnacknowledgedCommitInFlight` khi coroutine bị hủy trong lúc I/O; tự động hòa giải (reconcile) revision với đĩa khi gặp Conflict cục bộ mà không phá vỡ CAS đối với tác nhân ngoài. | **PASS** |
| **N04** | P1 | Ghép bố cục làm dính chữ giữa các dòng do `runs` thiếu phân cách dòng `\n`. | Chuẩn hóa `runs` trong `enrichPageWithLayoutAndTables` khớp chính xác với `aPara.text`; chèn `<w:br/>` chuẩn OpenXML trong `DocxWriter`. | **PASS** |
| **N05** | P2 | Gõ thêm chữ xóa định dạng; Undo không khôi phục được định dạng font/style cũ. | Lưu snapshot rich content (`oldEditedContent`/`newEditedContent`) trong `ReplacePageText`; khôi phục nguyên vẹn khi undo; bảo lưu style run hiện tại khi gõ tiếp. | **PASS** |
| **N06** | P2 | Xóa hàng/cột cắt qua ô gộp tạo bảng không hợp lệ (out of bounds). | Cập nhật cấu trúc topology lưới: giảm `rowSpan`/`colSpan` cho các ô bị cắt qua; kiểm tra `validateGrid()` hợp lệ trước lưu/xuất. | **PASS** |
| **N07** | P1 | Nhận diện lại có thể ghi đè bản sửa đã autosave mà không hỏi người dùng. | Tách cờ `hasUserEdits` bền vững trong ViewModel/Activity; flush pending edit và cảnh báo xác nhận bắt buộc trước khi chạy lại OCR. | **PASS** |
| **N08** | P2 | Xóa sạch tài liệu nhưng Copy/Share vẫn gửi nội dung cũ do chỉ cập nhật khi text khác rỗng. | Luôn đồng bộ `extractedText` và `tvOcrContent.text` kể cả khi chuỗi rỗng; flush editor và kiểm tra rỗng trước khi Copy/Share. | **PASS** |
| **N09** | P1 | Khôi phục màn hình ở trang 2 có thể flush editor rỗng vào trang 1. | Khởi tạo `currentObservedPageIndex = -1`; theo dõi `isUserTextDirty`; chỉ flush khi người dùng thực sự có chỉnh sửa chưa lưu; không flush ở lần bind đầu tiên. | **PASS** |

---

## 3. Báo Cáo Triển Khai Hoàn Thiện Các Gói Mở Rộng (G1 – G7)

| Gói | Thành phần | Mô tả công việc đã hoàn thành | Kết quả nghiệm thu |
|:---:|---|---|:---:|
| **G1** | I/O & Async Start | Loại bỏ `runBlocking` khỏi các overload khởi tạo; kiểm soát an toàn ngoại lệ I/O lưu/nhập tài liệu; không mở ID chưa lưu trên đĩa. | **ĐẠT** |
| **G2** | Export Save Error | Trong `prepareDocumentForExport()`, kiểm tra nghiêm ngặt kết quả `flushPendingSaves()` và xuất snapshot nhất quán; log cảnh báo chi tiết nếu có lỗi đĩa. | **ĐẠT** |
| **G3** | ViewModel Protection | Bảo vệ `initialize`/`loadDocument` chống nạp đè tài liệu khi Activity tái tạo; bảo lưu nguyên vẹn ngăn xếp Undo/Redo và bản nháp RAM chưa commit. | **ĐẠT** |
| **G4** | Multi-Table & Selection UI | Hỗ trợ duyệt qua nhiều bảng trên cùng một trang (`layout_table_selector` với nút Trước/Sau, hiển thị "Bảng X / Y"); hỗ trợ chọn nhiều ô bằng thao tác chạm/nhấn giữ (`selectedCellIds`) và nút "Trộn ô" trên toolbar; tích hợp trộn ô liền kề (phải/dưới) trực tiếp trong hộp thoại sửa ô. | **ĐẠT** |
| **G5** | Memory & Byte Budget | Nâng cấp `BoundedMemoryCache` hỗ trợ đồng thời cả giới hạn số phần tử (`maxEntries = 3`) và giới hạn bộ nhớ theo byte (`maxSizeBytes = 24 MB`); hàm tính toán byte an toàn (`calculateBitmapBytes`) tương thích cả môi trường JVM unit test và Android Runtime, tự động giải phóng (recycle) bitmap cũ để ngăn chặn OOM. | **ĐẠT** (Unit test chứng minh) |
| **G6** | Localization & OOXML | Bản địa hóa 100% các chuỗi ký tự bảng biểu tiếng Việt cứng vào `strings.xml` và `values-vi/strings.xml`; hoàn thiện định dạng OpenXML (.docx) chuẩn hóa bảng, ngắt trang và phân dòng. | **ĐẠT** |
| **G7** | Suite Verification & Audit | Kiểm thử hồi quy toàn bộ hệ thống (387 tests PASS 100%), build release APK/AAB thành công với lint 0 lỗi; đo lường delta size đạt mức tối ưu tuyệt đối (+140 KB / trần 10 MB). | **ĐẠT** |

---

## 4. Tình Trạng Bộ Kiểm Thử & Môi Trường Xác Nhận

- **Kiểm thử đơn vị tự động (Unit Tests):** **387/387 tests PASS (100%)**, 0 failures, 0 errors, 0 skipped.
  - Gồm 372 tests tính năng cốt lõi.
  - 6 tests audit độc lập vòng 1 (`OcrIndependentAuditTest.kt`).
  - 7 tests kiểm định mở rộng vòng 2 (`OcrSecondReauditPermanentTest.kt` và `OcrSecondReauditTest.kt`).
  - 2 tests kiểm định bộ đệm bộ nhớ theo byte và thao tác bảng (`OcrReaderViewModelTest.kt`).
- **Lệnh thực thi xác nhận:**
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain
  .\gradlew.bat -I build/ocr-reaudit2/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.OcrSecondReauditTest --offline --console=plain
  ```
- **Kiểm tra Lint:** `.\gradlew.bat :app:lintDebug --offline --console=plain` hoàn tất: **0 error**.
- **Build Release:** `.\gradlew.bat :app:assembleRelease :app:bundleRelease --offline --console=plain` thành công (0 error, lint-vital pass).
- **Size Gate:** Delta APK **+144.009 bytes** (< 10 MB budget).

### Giới hạn xác minh thực tế (Chưa thực thi / NOT RUN):
Theo đúng nguyên tắc trung thực kỹ thuật và kết luận đợt kiểm định:
1. **Thiết bị thật (Physical Device / adb devices):** **NOT RUN** (môi trường hiện tại không kết nối thiết bị Android thật để kiểm thử trực quan IME bàn phím tiếng Việt gõ có dấu, chạm vuốt ROI, và process death thực tế).
2. **Microsoft Word / Office Desktop thực tế:** **NOT RUN** (mở file .docx xuất ra bằng ứng dụng Microsoft Office desktop thật để kiểm tra độ render ô gộp dọc/ngang chưa được thực thi trên môi trường này; kiểm thử hiện tại dựa trên xác thực OpenXML zip XML parser).
3. **Corpus OCR ảnh chụp thực tế:** **NOT RUN** (kiểm định chất lượng nhận dạng trên tập ảnh quét đa ngôn ngữ thực tế không được thay thế bằng fixture JSON).
4. **Đo tải download thiết bị qua Bundletool:** **NOT RUN** (chưa cài đặt `bundletool` trong path hệ thống).
