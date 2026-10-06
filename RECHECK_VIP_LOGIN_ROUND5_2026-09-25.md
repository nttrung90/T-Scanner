# Rà soát sau H00–H05 — vòng 5

Ngày 25/09/2026. Không sửa production/test trong app/src; giữ working tree có sẵn. Dùng skill rà soát T-Scanner. Chỉ thêm probe trong build/vip-login-reaudit5 và tài liệu.

## Kết luận

Chưa đạt nghiệm thu. Bộ hiện hữu chạy mới đạt 691/691; build/lint thành công. Probe bổ sung gọi state machine production: 5 ca, 4 assertion failures và 1 control đạt. Bốn failures thuộc hai nhóm nguyên nhân chính, không phải bốn subsystem độc lập.

ADB không có thiết bị. Các probe không gọi Google SDK thật và không kill/recreate Android thật. Không kết luận nguyên nhân lỗi Google Play là SHA-1, cũng không xác nhận đã sửa xong lỗi đăng nhập trên Play.

## F01 — P1: LogoutCoordinator nhầm coroutine hoàn tất với provider đã xong

### F01a: Timeout/cancel mở lại login khi Task còn pending

- LogoutCoordinator.onCleanupCompleted luôn đặt isProviderTaskPending=false và complete deferred.
- AppAuthManager.kt:1042–1048 chỉ chờ GoogleSignIn.signOut Task tối đa 3 giây; sau timeout, finally :1064 gọi onCleanupCompleted dù Task chưa kết thúc. Nhánh cancellation :1050 cũng gọi markProviderTaskCompleted dù không có bằng chứng Task SDK đã ngừng.
- Probe gọi đúng transitions production `startLogout → markProviderTaskStarted → onCleanupCompleted → awaitProviderCleanup`: trả true; kỳ vọng false vì chưa có markProviderTaskCompleted.
- Đây là bằng chứng state machine mở cổng sai, không phải đã quan sát Google tự logout user mới trên thiết bị.

### F01b: Logout mới đánh thức waiter của logout cũ trước khi cleanup mới xong

- startLogout hoàn tất deferred cũ và thay activeOperationId/deferred; awaitProviderCleanup dùng completedInTime để trả true ngay cả khi isCleaning vẫn true và task mới chưa bắt đầu.
- Probe tạo waiter operation A rồi startLogout B: waiter A trả true dù B còn active.
- markProviderTaskStarted/Completed không nhận operationId nên completion cũ cũng không được ràng buộc rõ với operation sở hữu.
- Cần quản lý trạng thái theo operation/Task, không ghi đè pending task cũ khi startLogout mới. Waiter phải đánh giá toàn bộ cleanup liên quan còn chạy, không coi deferred cũ complete là cho phép login.

Control: provider thật sự được đánh dấu hoàn tất rồi coroutine hoàn tất → await trả true, test đạt. Harness không phải luôn trả lỗi.

## F02 — P1: Login token epoch được thêm nhưng chưa bắt buộc ở mọi đường

- AppAuthManager.matchesActiveAttemptLocked :86 cho qua khi token.processEpoch hoặc active.processEpoch rỗng.
- MoreFragment.kt:57, PdfViewerActivity.kt:315, IdCardComposeActivity.kt:295 khôi phục `GoogleLoginAttempt(reqId, sessionGen)` mà không giữ processEpoch, tạo token rỗng trên đường production thực tế.
- clearActiveAttemptIfMatchingLocked chỉ so requestId/sessionGeneration, không so processEpoch.

Hai probe:

1. Tạo token cũ, tạo bản restore hai trường như host, reset singleton/counters rồi tạo attempt mới cùng counter → token không epoch vẫn isAttemptValid=true. Kỳ vọng reject.
2. Giữ token có epoch cũ, reset/tạo attempt mới → cancelSignInProgress(token cũ) xóa active mới. Kỳ vọng giữ active mới.

ResetForTesting ở đây mô phỏng counter/registry khởi động lại, không phải chứng cứ mọi Android restart đều gặp collision. Nhưng hai public production paths đã chấp nhận/clear sai identity khi counter trùng. Đây là phần thiếu trong policy reject token process cũ.

Cần một tiêu chí identity thống nhất cho validate/commit/cancel/release: processEpoch không rỗng + requestId + sessionGeneration. Host save/restore epoch đầy đủ; state cũ thiếu epoch phải reject và cho retry, không fallback về epoch hiện tại.

## F03 — Báo cáo bàn giao kết luận vượt bằng chứng

REPORT_VIP_LOGIN_ROUND4_H05.md nói mã “an toàn tuyệt đối” và nếu lỗi 10 thì nguyên nhân “100%” là thiếu SHA-1 Play App Signing. Không có Console/status trace/thiết bị trong lượt này chứng minh điều đó; các probe trên còn tìm lỗi mã. Cần đính chính, không chỉ sửa code rồi giữ nguyên kết luận.

Không tự thay OAuth/client ID hoặc khóa ký theo phỏng đoán. Khi có lỗi Play thật phải lấy mã lỗi, bản cài thực tế, package, client project và chứng thư đúng kênh phân phối để đối chiếu.

## Những phần đã cải thiện

- Drive callback production đã reject token null; token Drive có epoch và registry kiểm tra.
- Presenter có kiểm tra session/user/host khi bấm action; các host truyền origin và lifecycle guard.
- Login chờ LogoutCoordinator ở đường thường, nhưng state machine còn F01.
- Suite regression vòng trước xanh; sửa repository async và persistent snapshot vẫn còn trong mã. Lượt này không khẳng định đã test toàn bộ I/O/Drive/device.

## Bằng chứng

Host command: `GRADLE_USER_HOME=C:\Users\nguye\.gradle`, `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain`. BUILD SUCCESSFUL, 691 tests/0 failures; test thực thi mới, phần lớn build/lint UP-TO-DATE.

Probe: build/vip-login-reaudit5/VipLoginRound5ProbeTest.kt; init script cùng thư mục nạp Kotlin test sourceSet. Năm tests gọi LogoutCoordinator/AppAuthManager production, có ArchTaskExecutor delegate để chạy LiveData JVM. Kết quả 4 fail/1 pass. XML riêng: build/vip-login-reaudit5/probe-results.xml; baseline: baseline-results. Sau đó chạy lại suite bình thường không nạp probe.

Phần chưa chạy: SDK Google Task thật sau timeout/cancel, double logout trên Android, activity/process recreation thực, login bản Play, Console/certificate. Kế hoạch tiếp theo: PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md.
