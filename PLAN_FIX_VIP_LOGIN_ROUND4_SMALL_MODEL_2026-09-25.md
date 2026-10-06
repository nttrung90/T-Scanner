# Kế hoạch sửa vòng 4 — giao từng gói nhỏ

Repository E:\DU AN AI\T-Scanner, module :app. Đọc RECHECK_VIP_LOGIN_ROUND4_2026-09-25.md. Không làm lại G00–G07; xử lý bốn phạm vi U01–U04 còn hở.

## Quy tắc giao việc

- Thứ tự `H00 → H01 → H02 → H03 → H04a → H04b → H05`. Mỗi lượt chỉ giao một gói, báo cáo rồi dừng; không song song file AppAuthManager/callsite.
- Chụp git status/diff trước sửa; giữ thay đổi sẵn, không reset/clean, không xóa build vì chứa bằng chứng. Production chưa được sửa trong lượt lập kế hoạch này.
- Không đổi OAuth/client ID/chữ ký/dependency hoặc thêm Billing; không publish. Các kết quả Play/device còn NOT RUN.
- Test phải chạy production caller thật với seam ở SDK/lifecycle. Không chỉ tạo helper test hoặc nới assertion để xanh. Fake provider kiểm tra thứ tự không được gọi là Google SDK test.
- Mỗi gói tạo REPORT_VIP_LOGIN_ROUND4_Hxx.md: file sửa, invariant, trước/sau, command/exit/count, các regression gói sau còn đỏ, runtime chưa chạy, điểm dừng.
- Các đường file ngắn bên dưới tính từ app/src/main/java/com/tscanner/app; tests ở app/src/test/java/com/tscanner/app.

## H00 — Giữ bốn regression mới

File chỉ tests: VipLoginRound4RegressionTest.kt và fixture tối thiểu. Đọc source/XML trong build/vip-login-reaudit4. Chuyển bốn ca vòng 4 thành regression lâu dài, không copy cả suite vòng 3.

Giữ kỳ vọng: logout provider không hoàn tất trên phiên mới; null Drive host token không consume; token cũ không trùng registry mới sau reset; action UI cũ không chạy sau đổi phiên. Thêm controls valid request, action cùng phiên và logout/login theo thứ tự bình thường.

Nghiệm thu: xác nhận 4 assertions đỏ và controls xanh. Chưa sửa production; suite còn đỏ là baseline có chủ ý. Dừng.

## H01 — Serialize login với provider cleanup (U01/P1)

File: AppAuthManager.kt; helper LogoutCoordinator.kt nếu cần; GoogleLoginFlowTest.kt, VipLoginRound3RegressionTest.kt, VipLoginRound4RegressionTest.kt. UI caller chỉ thêm trạng thái chờ/thử lại nếu hợp đồng đòi hỏi.

Trước sửa viết state table: idle → cleaning provider → idle/login allowed; queued/cancelled/double-logout/error/timeout. Chọn cơ chế serialize cleanup và login sao cho provider operation đang chạy không thể tác động tài khoản vừa commit. Không chỉ guard generation trước/giữa hai call hoặc ở finally.

Các yêu cầu:
1. Local logout và cleanup provider có operation identity riêng; clean request cũ không cancel work hoặc clear provider của phiên mới.
2. Chặn/queue login trong khi destructive SDK cleanup chưa hoàn tất, với phản hồi UI rõ; không để coroutine scope bị hủy trước start làm chờ vĩnh viễn.
3. Task GoogleSignIn.signOut có completion riêng; coroutine timeout/cancel không chứng minh task đã dừng. Xác định trạng thái an toàn khi task chưa biết kết quả; không silently unlock chỉ vì hết 3 giây.
4. CancellationException phải propagate hoặc được xử lý có chủ đích; không catch như lỗi bình thường rồi tiếp tục cleanup mới trong scope đã hủy.
5. Giữ việc không xóa local B ở late finally và cancel WorkManager theo owner.

Regression: suspend ngay giữa provider cleanup → yêu cầu login B → thả cleanup; xác minh thứ tự provider completion trước commit B hoặc invariant tương đương không tác động B. Cả credential và classic; canceled scope trước launch; cancel giữa await; provider exception; task timeout còn pending; 2 lần logout. Không sửa test thành chỉ login B trước khi cleanup bắt đầu.

Nghiệm thu: U01 xanh với production coordinator; các regression cũ xanh; không khóa login vĩnh viễn. Dừng.

## H02 — Drive result bắt buộc token host (U02/P2)

File: AppAuthManager.kt; HomeFragment/MoreFragment/PdfViewerActivity/IdCardComposeActivity tại launcher Drive; DriveAuthorizationFlowTest.kt và VipLoginRound4RegressionTest.kt.

Loại `attempt ?: pendingDriveAuthAttempt` khỏi đường callback production. Thiếu token → discard trước parse/consume. Rà overload tương thích: không lén gán singleton request cho callback thiếu token. API launch có thể tạo request nhưng caller phải capture token riêng cho result. Không lấy request host B khi callback host A tới trễ.

Regression: B đang chờ, A result null-token tới → B còn pending; B result thật sau đó success đúng một lần. Duplicate callback sau host clear token không consume request mới. Cancel/error launch clear đúng owner; valid scope/account vẫn qua.

Nghiệm thu: U02 xanh, mọi callsite production explicit-token; thiếu token cho retry an toàn. Dừng.

## H03 — Identity không lặp giữa process (U03/P2)

File: DriveAuthorizationAttempt.kt, AppAuthManager.kt; GoogleLoginAttempt.kt nếu chung generator và báo phạm vi rõ; saved-state code của các host; DriveAuthorizationFlowTest/VipLoginRound4RegressionTest, test identity mới nếu cần.

Thêm process epoch/request nonce không reset thành cùng giá trị giữa các process (ví dụ UUID), immutable trong token và registry. Trong cùng process restored token phải đối chiếu được; process mới policy reject token cũ dù counter/user/session tình cờ trùng. Không chỉ tăng counter khởi tạo một số khác tùy ý. Rà cancel/lookup/consume/release dùng đủ identity, không chỉ requestId.

Chính sách tương thích saved state cũ thiếu epoch: discard an toàn và cho retry, không tự coi là current epoch. Cân nhắc serialVersionUID/default field để không crash trên restore; không sửa persisted tài liệu/VIP.

Regression: giữ token cũ → reset registry/counters → tạo request mới cùng user/generation → token cũ bị loại và không cancel request mới. Serialize/deserialize trong process vẫn hợp lệ; duplicate chỉ một lần; missing epoch; nếu sửa login identity thêm cùng ma trận login.

Nghiệm thu: U03 xanh; không coi resetForTesting là process-kill Android đã test. Dừng.

## H04a — Guard action tại thời điểm bấm (U04/P2, presenter)

File: SyncResultPresenter.kt; PostAuthorizationSyncResultTest.kt và VipLoginRound4RegressionTest.kt.

Action giữ origin user/session và host-validity predicate hoặc lifecycle owner; kiểm tra lúc render VÀ lúc tap. Chỉ consume action sau khi xác nhận còn hợp lệ; stale action bị bỏ, không launch consent/retry. Không giữ Activity/view đã destroy qua prompt; Activity còn sống chưa đủ nếu fragment view mất.

Regression: prompt đúng phiên → đổi phiên → tap bị loại; render → destroy host/view → tap bị loại; đúng phiên/host → tap một lần; double tap; debounce/retry không làm mất action hợp lệ. Dùng ActionPrompt.onAction production seam, không chỉ gọi predicate riêng.

Nghiệm thu: U04 xanh; prompt render không tự kích hoạt callback; chuẩn bị contract H04b, chưa khẳng định tích hợp đủ host. Dừng.

## H04b — Nối origin và lifecycle từ tất cả caller

File: HomeFragment, MoreFragment, PdfViewerActivity, IdCardComposeActivity, VipUpgradeDialog tại sync callback; AppAuthManager/CloudBackupManager chỉ envelope result nếu cần; tests tích hợp host/producer.

Capture generation/user khi operation bắt đầu, chuyển cùng result/action; không lấy generation hiện tại lúc nhận callback rồi gọi đó là origin. Fragment dùng view lifecycle, Activity dùng lifecycle phù hợp. Nếu operation trả đồng bộ AuthRequired/Skipped vẫn dùng cùng hợp đồng. Bỏ đường optional argument default khiến caller bỏ qua bảo vệ.

Regression: callback cũ sau logout→login B không render/action trên B; fragment replace trong cùng Activity; dialog dismiss; retry tạo operation identity mới; tất cả bốn host. Kiểm tra thực tế callsite được nối, không chỉ presenter test.

Nghiệm thu: typed results/Free Skipped và action đúng vẫn hoạt động; device UI chưa chạy ghi NOT RUN. Dừng.

## H05 — Nghiệm thu tổng hợp và điểm dừng

File: REPORT_VIP_LOGIN_ROUND4_H05.md, regression bổ sung khi cần; không sửa production tiện tay.

Chạy unit test mới toàn bộ, lint/assembleDebug; số test thực tế thay vì giữ cứng 668. Xem assertions của vòng 2/3/4 không bị bỏ. Nếu test cũ cần cập nhật setup theo token bắt buộc, giữ nguyên kịch bản lỗi và control valid.

Trên thiết bị/Play track: valid Google login/cancel/error; provider logout chậm và bấm login ngay; xoay màn hình; process kill lúc Drive consent rồi retry; callback trùng; logout trong khi Snackbar còn hiện; fragment chuyển tab rồi tap; Home/More/Viewer/ID card/Create PDF. Ghi version/artifact và evidence. Không có thiết bị/Console thì NOT RUN, không khẳng định lỗi Play ban đầu đã hết.

Không tự publish/đổi OAuth. Nếu cần OAuth, đối chiếu actual package/certificate của đúng bản phân phối và project, không mặc định mismatch. Báo cáo cũ được giữ như lịch sử, đính chính qua liên kết báo cáo mới.

## Lệnh và prompt bàn giao

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound4RegressionTest --offline --console=plain
# H05
.\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Dùng tên đầy đủ, không wildcard, không gradle clean. Cache permission không được tính là lỗi app.

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_ROUND4_SMALL_MODEL_2026-09-25.md và RECHECK_VIP_LOGIN_ROUND4_2026-09-25.md. Thực hiện DUY NHẤT H00 khi tôi giao triển khai.
Giữ working tree và bằng chứng build/vip-login-reaudit4. Chuyển 4 probe thành regression lâu dài gọi production, giữ expected assertions và thêm controls. Không sửa production hoặc nới test cho xanh. Chạy và báo số đỏ/xanh thực tế trong REPORT_VIP_LOGIN_ROUND4_H00.md, ghi giới hạn SDK/device. Dừng, không làm H01.
```

Gói sau: “Đọc kế hoạch vòng 4 và báo cáo trước; chỉ thực hiện Hxx theo phạm vi/test/nghiệm thu. Bảo toàn thay đổi, báo cáo kết quả và runtime chưa chạy, rồi dừng.” Mỗi lần một gói; không giao cả chuỗi.
