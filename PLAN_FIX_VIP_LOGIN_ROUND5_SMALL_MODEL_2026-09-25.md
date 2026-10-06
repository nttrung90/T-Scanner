# Kế hoạch vòng 5 — đóng hai nhóm lỗi còn lại

Repository E:\DU AN AI\T-Scanner, module :app. Đọc RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md. Chỉ sửa F01/F02 và đính chính F03; không làm lại toàn bộ các vòng trước.

## Cách giao

Thứ tự `J00 → J01 → J02 → J03 → J04`. Mỗi lượt một gói, báo cáo và dừng, không song song AppAuthManager. Không triển khai production trong lượt chỉ lập kế hoạch này.

Giữ working tree, chụp status/diff trước sửa. Không reset/clean, không xóa build chứa probe. Không đổi SDK/dependency/OAuth/chữ ký/Billing, không publish. Tests phải gọi production thực sự, không đổi expectation để xanh; báo rõ phần JVM mô phỏng khác thiết bị thật.

Mỗi gói: REPORT_VIP_LOGIN_ROUND5_Jxx.md gồm invariant, file sửa, trước/sau, command/exit/count, regression gói sau còn đỏ, runtime chưa chạy. File production bên dưới tính từ app/src/main/java/com/tscanner/app, tests từ app/src/test/java/com/tscanner/app.

## J00 — Đưa probe thành regression và đính chính báo cáo

File: VipLoginRound5RegressionTest.kt; REPORT_VIP_LOGIN_ROUND4_H05.md chỉ đính chính kết luận, giữ lịch sử kết quả; báo cáo J00. Không sửa production.

Chuyển bốn probe lỗi và control từ build/vip-login-reaudit5/VipLoginRound5ProbeTest.kt. Giữ transitions production và assertions. Test reset singleton phải ghi rõ là mô phỏng process identity, không Android instrumentation.

Bỏ kết luận “an toàn tuyệt đối/100% do SHA-1” khỏi báo cáo hiện hành hoặc đánh dấu bị thay thế bởi audit mới; ghi Console/Play vẫn NOT RUN, không thêm kết luận nguyên nhân khác theo suy đoán.

Nghiệm thu: 4 regression đỏ + control xanh, không lỗi compile. Báo cáo rồi dừng.

## J01 — Trạng thái cleanup theo operation và Task (F01)

File: utils/LogoutCoordinator.kt; VipLoginRound5RegressionTest.kt; test LogoutCoordinatorTest.kt nếu cần. Chưa đổi AppAuthManager ngoài adapter tương thích tối thiểu để build; chưa coi tích hợp đã hoàn tất trước J02.

Thiết kế trước sửa một bảng transitions:

- Operation có ID, coroutine state, tập provider Tasks đang chạy, kết quả terminal.
- Coroutine return/timeout/cancel chỉ là trạng thái coroutine; không phải Task SDK complete.
- Callback provider phải mang operation ID và Task ID; callback cũ chỉ hoàn tất task của chính nó.
- Operation B bắt đầu không được xóa pending Tasks của A hoặc nhả waiter khi cleanup bất kỳ vẫn có thể ảnh hưởng login.
- Waiter được đánh thức phải đọc lại state; không dùng việc deferred bất kỳ complete làm quyền cho login. CancellationException propagate đúng.

Regression: Task pending + coroutine completed vẫn gate; Task complete mới unlock; logout A→B khi waiter A đang chờ không unlock sớm; task A callback trễ không hoàn tất task B; canceled-before-provider-start không deadlock; timeout waiter không thay đổi task state; duplicate completion idempotent.

Nghiệm thu: hai probe F01 xanh và control xanh; chỉ claim coordinator pass, chưa SDK wiring. Báo cáo API mới rõ và dừng.

## J02 — Nối Task Google thật vào coordinator (F01 tích hợp)

File: AppAuthManager.kt signOut/signIn boundary; coordinator nếu cần adapter; GoogleLoginFlowTest/VipLoginRound4RegressionTest/VipLoginRound5RegressionTest và test tích hợp logout mới.

Thực hiện:
1. Đăng ký provider Task với operation trước khi đợi; completion do Task listener thực sự, không do timeout/catch CancellationException/finally tự đánh dấu xong.
2. Cleanup coroutine finally chỉ báo coroutine completed; Task còn sống tiếp tục chặn destructive overlap. onComplete UI có hợp đồng rõ, không giả rằng provider đã xong khi local logout xong.
3. Timeout cho người dùng thông báo/thử lại an toàn; không mở khóa bằng timer trong khi Task còn pending. Tránh giữ Activity cũ; phần theo dõi Task dùng app-scope phù hợp.
4. Login chờ trạng thái authoritative. Sau chờ kiểm tra lại attempt/session trước SDK và commit; request đã bị logout khác vô hiệu hóa không mở UI Google muộn.
5. Không để lần logout thứ hai/cleanup callback cũ mở cổng khi task đầu còn sống. Giữ kiểm tra owner WorkManager và local-session guards đã có.

Test bằng controllable provider Task adapter production: Task giữ pending qua 3s timeout và scope cancel → login chưa commit; Task hoàn tất sau đó → lần login hợp lệ chạy. Two logout operations với completion đảo thứ tự; coroutine canceled-before-start; failure synchronous trước Task creation; valid Google credential/classic control. Không chỉ gọi helper coordinator riêng rồi tuyên bố AppAuthManager đã đúng.

Nghiệm thu: coordinator tests và integration production xanh, không task pending bị mark complete giả. SDK thật vẫn device gate. Dừng.

## J03 — Identity login thống nhất và saved state đầy đủ (F02)

File: AppAuthManager.kt matches/clear/commit/cancel helpers; GoogleLoginAttempt.kt; MoreFragment.kt, PdfViewerActivity.kt, IdCardComposeActivity.kt chỉ save/restore token; GoogleLoginFlowTest/VipLoginRound3RegressionTest/VipLoginRound5RegressionTest; helper encode/decode saved state nhỏ nếu cần.

Thực hiện:
- Epoch bắt buộc không rỗng và bằng active/current process epoch; cùng criterion cho validate, clear, cancellation, finally và completion. Không whitelist epoch rỗng, không default token cũ sang current epoch.
- Save/restore đủ requestId/sessionGeneration/processEpoch trong cả ba host. Decode state cũ thiếu field → bỏ pending và cho retry, không tự nhận identity mới.
- Token cùng giá trị được recreate trong cùng process vẫn hợp lệ; không dùng object identity CAS kiểu lỗi vòng trước.
- Stale token không clear attempt mới ngay cả request/session counters giống nhau. Bỏ tham số mặc định rỗng nếu có thể; adapter tests phải tạo token thật thay vì bypass.

Regression: epoch rỗng reject; old epoch cancel không clear mới; cùng process restore success/cancel/error giải phóng đúng; state round-trip qua helper được host thật dùng; thiếu epoch từ bản cũ; counters trùng sau reset; callback cũ không gọi parser/claim/save. Không chỉ test data class mà bỏ callsite khôi phục vẫn hai trường.

Nghiệm thu: hai probe F02 xanh, regression recreate vòng trước vẫn xanh. Instrumentation recreation chưa chạy ghi NOT RUN. Dừng.

## J04 — Nghiệm thu và bàn giao

Chỉ báo cáo J04/tests cần thiết; nếu tìm lỗi mới thì gói riêng, không sửa production tiện tay.

- Chạy mới full unit tests + lint + assembleDebug; count thực tế, không giữ cứng 691. Rà assertions các vòng trước không bị bỏ để xanh.
- Đối chiếu F01a/F01b/F02 và bốn original probe; kiểm tra callsite thật, không chỉ helper. Ghi PROBE PASS/CODE VERIFIED/DEVICE PASS/NOT RUN riêng.
- Thiết bị: logout Task chậm; rotate/hủy scope trong khi chờ; double logout; login ngay khi cleanup chờ; timeout rồi Task hoàn tất; classic result sau recreate; process kill và retry; thao tác Drive/Sync action đã sửa ở vòng trước.
- Bản Google Play: kiểm tra login trên đúng artifact/version/kênh phân phối; chỉ nếu có lỗi thì lấy status và đối chiếu package/client/project/certificate thực tế. Không mặc định lỗi 10 là thiếu SHA-1, không tuyên bố 100% an toàn từ JVM.
- Thiếu thiết bị/Console → dừng phần host, giữ gate mở. Không tự upload/publish.

## Lệnh và prompt

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound5RegressionTest --offline --console=plain
# J04
.\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Không dùng wildcard Windows, không clean bằng chứng. Cache permission là lỗi môi trường, không tính là app failure.

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_ROUND5_SMALL_MODEL_2026-09-25.md và RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md. Thực hiện DUY NHẤT J00 khi tôi giao triển khai.
Giữ working tree và build/vip-login-reaudit5. Chuyển 4 probe lỗi + control thành regression lâu dài gọi production, giữ expectation. Không sửa production. Đính chính kết luận vượt bằng chứng trong báo cáo H05, không suy đoán OAuth. Chạy test và ghi REPORT_VIP_LOGIN_ROUND5_J00.md, rồi dừng, không làm J01.
```

Các lượt sau thay J00 bằng đúng một Jxx, đọc báo cáo gói trước, giữ phạm vi/test/nghiệm thu và dừng sau báo cáo.
