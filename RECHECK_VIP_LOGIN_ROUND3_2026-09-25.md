# Rà soát sau kế hoạch vòng 2 — vòng 3

Ngày 25/09/2026. Kiểm tra checkout hiện tại và REPORT_VIP_LOGIN_ROUND2_S09.md. Không sửa production/test trong app/src; chỉ thêm probe riêng dưới build/vip-login-reaudit3, báo cáo và kế hoạch tiếp theo. Working tree có nhiều thay đổi sẵn, đã giữ nguyên.

## Kết quả

Chưa đạt nghiệm thu: 638 tests hiện hữu đạt, nhưng 4 probe mới gọi AppAuthManager production đều thất bại. Hai thiếu sót khác xác nhận qua mã. Không có thiết bị trong adb devices; chưa chứng minh lỗi ban đầu trên Google Play đã hết hoặc xác nhận OAuth/SHA-1.

## T01 — P1: Token tái tạo hợp lệ nhưng không giải phóng khóa đăng nhập

- AppAuthManager.kt:83–87 dùng data-class equality (`active == token`) để nhận token; cancel/terminal/commit lại dùng AtomicReference.compareAndSet(token, null), yêu cầu cùng object identity.
- MoreFragment.kt:53–58, PdfViewerActivity.kt:301, IdCardComposeActivity.kt:281 tạo object GoogleLoginAttempt mới từ requestId/sessionGeneration khi khôi phục màn hình.
- Probe: tạo attempt gốc → tạo token mới cùng giá trị như host restore → trả RESULT_CANCELED. Kết quả hủy được xử lý nhưng isSignInInProgress vẫn true. Đăng nhập tiếp theo bị chặn. Với commit thành công, active reference cũng có thể còn sót dù boolean đã false.
- Cần validate ID/generation rồi xóa đúng object hiện hành dưới cùng authStateLock; không chỉ thay equality tại một nhánh. Kiểm tra success/error/cancel và completion request cũ trong lúc request mới chạy.

## T02 — P1: Kết quả thiếu token của host mượn attempt hiện hành

- AppAuthManager.kt:515: `attempt ?: activeLoginAttempt.get()`. Các overload :578–612 cũng mượn active token. Host thực tế truyền nullable pendingSignInAttempt đã được xóa sau lần callback đầu.
- Probe: có attempt đang chờ, callback với attempt=null và account cũ → user `old` được commit. Kỳ vọng không đăng nhập.
- Như vậy callback trễ/trùng từ host không có token có thể gắn vào attempt mới. Không phải chứng cứ kẻ ngoài giả OAuth; đây là lỗi tương quan result trong ứng dụng.
- Cần entry production bắt buộc token của đúng launcher; thiếu/đã consume thì bỏ, không suy ra token từ singleton.

## T03 — P2: Drive single-consume chỉ nằm trong object, không theo request ID

- DriveAuthorizationAttempt.kt: consumed là @Transient, consume chỉ thay biến của object. AppAuthManager.kt:228–262 nhận token explicit và không có registry consume theo ID.
- Probe serialize/deserialize token chưa consume (mô phỏng bản saved state), xử lý bản gốc rồi bản khôi phục. Success chạy 2 lần, kỳ vọng 1. Home/More/Viewer/ID card đang lưu Drive attempt bằng Serializable.
- Cần quản lý request đang chờ/đã consume tập trung theo identity, token host là dữ liệu đối chiếu. Sau process restart phải chọn restore có kiểm chứng hoặc từ chối an toàn; không chỉ thêm boolean vào Parcelable/Serializable rồi coi đã chống replay.

## T04 — P1: Logout cũ xóa login mới

- AppAuthManager.signOut.kt:898–902 invalidate/xóa local ngay, mở đường login tiếp. Coroutine cleanup tiếp tục chạy; finally :922–926 lại clearSavedUser và currentUser=null không kiểm tra generation.
- Probe dùng dispatcher hàng đợi: A đã login → yêu cầu logout A nhưng giữ coroutine cleanup → login B thành công → chạy cleanup A. Thực tế B biến mất (null).
- Ngoài local state, cancelAllWork và GoogleSignIn.signOut trễ có thể tác động phiên mới; probe chỉ xác nhận việc xóa currentUser, chưa chạy SDK/WorkManager thật.
- Sửa toàn bộ logout transaction: fence cleanup provider và local mutation; hoặc serialize login tới khi cleanup xong với trạng thái UI rõ. Không chỉ thêm if cho dòng cuối rồi bỏ qua provider cleanup.

## T05 — P2: Đổi tên tài liệu vẫn copy PDF/chờ WorkManager đồng bộ trên UI

- HomeFragment.kt:531 và FilesFragment.kt:331 gọi repo.renameDocument trực tiếp trong callback dialog.
- DocumentRepo.kt:396–421 giữ @Synchronized, save/publish rồi gọi CloudBackupManager.enqueueBackup đồng bộ. Phương thức này vẫn cleanup Future.get(timeout 3s mỗi file) và copy toàn PDF trước trả về.
- S07 đã sửa post-auth sync và tạo enqueueBackupAsync, nhưng repository còn 3 callsite trực tiếp ở :299, :421, :714. Vì vậy đường đổi tên từ UI chưa hưởng async; vừa block UI vừa giữ monitor repository.
- Đây là đường blocking xác nhận bằng mã; chưa đo thời gian hay tái hiện ANR. Cần tách side effect backup khỏi monitor và chạy IO, giữ snapshot owner/session/revision, ghi lỗi enqueue riêng sau khi đổi tên đã lưu.

## T06 — P2: Presenter nhận callback retry/cấp quyền nhưng không cung cấp thao tác

- SyncResultPresenter.present nhận onRequestDrivePermission/onRetry nhưng hai tham số không được dùng. AuthRequired/Failure chỉ Toast.
- Đã cải thiện: Free trả Skipped và im lặng; callsite nhận typed result. Nhưng yêu cầu S06b về thao tác thử lại/cấp quyền chưa được triển khai trong presenter.
- Cần hành động do người dùng bấm (Snackbar/banner/dialog phù hợp lifecycle), không tự gọi callback ngay khi gặp lỗi. Test việc tap action thay vì test toast là đủ.

## Những điểm đã cải thiện

- Credential factory vẫn chỉ một option, identity tách Drive.
- Các regression vòng 2 đã nằm trong suite và qua.
- Viewer/Create PDF/ID card đã có callback login; tên PDF draft được đưa lại vào dialog sau xác nhận VIP. Chưa kiểm chứng đầy đủ rotation/page/draft trên thiết bị.
- Post-auth I/O chạy session IO; batch không quét cleanup mỗi tài liệu.
- Snapshot mới dùng noBackupFilesDir (có fallback), giữ legacy path; không upload file sống nếu thiếu snapshot. Chưa thử fault injection power loss/fsync failure; việc catch lỗi fd.sync và fallback cache không đủ bảo đảm bền vững trong mọi lỗi I/O.
- Chính sách trial local không thay đổi; không có bằng chứng restore VIP máy mới qua Billing.

## Validation và bằng chứng

Lệnh host: `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain`, GRADLE_USER_HOME=C:\Users\nguye\.gradle. BUILD SUCCESSFUL (2m28s); 638 tests/0 failures; lint XML 0 errors/728 warnings. Test chạy mới, assemble/lint thành công, một phần task UP-TO-DATE.

Probe: `build/vip-login-reaudit3/VipLoginRound3ProbeTest.kt`, init script cùng thư mục nạp Kotlin test sourceSet tạm. Fixture lấy từ regression hiện có; logic được gọi là AppAuthManager, chỉ fake SDK parser và điều khiển dispatcher. Bốn method: probeRecreatedLoginCancelReleasesAttempt, probeMissingHostTokenMustNotBorrowActiveAttempt, probeCopiedDriveAttemptCannotBeConsumedTwice, probeLateSignOutMustPreserveNewLogin. Chạy --tests tên method đầy đủ: 4/4 assertion failures, không phải compile failure.

Baseline XML lưu trong build/vip-login-reaudit3/baseline-results; XML thất bại lưu build/vip-login-reaudit3/probe-results.xml. Sau probe chạy lại suite chuẩn không nạp init script. Probe không phải instrumentation và không chứng minh tần suất xảy ra trên thiết bị.

Không có thiết bị ADB. Play-signed login, OAuth Console, rotation thực, process recreation, Drive thật, áp lực I/O và xuất PDF thực tế còn NOT RUN.

Kế hoạch tiếp theo: PLAN_FIX_VIP_LOGIN_ROUND3_SMALL_MODEL_2026-09-25.md. Không sửa mã nguồn trong lượt rà soát này.
