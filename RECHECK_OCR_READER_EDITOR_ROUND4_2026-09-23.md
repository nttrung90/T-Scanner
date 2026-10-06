# Kiểm tra OCR reader/editor — vòng 4, 23/09/2026

## Kết luận

Chưa nghiệm thu toàn phần. Bản sửa có xử lý cả T01–T06 vòng 3 và bổ sung 8 regression test. Tuy nhiên 5 probe mở rộng đều thất bại. Không sửa production hoặc test chuẩn; chỉ thêm probe độc lập trong `build/ocr-reaudit4/` và báo cáo này. Giữ nguyên các thay đổi có sẵn trong working tree.

## Đối chiếu T01–T06

| Mục vòng 3 | Bản sửa hiện tại | Đánh giá |
|---|---|---|
| T01 hòa giải commit bị hủy | Token riêng + so sánh nội dung, chặn writer khác cả khi text giống nhau | Ca cũ được bao phủ; còn hủy trong retry (R02) |
| T02 OCR lại cùng ID | `applyCommittedRecognitionResult`, reset history, kiểm tra revision/snapshot | Ca trực tiếp được bao phủ; caller và autosave vẫn lệch nhau (R01/R03) |
| T03 bitmap vượt cache | Publish trước cache; tính sample theo byte budget | Ca ownership thuần JVM được bao phủ; cần Android xác nhận |
| T04 dấu chỉnh sửa | Persist `hasUserEdits` trong model/JSON, reducer đặt true, VM khôi phục | Ca lưu/mở lại được bao phủ; manifest cũ thiếu field vẫn mặc định false, chưa có chính sách migration xác định provenance cũ |
| T05 nhiều run | Giữ prefix/suffix và style tại vùng edit trong từng paragraph | Ca một đoạn được bao phủ; thêm newline vẫn mất style (R04) |
| T06 undo xóa ô gộp | History chụp before/after table cho delete row/column | Ca xóa origin, xóa phần span, undo/redo được bao phủ; insert giữa span còn lỗi API (R05) |

## Bằng chứng và giới hạn

- Bộ chuẩn chạy lại sau probe: **395 tests, 0 failures, 0 errors**; riêng `OcrThirdReauditPermanentTest`: **8 tests, 0 failures, 0 errors**.
- `:app:assembleDebug` và `:app:lintDebug` thành công (các tác vụ build/lint có sử dụng kết quả up-to-date); báo cáo lint hiện tại **0 errors, 720 warnings**, không phải lint sạch. Log: `build/ocr-round4-validation.log`.
- Lệnh bộ chuẩn: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`, với GRADLE_USER_HOME như bên dưới. Test chuẩn thực thi lại sau khi bỏ init script probe.
- Lượt đầu sandbox không ghi được wrapper lock trong cache; đã chạy lại được với quyền truy cập cache. Đây không phải lỗi compile ứng dụng.
- `adb devices` chạy được sau khởi động daemon nhưng danh sách trống: **không có thiết bị/emulator để kiểm chứng Android**.
- Probe: `build/ocr-reaudit4/tests/com/tscanner/app/OcrFourthReauditTest.kt`.
- Kết quả riêng: `build/ocr-reaudit4/results.xml`: **5 tests, 5 failures, 0 errors**. Tất cả là assertion failure; không phải lỗi compile.
- Probe sử dụng repository, ViewModel, reducer/history production; không chạy Activity hoặc Bitmap/Canvas thực trên JVM.
- R03 dựng lại thứ tự gọi của Activity bằng repository/VM thật; tác động UI là kết luận đối chiếu code, chưa phải instrumentation.
- R05 là lỗi API/reducer được tái hiện. UI hiện gọi add tại cuối (`OcrTableEditorFragment.kt:100,109`), chưa có đường thao tác UI chèn giữa.
- Không đánh giá lại dung lượng release/download trong vòng này; không dùng số đo vòng trước như kết quả hiện tại.

Lệnh probe (PowerShell):

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat -I build/ocr-reaudit4/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.OcrFourthReauditTest --offline --console=plain
```

Probe trong build có thể mất khi clean. Khi sửa, chuyển từng ca sang test chuẩn; không bật init script probe khi đếm toàn bộ regression test.

## Kế hoạch cho mô hình nhỏ

Thứ tự: **R01 → R02 → R03 → R04 → R05 → R06**. Mỗi lượt chỉ thực hiện một gói; đọc code thực tế, giữ working tree, không đổi thư viện hoặc mở rộng sang Drive/camera. Dừng khi gói đạt nghiệm thu, báo file sửa và test thực chạy. Kế hoạch này không tự cho phép triển khai production trong phiên kiểm tra hiện tại.

### R01 — P1: Đồng bộ metadata đã commit với edit history

**Bằng chứng:** `OcrReaderViewModel.kt:384,413` chỉ gọi `editHistory.markCommitted()`; `OcrEditHistory.kt:123` chỉ đặt dirty=false. `executeCommand` lấy document từ history cũ và gán lại vào live state. Probe `editingAfterSaveMustNotRollBackLiveRevision`: revision đã lưu là 3, sửa thêm ký tự làm live revision về 2. Collector Activity lấy chính revision này tại `OcrResultActivity.kt:377`; các writer OCR lại có thể dùng revision cũ và conflict. Autosave dùng lastPersistedRevision nên không kết luận mọi lần save thường đều hỏng.

**Phạm vi:** `OcrEditHistory.kt`, `OcrReaderViewModel.kt`, test history/VM.

1. Chuyển probe thành regression test; thêm assertion revision/token của export snapshot sau save.
2. Thiết kế hàm acknowledge commit cập nhật metadata của current history mà không xóa undo/redo. Nếu có edit mới khi save chạy, giữ nội dung mới và dirty, chỉ cập nhật metadata hợp lệ.
3. Dùng cùng cơ chế ở success thường và success retry. Không reset history để chữa revision.
4. Kiểm tra edit-save-edit, undo/redo sau save, save đang chạy có edit mới, export snapshot.

**Nghiệm thu:** live/history/export giữ revision mới nhất đã được acknowledge; không mất nội dung edit sau snapshot; undo/redo còn hoạt động; test cũ pass.

**Prompt:** Đọc `RECHECK_OCR_READER_EDITOR_ROUND4_2026-09-23.md`, chỉ làm R01 khi được giao triển khai. Bắt đầu từ probe rollback revision, sửa đồng bộ metadata commit nhưng giữ history và edit mới. Chạy test history/VM và báo kết quả; dừng trước R02.

### R02 — P1: Theo dõi cả commit trong nhánh retry khi bị hủy

**Bằng chứng:** `OcrReaderViewModel.kt:375` đặt inFlight=false sau save đầu; `:406` xóa pending; `:407` retry không truyền token đã theo dõi và không bật inFlight. Nếu retry ghi disk rồi bị hủy, catch tại `:437` không lưu pending đúng. Probe `retryCommitCancellationMustRemainRecoverable` hủy sau commit đầu, hủy tiếp sau commit retry, rồi flush draft thứ ba: trả false, không phục hồi save của chính mình.

**Phạm vi:** save/reconcile của `OcrReaderViewModel.kt`, test autosave; tận dụng fault hook repository hiện có.

1. Đưa probe vào regression test, giữ timeout hữu hạn và barrier để tránh test dựa vào timing.
2. Tách một thao tác commit có token, snapshot và trạng thái in-flight dùng chung cho lần đầu/retry; giữ pending của đúng lần chưa acknowledge.
3. Reconcile phải kiểm tra token + nội dung + CAS; không nới điều kiện để bỏ qua conflict writer khác.
4. Thử hủy trước commit, sau commit, trong retry và hai lần liên tiếp; thêm writer khác sau lần retry bị hủy.

**Nghiệm thu:** draft thứ ba flush được khi disk chỉ có commit của chính VM; nếu writer khác đã commit phải báo conflict, không ghi đè; các test T01 cũ vẫn pass.

**Prompt:** Đọc báo cáo vòng 4, chỉ thực hiện R02 sau R01. Dùng probe hủy hai commit liên tiếp, sửa tracking token ở mọi lần ghi. Không bỏ CAS hoặc tự chấp nhận revision lạ. Chạy regression autosave và dừng.

### R03 — P1: Hợp nhất autosave và nhận diện lại thành giao dịch nhất quán

**Bằng chứng:** `OcrResultActivity.kt:720-721` capture base trước OCR; `:858,945` save bằng `currentRevision` có thể thay đổi qua collector; `:862,949` apply bằng base cũ. Khi apply=false vẫn gán currentDocument/currentRevision và báo thành công. Probe `autosaveDuringRecognitionMustNotLeaveDiskAndReaderDivergent`: dirty draft được autosave trong lúc OCR chạy, OCR commit thành công theo revision mới, VM từ chối base cũ; disk="new OCR", reader="user draft". Có thể gặp ngay sau xác nhận thay bản đang sửa khi autosave chưa xong.

**Phạm vi:** hai nhánh OCR lại trong Activity, API điều phối của ViewModel, test integration bằng repository thật. Phụ thuộc R01/R02.

1. Đưa probe vào test chuẩn; thêm ca một trang/nhiều trang, save conflict và callback cũ.
2. Flush editor và hoàn tất/acknowledge pending save trước khi capture base; nếu flush thất bại thì dừng OCR thay thế và giữ draft.
3. Dùng base revision/document identity/edit generation cố định, kiểm tra hợp lệ trước commit. Điều phối autosave và commit OCR dưới cơ chế serialization thống nhất; tránh chỉ kiểm tra sau khi đã thay disk.
4. Chỉ publish metadata, text file phụ và thông báo thành công khi commit và reader được cập nhật nhất quán. Nhánh stale/conflict phải có kết quả rõ, không chỉ Log rồi tiếp tục như thành công.
5. Kiểm tra cancellation/recreation, nhận diện lại khi dirty hoặc ngay sau undo, callback cũ sau đổi tài liệu. Không khóa mất nội dung người dùng vừa sửa trong lúc OCR chạy.

**Nghiệm thu:** success thì disk/reader/editor/export cùng kết quả mới; stale/conflict không làm disk bị thay âm thầm hoặc báo thành công giả; rotation không reset draft; history cũ không quay lại kết quả đã thay.

**Prompt:** Đọc báo cáo vòng 4, chỉ làm R03 sau R01/R02. Sửa toàn bộ giao dịch OCR lại của cả hai nhánh, không chỉ nới guard applyCommittedRecognitionResult. Tái hiện autosave xen giữa, giữ CAS và bảo vệ edit mới, chạy integration test rồi dừng.

### R04 — P2: Bảo toàn style khi thêm/xóa newline

**Bằng chứng:** `OcrEditReducer.kt:54-63` ghép paragraph cũ/mới theo chỉ số. Probe `insertingNewlineMustPreserveStyleOfMovedParagraph`: "head\nBold" (Bold in đậm) → "head\n\nBold"; paragraph Bold chuyển từ index 1 sang 2 mất đậm dù nội dung không đổi. Alignment và identity cũng có nguy cơ gán sang đoạn khác theo cùng thuật toán; chưa thêm probe riêng cho hai thuộc tính đó.

**Phạm vi:** ReplacePageText reducer, history snapshot và test DOCX; không đổi engine OCR.

1. Giữ probe newline làm regression; bổ sung split paragraph giữa run, join hai paragraph và chèn dòng đầu.
2. Ánh xạ vùng text thay đổi trên toàn trang hoặc nhận edit range từ editor; phân phối lại run/paragraph theo vị trí ký tự, không dựa riêng vào line index.
3. Quy định style/alignment cho đoạn mới, giữ style vùng không bị sửa và ID hợp lệ không trùng.
4. Thử undo/redo và kiểm tra XML DOCX của đoạn dịch chuyển.

**Nghiệm thu:** xuống dòng, nối dòng, xóa qua ranh giới đoạn không làm mất style của phần còn nguyên; undo phục hồi đúng; DOCX giữ đậm/nghiêng/gạch chân/alignment tương ứng.

**Prompt:** Đọc báo cáo vòng 4, chỉ làm R04. Bắt đầu bằng test thêm dòng trống trước paragraph in đậm; sửa mapping paragraph/run xuyên newline, giữ các ca single-paragraph đã pass. Kiểm tra undo và DOCX rồi dừng.

### R05 — P2, lỗi API tiềm ẩn: Chèn giữa ô gộp tạo overlap/hole

**Bằng chứng:** `OcrEditReducer.kt:249,329` chỉ dịch origin phía sau và thêm đủ ô mới, không xử lý span cắt qua vị trí insert. Probe `insertingRowInsideMergedCellMustNotOverlap`: bảng 2×1, một ô rowSpan=2; chèn tại row=1 → vị trí row=1 có 2 owner thay vì 1, row cuối không được phủ. Nhánh cột có thuật toán tương tự nhưng chưa chạy probe riêng. UI hiện chỉ append cuối, nên không trình bày như crash UI đã tái hiện.

**Phạm vi:** reducer add row/column, history/inverse nếu cần, test cấu trúc bảng.

1. Chọn contract: hỗ trợ insert giữa hoặc reject rõ vị trí này. Nếu chỉ hỗ trợ append, validate tại API và bảo đảm invalid command không làm dirty/history giả.
2. Nếu hỗ trợ insert: mở rộng span đi xuyên hàng/cột mới, chỉ tạo ô ở vùng chưa được phủ; không tạo overlap/hole.
3. Chụp trạng thái đủ cho undo/redo insert, tương tự cách delete đã sửa.
4. Test row/column đối xứng, insert tại origin/giữa/cuối span và append thường; gọi validateGrid sau mỗi thao tác.

**Nghiệm thu:** bảng hợp lệ hoặc command bị từ chối không đổi state; mỗi tọa độ đúng một owner; undo/redo phục hồi chính xác; append UI vẫn chạy.

**Prompt:** Đọc báo cáo vòng 4, chỉ làm R05. Xác định contract insert tại API hiện tại, sửa overlap/hole bằng validation hoặc hỗ trợ span đầy đủ, có row/column và undo/redo test. Không thêm UI chèn giữa nếu chưa được yêu cầu.

### R06 — Nghiệm thu tích hợp sau sửa

1. Chạy lại tất cả probe đã chuyển thành regression và toàn bộ JVM suite, assembleDebug, lintDebug; ghi số test thực tế, failures/errors/warnings.
2. Android thật/emulator: mở ảnh 12 MP, chuyển trang nhanh, zoom, xoay màn hình, background/foreground, process recreation, nhận diện lại khi vừa edit; kiểm tra reader/editor/export đồng nhất và không bitmap recycled/trang trắng.
3. Đóng/mở tài liệu đã sửa, kiểm tra cảnh báo thay thế; quyết định chính sách dữ liệu cũ thiếu hasUserEdits thay vì mặc định xem provenance cũ đã được xác minh.
4. Mở DOCX/XLSX bằng Office thực, thử bảng gộp và paragraph có format sau newline.
5. Nếu vẫn áp dụng giới hạn 10 MB, build và đo lại artifact với baseline có metadata; không thay phép đo download bằng kích thước AAB.

**Điểm dừng:** ghi PASS/FAIL/CHƯA CHẠY riêng; không tuyên bố nghiệm thu Android dựa trên JVM.

**Prompt:** Đọc báo cáo vòng 4, chỉ làm R06 sau khi R01–R05 hoàn tất. Chạy kiểm chứng độc lập và báo bằng chứng; không tự sửa production khi test thất bại, trả lại đúng gói phụ trách.
