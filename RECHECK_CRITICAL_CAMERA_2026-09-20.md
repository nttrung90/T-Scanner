# Kiểm tra lỗi nghiêm trọng sau sửa B01–B05

Ngày 20/09/2026. Chỉ kiểm tra, không sửa code sản phẩm.

## Kết luận

Còn một rủi ro P1 có bằng chứng trực tiếp trong luồng phục hồi camera. Chưa phát hiện thêm P1 khác trong phạm vi các bản sửa được rà lại; không phải bảo đảm toàn ứng dụng hết lỗi.

## C01 — P1: Mất liên kết trang khi raw đã xóa nhưng trạng thái phiên chưa được lưu

**Bằng chứng:** `app/src/main/java/com/tscanner/app/ui/camera/CameraScanActivity.kt:467–479` xử lý output hợp lệ theo thứ tự: bỏ pending/failed → chuyển IO xóa rawFile → đưa finalPageFile vào orderedPageMap. Không ghi manifest phiên bền vững tại bước commit này. Bundle chỉ ghi snapshot trong onSaveInstanceState (241–265).

Khôi phục ở dòng 114–167 chỉ đọc danh sách từ Bundle. Quét thư mục dòng 170–182 chỉ tìm `raw_*.jpg`, không phục hồi `page_<index>_<timestamp>.jpg` đã xử lý nhưng chưa có trong Bundle.

**Kịch bản cần tái hiện trên thiết bị:**

1. Trang một đã hoàn tất, trang hai đang xử lý; onSaveInstanceState ghi snapshot chưa có trang hai (raw đang pending).
2. Xử lý trang hai xong và xóa raw; process bị dừng trước khi có snapshot mới. Hoặc Activity bị hủy/cancel ở điểm withContext xóa raw trước khi map được cập nhật.
3. Phục hồi snapshot: raw trang hai không còn; ảnh page_2 có trên đĩa nhưng không được quét phục hồi. Phiên chỉ còn trang một và không đánh dấu trang hai thất bại.

Tác động là mất trang khỏi phiên và có thể lưu tài liệu thiếu trang. Không khẳng định file finalPageFile bị xóa: ảnh có thể vẫn còn nhưng không còn được ứng dụng liên kết.

**Sửa cần thiết:**

- Ghi manifest phiên có capture ID, raw path, output path và trạng thái commit bằng cơ chế ghi an toàn; chỉ xóa raw sau khi trạng thái output bền vững.
- Phục hồi đối chiếu manifest với cả raw và output đã validate; giữ thứ tự ID và tránh thêm trùng khi cả hai còn tồn tại.
- Không chỉ chuyển lệnh cập nhật map lên trước xóa raw: map trong RAM không xử lý process death sau snapshot.
- Không coi pending có file mất là tự động bỏ qua; phải khôi phục từ output đã commit hoặc báo trạng thái thất bại rõ ràng.

**Nghiệm thu:** fault injection dừng tại (a) output đã ghi, (b) trước/sau manifest commit, (c) trước/sau xóa raw, (d) trước snapshot kế tiếp. Sau restart số trang phải giữ nguyên, đúng thứ tự, không trùng, không im lặng mất trang. Test hiện có khôi phục map/pending dựng sẵn chưa bao phủ cửa sổ commit này. Cần test lifecycle thực tế hoặc owner/repository thực, không chép lại thuật toán khôi phục trong test.

## Những phần đã cải thiện

- Ảnh xử lý lỗi được giữ raw, có retry/xác nhận bỏ trang trước Done.
- Chọn Lưu khi thoát editor đã kiểm tra flush Boolean; lỗi lưu giữ màn hình lại.
- Home có đường mở bản nháp gần nhất.
- createNewPdf đã kiểm tra kết quả addDocument, không còn bỏ Boolean như trước.

## Kiểm tra tự động và giới hạn

- Gradle `:app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain`: BUILD SUCCESSFUL; 187 JVM tests, 0 failures/errors. Lint task dùng kết quả up-to-date: 0 errors, 711 warnings.
- Localization validator PASS: 650 strings + 7 plurals; 12/12 unittest validator PASS.
- `adb devices`: không có thiết bị. Lỗi C01 được xác định từ code và lịch thực thi khả dĩ; chưa có tái hiện trên camera thật/process death. Chưa kiểm thử RAM thấp, OCR ảnh thật hoặc bản cài release AAB trong lượt này.
