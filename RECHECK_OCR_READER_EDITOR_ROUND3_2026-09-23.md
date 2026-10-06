# Kiểm tra OCR reader/editor — vòng 3, 23/09/2026

## Kết luận

Chưa thể nghiệm thu toàn phần. Bộ 387 JVM test hiện có đã pass, nhưng 6 ca mở rộng của vòng này đều thất bại. Không sửa mã ứng dụng; chỉ bổ sung probe tạm trong `build/ocr-reaudit3/` và báo cáo này. Working tree có nhiều thay đổi từ trước, được giữ nguyên.

## Kết quả chạy

| Kiểm tra | Kết quả |
|---|---|
| Bộ JVM hiện có, chạy lại | 387 tests, 0 failures, 0 errors |
| Probe vòng 3, chạy riêng | 6 tests, 6 assertion failures, 0 errors |
| assembleDebug | Thành công |
| lintDebug | 0 errors, 720 warnings; không coi đây là lint sạch |
| assembleRelease / bundleRelease | Thành công |
| Android thực tế | Không có thiết bị trong `adb devices` |

Lệnh tái hiện probe (PowerShell, dùng cache Gradle hiện có):

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat -I build/ocr-reaudit3/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.OcrThirdReauditTest :app:assembleDebug :app:lintDebug --continue --offline --console=plain
```

Nguồn probe: `build/ocr-reaudit3/tests/com/tscanner/app/OcrThirdReauditTest.kt`. Kết quả lưu riêng: `build/ocr-reaudit3/results.xml`. Các file trong build có thể mất khi clean; cần chuyển probe thành regression test khi thực hiện sửa. Lệnh trên có exit code thất bại do test, không phải do compile hoặc lint. 387 test và 6 probe là hai lượt chạy riêng.

## Các lỗi còn lại, theo thứ tự sửa

### T01 — P1: Hòa giải save bị hủy có thể ghi đè writer khác

- Vị trí: `app/src/main/java/com/tscanner/app/ui/ocr/reader/OcrReaderViewModel.kt:330-337`.
- Khi commit đã ghi xuống disk nhưng coroutine bị hủy trước khi nhận kết quả, cờ `hasUnacknowledgedCommitInFlight` được bật. Lần save kế tiếp bị conflict thì code chỉ kiểm tra revision đọc lại bằng revision trong kết quả conflict, rồi dùng revision đó để ghi lại draft hiện tại. Điều kiện này không chứng minh commit trên disk là commit của chính tác vụ bị hủy.
- Probe `cancelReconcileMustNotOverwriteAnotherCommittedWriter`: chặn sau commit, hủy autosave, cho writer khác commit, rồi flush draft cũ. Mong đợi giữ conflict; thực tế flush trả true. Đây là interleaving có kiểm soát với repository và ViewModel thật trên JVM; chưa phải tái hiện UI trên điện thoại.
- Sửa: đối chiếu token/nội dung của đúng commit chưa được xác nhận; không tự động chấp nhận revision bất kỳ. Giữ conflict nếu một writer khác đã commit.
- Nghiệm thu: ca trên trả conflict và disk giữ nguyên nội dung writer khác; ca hủy sau chính commit của mình vẫn phục hồi được.

### T02 — P1: Nhận diện lại thành công nhưng reader/export vẫn giữ bản cũ

- Vị trí: `OcrReaderViewModel.kt:222-225`; caller `app/src/main/java/com/tscanner/app/ui/ocr/OcrResultActivity.kt:865` và `:950`.
- `initialize` return ngay nếu document ID đã có, kể cả `initialDoc` truyền vào là revision mới. Hai nhánh nhận diện lại vẫn gọi chính hàm này sau commit.
- Probe `rerecognitionCommitMustReplaceSameDocumentRevision`: initialize revision 2, sau đó revision 3 cùng ID với text mới; export snapshot vẫn là text cũ.
- Sửa: tách khởi tạo khi mở màn hình khỏi áp dụng kết quả OCR mới đã commit; cập nhật document, history, revision và trạng thái save nhất quán, chặn callback cũ.
- Nghiệm thu: reader, editor và export dùng revision mới; xoay màn hình không reset draft; thao tác undo không phục hồi history thuộc kết quả OCR đã bị thay thế ngoài ý muốn.

### T03 — P1: Ảnh vượt byte budget bị recycle trước khi hiển thị

- Vị trí: `OcrReaderViewModel.kt:128`, `:199-202`, `:727-729`, `:757-759`.
- Cache giới hạn 24 MiB, nhưng decoder chỉ giảm ảnh khi một chiều vượt 4096. Ảnh ARGB8888 4000×3000 cần khoảng 48 MB. `bitmapCache.put` tự loại entry quá lớn; callback thấy nó chưa là current bitmap nên recycle, sau đó loader mới gán bitmap đó để hiển thị.
- Probe `oversizedDecodedImageMustNotBeDisposedBeforeDisplay` dùng lớp `BoundedMemoryCache` production và đối tượng mô phỏng ownership theo đúng thứ tự loader. Probe thất bại; đây là bằng chứng JVM về cache/ownership kết hợp đọc code, chưa xác nhận Bitmap/Canvas thực tế. View kiểm tra `isRecycled` nên có nguy cơ trang trắng.
- Sửa: làm rõ ownership ảnh đang hiển thị và ảnh trong cache; không recycle ảnh chuẩn bị publish. Cân nhắc decode theo byte budget chứ không chỉ chiều dài.
- Nghiệm thu: probe pass; mở/chuyển/zoom trang 12 MP trên Android không trắng, không dùng bitmap đã recycle, cache vẫn giữ trần đã định.

### T04 — P1: Mở lại tài liệu làm mất dấu đã chỉnh sửa

- Vị trí: `OcrReaderViewModel.kt:155`, `:222`, `:268`; `OcrResultActivity.kt:700-707`.
- `hasUserEdits` bắt đầu false và không được phục hồi từ bản lưu. Bundle chỉ giúp một số trường hợp phục hồi Activity, không bảo vệ khi mở mới tài liệu đã chỉnh sửa. Nhận diện lại dựa trên cờ này và dirty để hỏi xác nhận.
- Probe `savedEditsMustRemainProtectedAfterReopen`: chỉnh sửa, flush thành công, load lại từ repository, tạo ViewModel mới; cờ vẫn false. Mất cờ được xác nhận bằng test; hậu quả bỏ qua cảnh báo trên UI được suy ra từ nhánh code.
- Sửa: lưu provenance đã chỉnh sửa trong model/repository; không suy ra đơn giản từ `editedContent` vì analyzer cũng tạo nội dung đó.
- Nghiệm thu: đóng/mở mới hoặc process recreation vẫn yêu cầu xác nhận trước khi thay nội dung người dùng đã sửa; bản OCR chưa sửa không hỏi thừa.

### T05 — P2: Gõ thêm ký tự làm mất định dạng hỗn hợp

- Vị trí: `app/src/main/java/com/tscanner/app/ocr/edit/OcrEditReducer.kt:55-62`.
- Bản sửa chỉ bảo toàn đoạn có một run hoặc text không đổi. Đoạn có nhiều run, ví dụ “Bold plain” với chữ Bold in đậm, bị tạo lại thành một run mặc định khi thêm `!`.
- Probe `appendingTextMustPreserveMixedFormatting` thất bại với reducer/history production.
- Sửa: bảo toàn run ở phần text không đổi và cập nhật run tại vùng edit; xác định quy tắc style cho text mới.
- Nghiệm thu: append/insert/delete qua ranh giới run không xóa style ở vùng không bị tác động; undo và DOCX giữ kết quả đúng.

### T06 — P2: Undo xóa hàng không phục hồi ô gộp

- Vị trí: `app/src/main/java/com/tscanner/app/ocr/edit/OcrEditCommand.kt:145-159`; `OcrEditReducer.kt:182`, `:219`.
- Delete đã giảm rowSpan của ô gộp đi qua hàng bị xóa, nhưng inverse chỉ AddTableRow với deletedCells. Span của ô bắt đầu ở hàng trước không nằm trong dữ liệu phục hồi.
- Probe `undoDeleteRowMustRestoreMergedSpan`: ô gộp 2 hàng, xóa hàng dưới rồi undo; rowSpan vẫn 1 thay vì 2.
- Sửa: lưu đầy đủ before/after table hoặc delta chứa cả những ô bị thay đổi span.
- Nghiệm thu: undo/redo phục hồi chính xác cấu trúc và nội dung; mở rộng tương tự cho cột và xóa hàng chứa origin của ô gộp.

## Đối chiếu bản sửa trước

13 probe từ hai vòng trước đã được đưa vào bộ test hiện có và pass. Code hiện đã chuẩn hóa pageIndex của trang OCR lại, tăng generation khi undo/redo, thêm ngắt dòng DOCX, bảo vệ nội dung editor khi observer nhận trạng thái khởi tạo, và xử lý span khi delete. Tuy nhiên bảo toàn style mới bao phủ single-run, cờ user-edit mới có hiệu lực trong phiên, còn reconcile commit bị hủy và byte-bounded cache phát sinh các nhánh lỗi nêu trên. Passing các ca trước không đủ để đóng toàn bộ hạng mục.

## Dung lượng

Đo bằng `scripts/measure_ocr_size.ps1 -Json`, giữ nguyên baseline sẵn có:

| Artifact | Baseline (bytes) | Hiện tại (bytes) | Tăng |
|---|---:|---:|---:|
| Release APK | 75,347,472 | 75,491,481 | 144,009 bytes ≈ 0.144 MB |
| Release AAB | 44,641,170 | 44,788,839 | 147,669 bytes ≈ 0.148 MB |

Cả hai delta artifact đều dưới 10,000,000 bytes. APK SHA256: `6552899A003309F732C60A0B235D43CB489D7C52602EFC9E79477020F4D232D3`; AAB SHA256: `94D55A096E58C94B919285C82491DB5DAB7E22B6E4820CEAB796F1FA023111CF`.

Chưa có bundletool để đo download theo thiết bị; AAB size không phải download size. Baseline sẵn có chưa đủ metadata hash working tree để độc lập chứng minh tương ứng chính xác bản ứng dụng trước tính năng. Vì vậy đây là PASS so sánh artifact đã lưu, chưa là nghiệm thu đầy đủ giới hạn download/cài đặt.

## Giới hạn và bước tiếp theo

Sửa T01–T04 trước, sau đó T05–T06; chuyển từng probe thành regression test tương ứng. Không cần thêm thư viện Office nặng để xử lý các lỗi này. Sau khi test pass, vẫn cần Android thật/emulator cho ảnh lớn, lifecycle, và so sánh ảnh quét với reader; kiểm tra DOCX/XLSX bằng ứng dụng Office thực tế. Chưa có bằng chứng đủ để khẳng định hiển thị giống ảnh quét hoặc toàn bộ luồng an toàn trên thiết bị.
