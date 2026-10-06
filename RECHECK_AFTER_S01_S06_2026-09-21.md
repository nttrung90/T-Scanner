# Kiểm tra lại sau S01–S06 — 2026-09-21

Chỉ rà soát và kiểm tra; không sửa mã ứng dụng. Phạm vi là các bản sửa theo PLAN_FIX_SMALL_MODEL_2026-09-21.md và đường gọi liên quan, không phải chứng nhận toàn dự án hết lỗi.

## Kết quả xác minh

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`: thành công; các bước biên dịch/build/lint dùng kết quả UP-TO-DATE của đầu vào hiện tại.
- Chạy thêm `:app:testDebugUnitTest --rerun --offline --console=plain`: thực thi lại test thành công, **226 test, 0 failure/error, 0 skipped**.
- Lint hiện tại: **0 lỗi, 689 cảnh báo**.
- `adb devices`: không có thiết bị/emulator kết nối. Chưa thực hiện lifecycle Android thật, PDF thật hoặc đo RAM 12/24/48 MP; S06 chưa đủ bằng chứng nghiệm thu runtime.

## N1 — P1 — Toast chạy trên Dispatchers.IO gây lỗi runtime

`utils/DocumentImportHelper.kt:116` bọc toàn bộ thao tác trong `withContext(Dispatchers.IO)`. Các lời gọi `deps.showToast` tại dòng 154,160,172,195,200,206 vẫn nằm trong khối này. Implementation thực tại dòng 68–74 gọi trực tiếp `Toast.makeText(...).show()`.

Đã đối chiếu mã Android SDK 34 cài trên máy: `android/widget/Toast.java:183–188` yêu cầu `Looper.myLooper()` khác null, còn `makeText` tại dòng 492–504 tạo Toast với looper null. Worker Dispatchers.IO thông thường không có Looper. Vì vậy nhánh nhập ảnh lỗi ném exception ngay khi báo lỗi; nhánh thành công lưu tài liệu xong cũng lỗi ở toast, bị catch như lỗi persist rồi lại gọi toast từ cùng luồng và ném exception ra coroutine UI.

Ảnh hưởng: Tạo file PDF trong Công cụ và Nhập ảnh ở Trang chủ dùng helper này có thể làm ứng dụng dừng thay vì hiển thị kết quả. Đây là lỗi đường chạy xác nhận bằng mã helper và SDK; chưa tái hiện trên thiết bị ở lượt này.

Hướng sửa: để phần I/O trả kết quả rồi hiển thị toast/mở Viewer trên Main với vòng đời phù hợp; giữ commit và dọn tệp trong phần I/O. Thu hẹp try/catch persist để lỗi mở UI không bị gắn nhãn lỗi lưu. Test phải kiểm tra luồng callback hoặc chạy default dependency trên Android; fake showToast hiện chỉ thêm vào danh sách nên không phát hiện.

## N2 — P2 — Chưa tái sử dụng Fragment đã phục hồi

`MainActivity.kt:35–38` luôn tạo bốn instance mới. Nhánh restore tại dòng 150–157 giữ Fragment do FragmentManager phục hồi, nhưng `getFragmentForTab` tại dòng 252–259 tiếp tục trả các instance mới. Sau recreate ở Tools, chạm lại tab Tools sẽ replace Fragment phục hồi bằng ToolsFragment mới, làm mất trạng thái cục bộ như vị trí cuộn. Với Home, trạng thái tìm kiếm cũng có thể bị reset. Trường hợp reselect bình thường trước recreate không có sự khác biệt instance này.

Màu tab sau recreate đã được sửa; phần đồng nhất instance chưa hoàn tất. Nên tìm/tái sử dụng Fragment theo tag hoặc đồng bộ instance với FragmentManager, và tránh replace khi đã ở đúng tab. Test bằng Activity recreate → kiểm tra trạng thái → chạm lại cùng tab. `MainActivityNavigationTest` hiện kiểm tra ID và một lambda fallback viết trong test, chưa chạy luồng lifecycle này.

## N3 — P2, rủi ro còn lại — Khởi tạo phiên có thể ghi sau khi hủy

`PostScanSessionRepository.kt:79–83` chỉ kiểm tra closedSessions một lần trước khi tạo thư mục và sao chép ảnh; toàn bộ initializeSession chưa giữ session mutex. `discardSession:230–243` đóng phiên và xóa dưới mutex.

Thứ tự có thể xảy ra: initialize vượt kiểm tra closed → bị chậm; discard đóng/xóa xong → initialize tiếp tục tạo raw_pages và ghi ảnh. `saveDraft` cuối cùng sẽ từ chối metadata vì phiên đã đóng, nhưng ảnh/thư mục của phiên đã hủy vẫn có thể xuất hiện lại. Lỗi writer saveDraft đến sau discard trong báo cáo cũ đã được chặn; nhánh initialize đang chạy là trường hợp khác chưa được test.

Cần barrier giữ initialize sau kiểm tra trạng thái, cho discard hoàn tất rồi thả initialize; kiểm tra không còn tệp bị tạo lại. Phối hợp initialize và discard dưới cùng giao thức khóa/generation, tránh giữ Mutex rồi gọi saveDraft tự lấy lại cùng Mutex gây deadlock. Đây là rủi ro từ phân tích thứ tự thực thi, chưa có tái hiện runtime trong lượt này.

## Phần đã cải thiện và phần chưa kết luận

- Helper đã kiểm tra persist, dùng UUID, trả lỗi khi thiếu ảnh và dọn tệp chưa commit. N1 hiện chặn luồng UI thực tế nên chưa nghiệm thu S01/S02 end-to-end.
- Dấu đóng và lock được giữ sau discard; các test save sau discard đã qua.
- Ngân sách RAM đã tính theo nhánh và có guard trước decode. Không tiếp tục báo nguyên lỗi 12 byte/pixel cũ; cần đo thực tế trước khi đóng R2.
- Ưu tiên sửa N1, sau đó N2; bổ sung test N3 và kiểm chứng runtime S06. Không dùng số lượng unit test để thay thế các bước Android còn thiếu.
