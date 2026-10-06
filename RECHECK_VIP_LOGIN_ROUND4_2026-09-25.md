# Rà soát sau G00–G07 — vòng 4

Ngày 25/09/2026. Không sửa production hoặc test trong app/src. Đã đọc skill rà soát T-Scanner, đối chiếu REPORT_VIP_LOGIN_ROUND3_G07.md với mã, giữ thay đổi sẵn. Chỉ tạo probe riêng trong build/vip-login-reaudit4, báo cáo này và kế hoạch tiếp theo.

## Kết quả

668/668 test hiện hữu đạt khi chạy mới; build debug/lint thành công, lint 0 errors/728 warnings. Bốn probe bổ sung đều thất bại bằng assertion, không phải compile error. Các ca này kiểm tra biên khác với bốn regression vòng trước.

Không có thiết bị trong adb devices. Google Play login, chứng thư/OAuth Console, SDK provider thật và process recreation Android thực tế vẫn NOT RUN. Không kết luận SHA-1 sai hoặc việc cài từ Play đã hoạt động.

## U01 — P1: Cleanup provider đã bắt đầu vẫn chồng với login mới

AppAuthManager.kt:977–1006 chỉ kiểm tra `canRunProviderCleanup` trước khi gọi cleanup suspend. Sau khi clearCredentialState bắt đầu, user mới có thể login/commit; code tiếp tục GoogleSignIn.signOut mà không kiểm tra lại hoặc serialize. Lỗi xóa local session cuối logout đã được sửa, nhưng provider có vòng đời riêng.

Probe giữ googleSignOutAction ở `release.await()`, đăng nhập B qua signInWithGoogle production với credential client injected, rồi cho cleanup kết thúc. Callback cleanup quan sát B đã được đăng nhập. Đây là bằng chứng operation overlap, KHÔNG phải chứng cứ SDK thật đã xóa session B. Provider signOut sau clearCredentialState suspend có đường tác động phiên mới từ mã; phải kiểm tra thiết bị sau sửa.

Khác với test cũ: test cũ chỉ login B trước khi cleanup bắt đầu, nên guard đầu vào đủ để xanh. Cần serialize login/provider cleanup hoặc giao thức có bảo đảm tương đương; recheck trước gọi SDK chưa giải quyết operation đã chạy. Timeout/cancellation coroutine không đồng nghĩa Task SDK đã ngừng.

## U02 — P2: Drive callback thiếu token vẫn mượn pending request của host khác

AppAuthManager.kt:274 còn `attempt ?: pendingDriveAuthAttempt.getAndSet(null)` dù classic login đã bỏ kiểu fallback tương tự. Registry chỉ xác nhận request được mượn có tồn tại, không chứng minh callback thuộc request đó.

Probe tạo một Drive request hợp lệ rồi gọi handler với attempt=null, parser trả account đúng/scope đúng. onSuccess gọi 1 lần (kỳ vọng 0), request hiện hành bị consume. Callback trễ/trùng từ host đã clear pending có thể lấy request của host khác hoặc lượt mới.

Cần token bắt buộc cho production result, null thì discard trước lookup/consume/parser; rà mọi overload và launcher. Không tuyên bố đây là cấp quyền Google trái phép: lỗi đã xác nhận là mất tương quan kết quả nội bộ.

## U03 — P2: Counter reset làm token cũ trùng request mới

AppAuthManager.kt:56–59 dùng sessionGeneration khởi tạo 1 và driveRequestGeneration khởi tạo 0. Registry so requestId/user/email/generation nhưng không có process epoch hoặc nonce xuyên restart. In-memory registry không đủ loại token cũ nếu một request mới có cùng bộ giá trị.

Probe: login U → lưu token cũ → reset singleton bằng resetForTesting (mô phỏng counter/registry khởi động lại) → login U và tạo request mới → trả token cũ. Request mới bị nhận và success gọi 1 lần. Đây là mô phỏng state reset, chưa kill/recreate Android thật; không khẳng định mọi restart đều trùng generation.

Policy đã công bố là reject token process cũ. Cần định danh request có nonce/epoch không lặp và registry authoritative, kiểm tra cả cancel path; giữ saved state trong cùng process nhưng loại state process cũ. Xem xét cùng mô hình cho GoogleLoginAttempt, chưa có probe riêng kết luận lỗi login ở tình huống này.

## U04 — P2: Snackbar của phiên trước còn thực thi khi người dùng bấm

SyncResultPresenter.present chỉ kiểm tra expectedSessionGeneration lúc render; showActionablePrompt.safeAction kiểm tra mỗi AtomicBoolean nhưng không kiểm tra lại session/host lifecycle lúc tap. Các caller Home/More/Viewer/ID card cũng chưa truyền expectedSessionGeneration.

Probe render AuthRequired với generation đúng → đổi generation → invoke ActionPrompt.onAction (đường onClick production). Callback vẫn chạy 1 lần, kỳ vọng 0. Khi logout/đổi account mà Activity còn sống, action cũ có thể mở cấp quyền/retry cho tài khoản mới. Lỗi này tồn tại ngay cả khi caller đã truyền generation lúc render.

Cần capture identity/generation của operation và guard lúc render lẫn tap; invalidation khi view bị destroy. Không chỉ guard Activity: fragment có thể đã mất view trong Activity vẫn sống. Kiểm tra callback tap thật, không chỉ test prompt xuất hiện.

## Những sửa đổi đã qua kiểm tra

- Token login tái tạo được đối chiếu/release theo request ID/generation; suite regression vòng 3 xanh.
- Classic login thiếu token không còn tự mượn active token như trước.
- Drive duplicate cùng request trong một process có registry consume, nhưng còn U02/U03.
- Logout finally không xóa local user mới trong ca regression cũ; còn overlap provider U01.
- Ba callsite repository đã dùng dispatchCloudBackup thay vì backup đồng bộ trong rename/add/modify.
- Presenter đã có Snackbar action, nhưng còn U04.
- Snapshot persistent storage/no-live-fallback và trial local giữ nguyên; chưa nghiệm thu mọi I/O/process/device gate.

## Bằng chứng và cách chạy

Host: `$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'; .\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain`. BUILD SUCCESSFUL, test thực thi mới, lint/build phần lớn UP-TO-DATE.

Probe source: build/vip-login-reaudit4/VipLoginRound4ProbeTest.kt. Tái dùng fixture của VipLoginRound3RegressionTest, gọi production; fake parser/credential provider và CompletableDeferred điều khiển thứ tự. Bốn method:

- probeProviderCleanupMustNotOverlapNewLogin
- probeDriveNullHostTokenDoesNotBorrowOtherRequest
- probeOldDriveTokenDoesNotMatchNewProcessCounters
- probeSyncActionMustRecheckSessionAtClick

Chạy bằng init script build/vip-login-reaudit4/audit.init.gradle với --tests từng tên method đầy đủ. Kết quả 4/4 assertion failures, XML lưu probe-results.xml. Baseline XML lưu baseline-results. Sau probe đã chạy lại suite chuẩn không nạp init script. Không xem API resetForTesting hoặc injected provider là thiết bị/Google SDK thật.

Kế hoạch sửa tiếp: PLAN_FIX_VIP_LOGIN_ROUND4_SMALL_MODEL_2026-09-25.md. Chỉ bốn phạm vi đã tìm thấy; chưa triển khai production.
