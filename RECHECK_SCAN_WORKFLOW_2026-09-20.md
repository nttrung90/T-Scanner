# Kiểm tra lại sau sửa luồng quét

Ngày: 20/09/2026. Đọc code hiện tại và chạy kiểm tra tự động; không sửa code sản phẩm. Các lỗi dưới đây có đường thực thi cụ thể, nhưng chưa tái hiện camera/IO/lifecycle trên thiết bị.

## Kết quả

- Gradle `:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain`: BUILD SUCCESSFUL.
- 182 JVM tests, 0 failures/errors. Lint: 0 errors, 710 warnings.
- Localization validator PASS, 641 strings + 7 plurals; 12/12 unittest validator PASS.
- `adb devices`: không có thiết bị. Chưa kiểm chứng ảnh thật, process death, IO failure trên Android hoặc release AAB.

## Những bản sửa đã có hiệu lực trong code

- S01: crop dùng SafeFileWriter, kiểm tra nén/validate trước commit.
- S02: scanner kiểm tra trang nhập lỗi trước filterNotNull, không còn trả success với danh sách thiếu trang theo đường đã báo.
- S03: render PDF có result cấu trúc; failure không trả tập trang đầu như success.
- S04: Done khóa shutter và chờ cả capture đang chạy/crop job.
- S05: lưu Bundle cho session và các trang đã hoàn tất; phần đang xử lý vẫn thiếu như B02.
- S06: DocumentRepo.addDocument trả Boolean và rollback RAM khi ghi lỗi; saveFinalDocument đã kiểm tra kết quả. createNewPdf vẫn thiếu như B05.
- S07: initialize draft kiểm tra copy/commit/save, trả null khi lỗi; export kiểm tra flush.
- S08: Viewer giữ session khi tạo preview mới không đầy đủ.
- S09: Back xét dirty toàn phiên và title; nhánh Lưu chưa hoàn chỉnh như B03/B04.

## B01 — P1: Camera vẫn bỏ ảnh khi xử lý ảnh thất bại

**File:** `app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt:312–340`.

normalizeImageOrientation trả Boolean nhưng kết quả bị bỏ qua. finally xóa rawFile dù tạo ảnh đích thất bại. Nếu ảnh đích không qua validate, không có nhánh else báo lỗi hoặc ghi nhận failed page; counter giảm về 0 và Done tiếp tục với các trang còn lại.

**Tái hiện cần chạy:** có trang một hợp lệ, chụp trang hai rồi giả lập normalize/copy lỗi hoặc ảnh đích không hợp lệ; Done có thể mở editor chỉ với trang một. Ảnh raw trang hai cũng đã bị xóa nên không thể thử lại.

**Sửa:** giữ raw đến khi output đã validate và trạng thái trang được commit; lưu trạng thái failed theo capture ID. Không hoàn tất âm thầm khi có trang lỗi, cho Retry hoặc xác nhận bỏ trang. Test normalize=false, exception, validate=false và hủy.

## B02 — P1: Phục hồi camera chỉ giữ trang đã hoàn tất, mất trang đang xử lý

**File:** `CameraScanActivity.kt:107–128,159–167,306–340`.

Bundle chỉ lưu orderedPageMap.values. Raw đã chụp nhưng đang crop chưa nằm trong map. Job thuộc lifecycle Activity, không có manifest pending capture bền vững hoặc quét/reconcile file phiên khi phục hồi. onSaveInstanceState cũng có thể xảy ra trước khi một job hoàn tất, khiến Bundle không chứa trang vừa hoàn tất sau đó.

**Tái hiện:** chụp ảnh lớn, khi crop còn chạy đưa app ra nền và recreate/kill process → mở lại; chỉ các trang trong snapshot được phục hồi. Trang đang xử lý không được tiếp tục hoặc đưa lại vào phiên; raw còn có thể bị finally xóa.

**Sửa:** lưu capture ID/raw path/trạng thái xử lý ngay khi có ảnh, owner không gắn trực tiếp Activity cho phần xử lý cần sống qua recreate; sau process death reconcile file và trạng thái. Giữ nguyên thứ tự ID thay vì chỉ lưu danh sách path. Test có pending capture, completion sau onSaveInstanceState, recreate và process death; test map snapshot đơn thuần không đủ.

## B03 — P1: Chọn Lưu khi thoát vẫn đóng editor nếu lưu nháp thất bại

**File:** `app/src/main/java/com/tscanner/app/ui/editor/PostScanEditorActivity.kt:460–471`.

Cả nhánh nút Save và nhánh không dirty đều gọi flushPendingChanges nhưng bỏ Boolean rồi finish. Flush đã có khả năng trả false khi revision mới nhất chưa lưu được; bản sửa chưa xử lý kết quả ở caller này.

**Tái hiện:** sửa trang hoặc title, gây lỗi ghi metadata, Back → Lưu. Editor đóng dù revision mới chưa bền vững; state trong ViewModel bị mất khi finish, phiên phục hồi chỉ có revision cũ.

**Sửa/nghiệm thu:** chỉ finish khi flush=true; false phải giữ editor, báo lỗi và cho retry. Test fault injection kiểm tra Activity không đóng, trạng thái chỉnh sửa còn nguyên, retry thành công mới thoát.

## B04 — P2: Có nút lưu nháp nhưng chưa có đường mở lại bản nháp đã thoát

**Bằng chứng:** nhánh Save trong PostScanEditorActivity kết thúc Activity. `loadDraft(sessionId)` chỉ được gọi trong initialize của PostScanEditorViewModel. Rà production code chưa thấy API liệt kê/luồng Home mở draft_sessions hoặc lưu last-session để tiếp tục; camera mới tạo session ID mới.

**Tác động:** ngay cả khi flush thành công, người dùng quay Home không có cách bình thường để mở lại bản nháp vừa lưu. File draft có thể còn nguyên; đây là mất đường truy cập, không khẳng định xóa dữ liệu vật lý.

**Sửa/nghiệm thu:** thêm mục Bản nháp/Tiếp tục phiên hoặc cơ chế tiếp tục gần nhất có session ID bền vững. Test edit → Back → Lưu → khởi động lại → mở lại đúng trang/title/chỉnh sửa; phân biệt rõ lưu nháp với lưu PDF.

## B05 — P2: Nhánh Tạo PDF mới chưa kiểm tra commit danh mục

**File:** `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt:498–530`, hàm createNewPdf.

Luồng này vẫn bỏ kết quả `addDocument(docItem)` ở dòng 527 và mở dialog thành công. Nó cũng bỏ kết quả savePdfToDownloads và thông báo thành công trước khi đăng ký danh mục. Sửa ở saveFinalDocument không bao phủ nhánh này.

**Tái hiện:** tạo PDF mới từ Viewer, file tạo thành công nhưng ghi danh mục lỗi → repo rollback, dialog vẫn thành công, tài liệu không xuất hiện trong danh sách. Nếu Downloads cũng lỗi, người dùng không có bản ở vị trí được kỳ vọng. File output nội bộ có thể còn, không phải lỗi mất PDF vật lý trong mọi trường hợp.

**Sửa/nghiệm thu:** kiểm tra từng kết quả và thông báo đúng phần thành công/thất bại; retry không tạo bản ghi trùng. Rà các caller addDocument khác khi đổi API từ Unit sang Boolean. Test riêng createNewPdf, không chỉ saveFinalDocument.

## Ưu tiên

B01/B02 → B03 → B04/B05. Các gói sửa nên kiểm tra hành vi luồng thật (đủ trang, giữ raw khi lỗi, không finish khi flush lỗi, mở lại draft), không chỉ lặp logic tương tự trong unit test.

Chưa kết luận toàn app hết lỗi. Các giới hạn từ báo cáo trước về RAM ảnh lớn, cache preview chung và filesystem vẫn cần kiểm chứng; lượt này tập trung xác minh các bản sửa vừa thực hiện.
