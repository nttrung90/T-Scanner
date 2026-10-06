# Kiểm tra lỗi còn lại và kế hoạch giao cho AI nhỏ — 16/09/2026

## Phạm vi và kết luận

Rà soát module Android `:app` đang được khai báo trong settings.gradle, tập trung Editor → xuất ảnh → Viewer → lưu PDF, bản nháp, bộ nhớ và backup. Đã đọc cả thay đổi chưa commit liên quan hộp thoại ID card. Các thư mục mẫu android/, ios/, tutorials/ không thuộc build này. Không khẳng định đã kiểm tra mọi tính năng, OCR hay mọi thiết bị.

**Còn 9 nhóm lỗi có bằng chứng trong mã: 6 P1 và 3 P2.** Đây là phân tích đường chạy, chưa tái hiện trên điện thoại. P1 liên quan nguy cơ mất dữ liệu, sai nội dung hoặc crash; P2 liên quan phục hồi và trạng thái không chính xác. Chỉ viết kế hoạch, không sửa production code và không giao AI khác chạy sửa tự động.

Không dùng nguyên trạng danh sách lỗi hôm trước: hiện đã có PageGeometry, token render, Mutex/revision cho draft, SafeFileWriter, kết quả export có kiểu và giới hạn kích thước preview PDF. Tuy nhiên một số sửa đổi chưa bao phủ nhánh thất bại.

## Các gói công việc

Đường dẫn bên dưới tính từ thư mục dự án; số dòng là tại thời điểm kiểm tra. AI thực hiện phải tìm lại tên hàm trước khi sửa.

### T01 — P1 — Fallback ghi file có thể xóa bản tốt

- **Bằng chứng:** `app/src/main/java/com/tscanner/app/utils/SafeFileWriter.kt:87–112`, `commitAtomic()`. Khi Files.move lỗi, catch gọi destination.delete() rồi renameTo(); nếu rename thất bại thì bản cũ đã mất. Nhánh move không atomic cũng không đảm bảo lời hứa bảo toàn bản cũ.
- **Tái hiện:** file đích chứa dữ liệu A, writer tạo B; ép commit move lỗi và rename thất bại.
- **Phạm vi sửa:** SafeFileWriter và SafeFileWriterTest. Tách thao tác commit thành dependency nhỏ có thể giả lập lỗi. Ưu tiên fail an toàn và giữ bản cũ khi không thể atomic replace; không xóa đích để thử vận may. Nếu dùng backup/restore phải có quy tắc phục hồi khi tiến trình dừng.
- **Nghiệm thu:** writer lỗi, validator lỗi, atomic move không hỗ trợ, commit IOException đều giữ nguyên byte của A; thành công mới thay bằng B. Test hiện tại chỉ kiểm tra lỗi trước commit nên chưa bắt lỗi này.

### T02 — P1 — Đổi trang rồi kéo slider có thể render ảnh trang trước

- **Bằng chứng:** `ui/editor/viewmodel/PostScanEditorViewModel.kt:141–174,291–316`. loadCurrentPage giữ bitmap A trong lúc decode B; schedulePreviewRender tạo token B nhưng lấy activeBaseBitmap không kiểm tra activeBaseBitmapPath. Token mới làm kết quả decode B bị bỏ qua.
- **Tái hiện:** mở A, chọn B có decode chậm, đổi filter hoặc slider trước khi B tải xong. Bitmap A được xử lý với state B và có thể ở lại trên màn hình.
- **Phạm vi sửa:** ViewModel; mỗi bitmap cache phải đi cùng khóa nguồn. Chỉ render khi khóa khớp token; nếu không, tiếp tục/tạo load đúng B. Reset preview/compare khi chuyển trang hoặc gắn khóa cho cả hai. Không recycle bitmap đang được UI hay job giữ.
- **Nghiệm thu:** test điều khiển thứ tự decode A/B và thao tác slider; ảnh cuối phải là B với state mới nhất. Kiểm tra nút so sánh và decode thất bại; loading phải kết thúc rõ ràng.

### T03 — P1 — Ngân sách full-resolution thấp hơn cấp phát thực tế

- **Bằng chứng:** `utils/ImageProcessingEngine.kt:316–321,394–421`; `utils/ImageProcessingAlgorithms.kt:278–279`. Ước lượng chỉ 12 byte/pixel. Đen trắng đồng thời cần bitmap (~4), pixels (4), lum (4), integral (~8): khoảng 20 byte/pixel chưa tính phần phụ. Với 12MP: 144 MB ước lượng so với khoảng 240 MB ở nhánh này; đây là tính toán, chưa phải đo thiết bị.
- **Tái hiện:** ảnh lớn, BLACK_AND_WHITE, heap khả dụng đủ vượt kiểm tra 12 byte/pixel + 48 MiB nhưng không đủ cấp phát thực tế.
- **Phạm vi sửa bước nhỏ:** tách hàm ước lượng theo pipeline/state, dùng Long và kiểm tra overflow; từ chối trước cấp phát nếu thiếu tài nguyên, trả lý do riêng để UI không chỉ yêu cầu thử lại vô ích. Chặn nhiều export nặng đồng thời. Không dùng catch OOM/largeHeap làm giải pháp.
- **Bước tiếp theo riêng:** nếu cần giữ full-res trên máy ít RAM, triển khai thuật toán theo dải có biên; không gom thay đổi thuật toán này vào bản vá ước lượng.
- **Nghiệm thu:** unit test công thức cho original/rotate/sharpen/black-white; đo trên thiết bị với 12/24/48MP. Ảnh không đủ ngân sách phải bị từ chối có kiểm soát, draft còn nguyên. Chưa đo thiết bị thì chưa đóng phần nghiệm thu RAM.

### T04 — P1 — Khởi tạo draft có thể báo thành công dù không lưu được

- **Bằng chứng:** `data/repository/PostScanSessionRepository.kt:77–118`: bỏ qua renameTo(), fallback về sourcePath khi copy lỗi và bỏ qua Boolean saveDraft(); ViewModel.initialize vẫn đặt SAVED.
- **Tái hiện:** giả lập lỗi copy, rename hoặc metadata write; nguồn là file tạm. UI vẫn cho chỉnh sửa, nhưng xóa cache/khởi động lại có thể mất nguồn hoặc metadata.
- **Phạm vi sửa:** initializeSession trả Success/Failure có nguyên nhân; tất cả trang phải được copy và xác minh vào vùng bền vững, metadata phải commit trước Success. Dọn riêng dữ liệu khởi tạo dở; giữ nguồn để thử lại. ViewModel hiển thị lỗi, không đặt SAVED hoặc cho export khi init lỗi.
- **Nghiệm thu:** lỗi trang thứ hai, rename=false, metadata write=false đều không tạo phiên thành công; đường bình thường đủ trang đúng thứ tự và phục hồi được sau restart.

### T05 — P1 — Export bỏ qua kết quả flush và không giữ vòng đời tác vụ

- **Bằng chứng:** `data/repository/PostScanSessionRepository.kt:193`: flush chỉ đọc revision hiện tại dưới lock, không tự chờ các job chưa lấy lock. `ui/editor/viewmodel/PostScanEditorViewModel.kt:400–407` bỏ qua kết quả flush, đọc snapshot trên IO trong khi state được sửa trên Main. `ui/editor/PostScanEditorActivity.kt:471` chạy export bằng lifecycleScope; chưa có isExporting/job dùng chung hay finally dọn lần xuất hủy.
- **Tái hiện:** saveDraft thất bại hoặc trì hoãn rồi bấm preview: export vẫn tiếp tục và thông báo draft an toàn. Recreate Activity lúc export có thể hủy điều phối, để lại output dở; gọi export hai lần không có bảo vệ ở ViewModel.
- **Phạm vi sửa:** trên Main chụp snapshot bất biến gồm title, revision, pages; lưu chính snapshot và kiểm tra thành công trước export. Một export job trong ViewModel, state tiến độ và kết quả để Activity quan sát; khóa sửa hoặc quy định rõ snapshot. Hủy phải truyền CancellationException, cleanup thư mục riêng bằng finally, chỉ điều hướng một lần. Rà các catch Exception trong đường SafeFileWriter/engine để không nuốt cancellation.
- **Nghiệm thu:** ép persistence thất bại thì không mở Viewer; hai lần bấm chỉ có một job; recreate không xuất trùng; cancel giữ draft, không công bố output dở; title/pages/revision cùng snapshot.

### T06 — P1 — Viewer dọn nguồn dù chưa xác nhận lưu và bàn giao đầy đủ

- **Bằng chứng A:** `data/repository/DocumentRepo.kt:180–230,252`: saveData chỉ log lỗi; addDocument vẫn publish và không trả kết quả. `ui/viewer/PdfViewerActivity.kt:583–603` vẫn hoàn tất/xóa draft. Nếu ghi catalog lỗi, tài liệu có thể biến mất khỏi danh sách sau restart dù PDF còn trên đĩa.
- **Bằng chứng B:** Viewer chỉ thay nguồn khi newPreviewPages không rỗng nhưng completeSession luôn chạy. Khi render preview lỗi ngay trang đầu, adapter còn dùng file trong session đã bị xóa. `utils/PdfConverterHelper.kt:115–163` còn trả danh sách một phần nếu lỗi ở trang giữa; kiểm tra isNotEmpty không đảm bảo đủ trang.
- **Phạm vi sửa, chia hai lượt:** (a) repository trả kết quả persistence, không báo thành công/enqueue backup khi chưa commit; caller giữ draft và cho retry nếu lỗi. (b) convertPdfToImages trả Success đầy đủ/Failure, dọn phần dở; chỉ xóa nguồn khi đã bàn giao đầy đủ. PDF lưu thành công nhưng preview lỗi phải được thể hiện riêng, có retry hoặc mở từ PDF bền vững.
- **Nghiệm thu:** lỗi catalog, render trang 1 và trang 2/3 đều không mất đường phục hồi, không có danh sách trang bị thiếu được coi là hoàn tất. Restart thấy tài liệu đã commit; retry không nhân đôi bản ghi. PDF tốt vẫn tồn tại.

### T07 — P2 — Hủy draft chưa ngăn writer đến muộn; trạng thái SAVED sai revision

- **Bằng chứng:** `data/repository/PostScanSessionRepository.kt:213–223` xóa lock/revision và cả closedSessions ngay sau discard; saveDraft đến sau có thể tạo lại session. `ui/editor/viewmodel/PostScanEditorViewModel.kt:354–361` completion của snapshot cũ vẫn đặt SAVED dù revision mới chưa lưu.
- **Tái hiện:** trì hoãn một save trước khi nó vào repository, discard rồi thả save; hoặc save revision N hoàn tất trong khi N+1 vẫn pending/lỗi.
- **Phạm vi sửa:** giữ tombstone đến khi chắc chắn không còn writer hoặc dùng session generation/actor đóng vĩnh viễn; không tạo lock mới trong khi writer cũ còn giữ lock cũ. Chỉ cập nhật draftStatus nếu completion tương ứng revision đang hiển thị; định nghĩa flush chờ writer rõ ràng, phối hợp T05.
- **Nghiệm thu:** lịch chạy có kiểm soát không tái sinh draft đã hủy; SAVED chỉ khi revision mới nhất bền vững; lỗi xóa không được trả true giả.

### T08 — P2 — Back bỏ qua chỉnh sửa trang khác và không có đường mở lại draft

- **Bằng chứng:** `ui/editor/PostScanEditorActivity.kt:449` chỉ xét currentPageState.isModified, bỏ qua trang khác và đổi tên. Tìm toàn source thấy loadDraft chỉ được gọi từ initialize bằng sessionId; chưa có danh sách tiếp tục draft, cleanOrphanedDrafts không được gọi.
- **Tái hiện:** chỉnh trang 1 → chọn trang 2 nguyên bản → Back; không có hộp thoại. Đóng task rồi mở Home không có luồng tìm lại draft.
- **Phạm vi sửa hai lượt:** (a) ViewModel cung cấp trạng thái dirty toàn tài liệu gồm title; Back hỗ trợ tiếp tục/lưu nháp/hủy và kiểm tra kết quả lưu/hủy. (b) Home có lối tiếp tục draft, repository đọc metadata mà không tạo thư mục khi read; cleanup dựa updatedAt và loại trừ phiên đang dùng.
- **Nghiệm thu:** chỉnh trang khác/đổi tên vẫn được hỏi đúng; lưu nháp rồi đóng/mở app khôi phục đúng nội dung; hủy xóa dữ liệu; cleanup không đụng phiên hoạt động.

### T09 — P2 — Backup cũ có thể đánh dấu revision mới đã đồng bộ

- **Bằng chứng:** `utils/GoogleDriveBackupWorker.kt:98` cập nhật SYNCED chỉ theo docId; `utils/CloudBackupManager.kt:45–58` không truyền revision; DocumentRepo có contentRevision nhưng updateSyncStatus không đối chiếu. REPLACE hủy worker không thay thế được kiểm tra revision khi lời gọi mạng cũ hoàn tất.
- **Tái hiện:** upload revision N bị chậm, sửa tài liệu thành N+1 rồi cho callback N hoàn tất. N+1 có thể bị đánh dấu SYNCED khi cloud vẫn là N.
- **Phạm vi sửa:** snapshot file upload và revision/owner; API repository commit sync theo docId + expectedRevision + owner với kiểm tra nguyên tử. Kết quả cũ không được ghi đè trạng thái mới; không upload nếu doc đã xóa. Kiểm tra lại tài khoản tại lúc commit.
- **Nghiệm thu:** fake transport điều khiển thứ tự upload N/N+1, xóa doc, đổi account; chỉ đúng revision/owner được đánh dấu SYNCED. Không cần tài khoản Drive thật để chạy test logic này; smoke test Drive riêng sau đó.

## Thứ tự giao cho AI nhỏ

1. T01 (file an toàn).
2. T02 (đúng ảnh preview).
3. T04 → T07 → T05 (hợp đồng draft và export, cùng các file nên làm tuần tự).
4. T06a → T06b (catalog rồi bàn giao Viewer).
5. T03 bước ước lượng; phần thuật toán theo dải thành task riêng nếu cần.
6. T08a → T08b, rồi T09.

Mỗi lượt chỉ một gói hoặc một nửa gói ghi rõ ở trên. Không yêu cầu AI nhỏ sửa toàn bộ trong một prompt. Không tự thay thư viện, thiết kế lại UI hay dịch hàng loạt strings trong các bản vá này. Giữ nguyên các thay đổi người dùng đang có ở HomeFragment, ToolsFragment, dialog_id_card_options.xml, IdCardOptionsDialog.kt và AAB; nếu cần sửa cùng file thì chỉ chạm đoạn thuộc task. Không commit binary/build reports.

### Prompt giao việc dùng lại

> Đọc PLAN_REMAINING_BUGS_2026-09-16.md và chỉ thực hiện Txx [hoặc T06a/T06b]. Kiểm tra lại bằng chứng trên mã hiện tại trước khi sửa. Giữ nguyên các thay đổi chưa commit của người dùng. Viết test tái hiện lỗi có ý nghĩa trước hoặc cùng bản vá; test phải thất bại với hành vi cũ, không chỉ kiểm tra helper mới. Làm thay đổi nhỏ nhất giải quyết nguyên nhân. Chạy test liên quan rồi unit test, assembleDebug và lintDebug. Báo file đã sửa, nguyên nhân, kết quả kiểm tra và phần chưa nghiệm thu. Không tự đánh dấu kiểm thử Android/Drive/RAM đạt nếu chưa có thiết bị hay môi trường tương ứng. Không triển khai các gói khác.

## Kiểm tra tại lần rà soát này

- Gradle dùng cache hiện có ở C:/Users/nguye/.gradle; lần đầu bị chặn ghi cache, sau đó chạy lại với quyền phù hợp.
- Lệnh: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`.
- Unit test: 43 test trong 10 lớp, 0 failure, 0 error theo XML của lần chạy này.
- Build debug và lint hoàn tất thành công (2 phút 19 giây; 12 task thực thi, 42 up-to-date). Lint: 0 error, 881 warning; không coi mỗi warning là một lỗi chức năng. Gradle còn cảnh báo API deprecated không tương thích Gradle 10, chưa nâng cấp trong đợt này.
- Chưa chạy release, instrumentation, emulator/điện thoại, đo RAM hoặc tích hợp Drive thực. Unit test JVM không xác nhận vòng đời Android và hoạt động của bitmap thật.
