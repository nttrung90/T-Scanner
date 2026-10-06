# Kế hoạch vòng 3 — sửa các lỗi còn lại sau S00–S09

Repository: E:\DU AN AI\T-Scanner; module :app. Đọc RECHECK_VIP_LOGIN_ROUND3_2026-09-25.md trước khi làm. T01–T04 là lỗi đã tái hiện bằng production probe; T05/T06 là thiếu sót từ mã, chưa nghiệm thu UI thực tế.

## Cách giao việc

- Chỉ thực hiện một gói trong mỗi lượt được giao, không chạy song song vì các gói dùng chung AppAuthManager. Lập kế hoạch chưa phải triển khai production.
- Thứ tự: `G00 → G01 → G02 → G03 → G04 → G05 → G06 → G07`.
- Chụp status/diff trước sửa, không reset/clean hay đè thay đổi có sẵn. Không xóa build vì chứa probe/bằng chứng.
- Không đổi OAuth/client ID/khóa ký/dependency/Billing, không publish; không refactor camera/OCR/PDF ngoài callsite cần thiết.
- Test gọi production đang được UI dùng; mock biên SDK và I/O. Không chỉ test data class/helper tách rời caller. Dùng dispatcher/latch điều khiển thứ tự, không test bằng sleep tùy ý.
- Mỗi gói tạo REPORT_VIP_LOGIN_ROUND3_Gxx.md: file đổi, trước/sau, assertions, lệnh/exit/count, regression còn đỏ của gói sau, runtime chưa chạy. Hoàn tất thì dừng.
- Tất cả đường file ngắn bên dưới tính từ app/src/main/java/com/tscanner/app; tests dưới app/src/test/java/com/tscanner/app.

## G00 — Đưa 4 probe mới thành regression lâu dài

File: chỉ thêm VipLoginRound3RegressionTest.kt và fixture cần thiết; không sửa production. Đọc build/vip-login-reaudit3/VipLoginRound3ProbeTest.kt và probe-results.xml; chỉ lấy bốn ca vòng 3, không copy cả suite vòng 2.

Regression: reconstructed token cancel; result thiếu host token; Drive token qua serialization consume hai lần; logout A trễ sau login B. Giữ expectation gốc. Bổ sung control request token đúng và logout bình thường để chứng minh harness hoạt động.

Nghiệm thu: xác nhận 4 ca đỏ bằng assertion tương ứng, controls xanh. Gói này cố ý chưa làm suite xanh. Báo cáo và dừng; không sửa tests để né lỗi.

## G01 — Nhất quán identity của token sau recreate (T01/P1)

File: utils/AppAuthManager.kt, utils/GoogleLoginAttempt.kt; MoreFragment/PdfViewerActivity/IdCardComposeActivity chỉ serialize/restore pending token nếu cần; VipLoginRound3RegressionTest.kt, GoogleLoginFlowTest.kt.

Bằng chứng: isAttemptValid dùng equality theo giá trị, CAS dùng object identity. Host restore tạo object mới.

Thực hiện:
1. Dưới authStateLock, đọc active object thật; đối chiếu request ID/generation từ token caller rồi thực hiện mutation/clear active object đó một lần.
2. Áp dụng cho cancel, lỗi, success commit, coroutine completion và fallback. Không sửa thành clear vô điều kiện: token A cũ không được xóa B.
3. Token truyền qua saved state chỉ mang dữ liệu; tránh state mutable/transient khiến instance khôi phục có hành vi khác. Không chỉ sửa một nhánh cancel.

Regression: token mới cùng giá trị trả success/error/cancel đều release đúng; A stale trong lúc B chờ không release B; sau recreate thử login tiếp chạy được. Kiểm tra persistent profile và active reference, không chỉ boolean.

Nghiệm thu: probe T01 xanh, các regression vòng 2 không hồi quy. Device rotation chưa chạy phải ghi NOT RUN. Dừng.

## G02 — Không mượn token từ singleton (T02/P1)

File: AppAuthManager; MoreFragment, PdfViewerActivity, IdCardComposeActivity tại callback classic; GoogleSignInResultRouterTest, VipLoginRound3RegressionTest.

Thực hiện: callback production phải có token host đã bắt đầu. Null/stale/consumed → discard; loại fallback `attempt ?: active` và overload tự mượn active token. Adapter tương thích chỉ hợp lệ nếu caller thực sự cung cấp/capture token, không mở lối bypass. Clear pending của host sau khi capture token cho xử lý, vẫn bảo toàn lifecycle.

Regression: old duplicate callback không token trong lúc B chờ không commit/cancel B; đúng token success; null token không gọi parser gây side effect; saved state mất token cho phép retry an toàn. Không thay null bằng current token trong test setup để làm mất coverage.

Nghiệm thu: probe T02 xanh, caller thực tế không còn implicit active-token route. Dừng.

## G03 — Đăng xuất có quyền sở hữu cleanup (T04/P1)

File: AppAuthManager.kt; helper logout operation nhỏ nếu cần; VipLoginRound3RegressionTest.kt, GoogleLoginFlowTest.kt; các caller signOut chỉ nếu cần trạng thái loading.

Thực hiện: trước sửa mô tả giao thức login/logout. Chọn serialize toàn bộ provider cleanup trước cho login mới hoặc cleanup được fence an toàn; không chỉ guard local finally rồi để GoogleSignIn.signOut/cancelAllWork chạy trễ đụng phiên B. Cancel work đúng owner thay vì toàn bộ nếu phù hợp. Local state/prefs nhất quán ngay khi logout; job canceled-before-start và cleanup exception không giữ khóa vĩnh viễn. Provider signOut Task phải được quản lý hoàn tất theo hợp đồng đã chọn.

Regression production: giữ cleanup A → B yêu cầu login → thả cleanup; nếu serialize thì B bắt đầu sau cleanup, nếu không serialize thì không mutation nào của A ảnh hưởng B. Test callbacks/prefs/currentUser/provider call order; cleanup lỗi/cancel/2 lần logout. Các seam SDK phải được production dùng.

Nghiệm thu: probe T04 hoặc phiên bản tương đương giữ nguyên invariant B không bị xóa xanh; logout bình thường và thử lại được. Dừng.

## G04 — Drive consume theo request identity (T03/P2)

File: AppAuthManager.kt, DriveAuthorizationAttempt.kt, DriveAuthorizationResultRouter.kt; Home/More/Viewer/ID card chỉ phần request/restore; DriveAuthorizationFlowTest.kt, VipLoginRound3RegressionTest.kt.

Thực hiện: registry authoritative của pending/consumed theo ID và generation; host token là dữ liệu tham chiếu. Một bản deserialize không được tiêu thụ lần hai. Clear đúng request khi lỗi launch/hủy/logout. Loại fallback lấy request của launcher khác. Xác định process restart: restore registry được kiểm chứng hoặc reject và cho retry; không dùng request counter reset + session generation reset làm bằng chứng token cũ còn hợp lệ.

Regression: serialize trước callback → original và restored callback chỉ sync một lần; serialize sau consume; hai host; request ID giống nhưng generation khác; process-reset policy; valid case đúng email/scope. Không chỉ bỏ @Transient mà thiếu registry đối chiếu.

Nghiệm thu: T03 xanh, null/stale/mismatch từ vòng 2 vẫn bị loại. Dừng.

## G05 — Repository không chạy backup blocking trong callback UI (T05/P2)

File: data/repository/DocumentRepo.kt chỉ 3 side effect backup :299/:421/:714 và cấu trúc tối thiểu để thoát monitor; CloudBackupManager.kt nếu cần safe enqueue API; CloudBackupDispatchTest.kt, test mới DocumentRepoBackupDispatchTest.kt.

Thực hiện: lưu/publish repository thành công trước, lấy immutable owner/session/revision snapshot; dispatch cleanup/copy/enqueue ngoài monitor trên IO. Giữ guard sau copy và CAS. Enqueue lỗi không biến đổi tên đã lưu thành thất bại giả, phải có trạng thái retry thích hợp. Không đổi nghĩa quyền sở hữu hoặc chuyển guest sang user mới do async.

Regression bắt buộc qua repo.renameDocument/addDocument/mark-content-change production: giữ cleanup/copy bằng latch → caller trả về và monitor được giải phóng; session switch khi chờ không enqueue sai; save thất bại không enqueue; bytes/revision đúng. Test chỉ gọi enqueueBackupAsync riêng không chứng minh repository đã sửa.

Nghiệm thu: Home/Files rename path không chờ backup I/O; không tuyên bố cả repository hoàn toàn không I/O trên UI vì saveData còn phạm vi riêng. Dừng.

## G06 — Thao tác retry/cấp quyền thực sự (T06/P2)

File: SyncResultPresenter.kt; host UI Home/More/Viewer/ID card và VipUpgradeDialog tại callback phù hợp; strings các locale đang hỗ trợ; PostAuthorizationSyncResultTest.kt và UI integration test phù hợp.

Thực hiện: present AuthRequired/Failure có action do người dùng bấm, gắn lifecycle host. Không tự gọi onRetry/onRequestDrivePermission trong present. ApplicationContext Toast không có action; dùng host view Snackbar/banner/dialog hoặc điều hướng rõ. Khi host đã bị hủy không giữ Activity/lambda và không hiển thị. Result/session cũ không tác động phiên mới. Debounce không được xóa action cần thiết hay chặn người dùng thử lại.

Regression: render không tự consent/retry; tap action gọi callback đúng một lần; destroyed host; stale session; Free Skipped im lặng; Success(0), Partial, Failure. Khi không có callback phải có thông báo/đường đến nơi xử lý rõ, không tạo nút vô tác dụng.

Nghiệm thu: kiểm tra click action production, không chỉ assert toast text; runtime UI chưa có phải ghi riêng. Dừng.

## G07 — Tổng hợp host và thiết bị

File: REPORT_VIP_LOGIN_ROUND3_G07.md và test nghiệm thu; không sửa production tiện tay.

- Chạy toàn bộ test mới + lint + assembleDebug; ghi count thực tế và test chạy mới. 638 là baseline cũ, không mục tiêu cứng.
- Kiểm tra bốn regression mới đều xanh, các regression vòng 2 không bị nới assertion. Nếu lỗi mới, ghi gói riêng.
- Rà tất cả launcher/saved state: token restore, callback null/trùng, request A/B, logout cleanup; các callsite backup trực tiếp phải giải thích thread.
- Nghiệm thu bản release cài qua Play track: Google login success/hủy/lỗi, rotation khi classic fallback và Drive mở; process kill; logout→login nhanh; các đường VIP Home/More/Viewer/Create PDF/ID card; retry/cấp quyền; rename với nhiều snapshot.
- Không publish hoặc đổi OAuth tự động. Nếu login bản Play còn lỗi cần status code và package/chứng thư Play App Signing so với đúng OAuth project. Thiếu thiết bị/Console thì gate NOT RUN, không ghi đã hết lỗi Play.
- Phân loại PROBE PASS / CODE VERIFIED / DEVICE PASS / NOT RUN; đính chính báo cáo cũ bằng liên kết đến báo cáo mới, không xóa lịch sử.

## Lệnh và bàn giao

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound3RegressionTest --offline --console=plain
# G07
.\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Dùng tên lớp đầy đủ, tránh wildcard Windows. Không chạy clean vì probe nằm trong build. Cache lock là vấn đề môi trường, không coi là lỗi app.

Mẫu báo cáo: baseline/status → file sửa → production path được test → lỗi trước/sau và command/count → gói sau còn đỏ → runtime chưa chạy → điểm dừng.

## Prompt giao gói đầu tiên

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_ROUND3_SMALL_MODEL_2026-09-25.md và RECHECK_VIP_LOGIN_ROUND3_2026-09-25.md. Khi được giao triển khai, thực hiện DUY NHẤT G00.
Bảo toàn working tree và bằng chứng build/vip-login-reaudit3. Không sửa production. Chuyển 4 ca probe mới thành VipLoginRound3RegressionTest gọi production, giữ expected assertions; thêm controls thành công. Chạy test, ghi chính xác các ca đỏ/controls và giới hạn. Xuất REPORT_VIP_LOGIN_ROUND3_G00.md rồi dừng, không làm G01.
```

Prompt cho gói tiếp: “Đọc kế hoạch vòng 3 và báo cáo gói trước; thực hiện DUY NHẤT Gxx theo phạm vi, regression và nghiệm thu. Không mở rộng SDK/OAuth/Billing, không publish. Báo cáo đầy đủ phần chưa chạy rồi dừng.” Mỗi lượt thay Gxx bằng một gói, không giao toàn bộ chuỗi.
