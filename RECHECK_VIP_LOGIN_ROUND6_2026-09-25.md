# Rà soát sau J00–J04 — vòng 6

Ngày 25/09/2026. Phạm vi: đối chiếu sửa logout/provider Task, token login restore và báo cáo J04. Không sửa production/test app/src; giữ working tree. Chỉ tạo probe dưới build/vip-login-reaudit6 và tài liệu.

## Kết luận

718/718 tests hiện hữu chạy mới đạt; build debug/lint thành công. Probe độc lập mới: **3 tests, 1 thất bại, 2 controls đạt**. Còn một lỗi điều phối logout chồng nhau cần sửa. Không thấy căn cứ từ lần kiểm tra này để yêu cầu làm lại các sửa token epoch đã hoàn tất.

ADB không có thiết bị. Chưa nghiệm thu Google SDK thật, Play login hay rotation/process kill thực tế. Đây không phải kết luận toàn bộ app hết lỗi ngoài phạm vi đã kiểm tra.

## P1 — Logout mới tự coi coroutine cũ đã hoàn tất

**Bằng chứng mã:** LogoutCoordinator.startLogout tại :58–68 duyệt mọi operation RUNNING, gán COMPLETED rồi xóa operation không còn pendingTasks. Không có lệnh cancel/join hay bằng chứng coroutine đó đã kết thúc.

**Probe gọi production:**

1. startLogout A, A chưa gọi onCleanupCompleted/onCleanupCancelled và chưa đăng ký GoogleSignIn Task.
2. startLogout B.
3. onCleanupCompleted B.
4. awaitProviderCleanup trả true, dù coroutine A chưa kết thúc. Kỳ vọng false.

**Đường có thể xảy ra trong app:** AppAuthManager.kt:1047 kiểm tra canRunProviderCleanup một lần, sau đó :1068 suspend tại CredentialManager.clearCredentialState. Lúc này chưa đăng ký Task GoogleSignIn. Nếu B bắt đầu/kết thúc trong khi A còn chờ, coordinator đã bỏ A, có thể cho login mới chạy. A sau đó có thể tiếp tục đến provider signOut. Probe xác nhận state machine nhả gate sai; chưa tái hiện SDK thật làm mất phiên trên thiết bị.

**Vì sao suite cũ không bắt:** LogoutCoordinatorTest có ca thay thế A→B nhưng chỉ yêu cầu chờ khi B còn chạy; sau khi B xong lại kỳ vọng login được phép mà không kết thúc A. Kỳ vọng đó không bảo vệ coroutine A vẫn tồn tại. Cần sửa setup/expectation để mô tả lifecycle thực, không giữ test xanh bằng giả định “bị thay thế là đã dừng”.

**Hai controls đạt:** khi A và B thực sự hoàn tất, login được phép; Task đã đăng ký còn pending vẫn chặn login sau khi coroutines kết thúc, đến khi callback Task thật hoàn tất.

**Hướng sửa:** giữ trạng thái RUNNING của mỗi operation đến khi chính Job báo kết thúc/hủy/thất bại. Nếu chọn hợp nhất các logout phải chứng minh operation cũ không thể phát sinh provider side effect nữa. Không chỉ thêm recheck session sau một await rồi coi đó thay thế tracking lifecycle. Coroutine và SDK Task tiếp tục là hai trạng thái riêng.

## Các cải thiện được xác nhận

- Pending GoogleSignIn Task không bị đánh dấu hoàn tất chỉ vì timeout/cancellation waiter như vòng trước; callback Task mang operation/task ID.
- Login token validate/clear yêu cầu epoch; More/Viewer/ID card dùng writeToBundle/fromBundle chung. Regression vòng trước xanh.
- Login có post-wait validity check; không yêu cầu đổi client ID/OAuth theo phỏng đoán.

## Bằng chứng và giới hạn

Host command: `GRADLE_USER_HOME=C:\Users\nguye\.gradle`, `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain`. BUILD SUCCESSFUL; unit tests thực thi mới, nhiều task build/lint UP-TO-DATE.

Probe: build/vip-login-reaudit6/VipLoginRound6ProbeTest.kt, gọi LogoutCoordinator production không sao chép thuật toán. Init script nạp sourceSet tạm, chạy --tests com.tscanner.app.VipLoginRound6ProbeTest. 1 assertion failure/2 pass, không compile failure. XML: build/vip-login-reaudit6/probe-results.xml; baseline XML: baseline-results. Sau đó chạy lại suite chuẩn không init script.

Báo cáo J04 nói “trạng thái sản xuất hoàn chỉnh” vượt bằng chứng khi device/Play còn NOT RUN và probe trên còn đỏ. Cần cập nhật kết luận cùng số liệu lint từ XML, không dùng số cảnh báo trích từ output rút gọn. Không sửa lịch sử test cũ hoặc tuyên bố SDK lỗi nếu chưa chạy SDK.

Kế hoạch hẹp: PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md.
