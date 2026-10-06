# Kiểm tra sau bản sửa VIP session vòng 2 — 04/10/2026

Hoàn tất tài liệu ngày 05/10/2026 (Asia/Saigon); giữ tên file và thư mục evidence theo ngày bắt đầu kiểm tra.

## Kết luận

Bản sửa có tiến bộ thực: các regression vòng trước về guest continuation, duplicate/late product callbacks và parse HTTP401 đều đã qua. PDF/ID-card hiện truyền expectedOwnerId và callback theo action; chuỗi reauth đã có các locale. **Chưa hoàn tất S01/S03/S04:** đường phục hồi sau receipt còn đứt ở UI, thiếu/hết hạn credential xử lý chưa nhất quán, attempt identity vẫn có đường bỏ qua, và lỗi mở fallback có thể khóa continuation.

Không sửa production code hoặc tests hiện có. Thêm probes/tài liệu tại `docs/vip-session-round3-reaudit-20261004/`. Không kết luận bản public Play đã được nghiệm thu.

## Kiểm chứng thực chạy

| Gate | Kết quả | Evidence |
|---|---|---|
| Gradle test/lint/assemble lần đầu | SUCCESS; phần lớn UP-TO-DATE | `host.log`; không coi đây là test fresh |
| Chạy mới test hiện tại, ép test task thực thi | **1.043/1.043**, 0 failure/error/skip | `tests-fresh.log`, `host-test-results.json` |
| Lint debug | 0 errors / 753 warnings, UP-TO-DATE | `app/build/reports/lint-results-debug.xml`, `host.log` |
| Debug build | SUCCESS, UP-TO-DATE | `host.log` |
| Regression vòng trước được chọn | **7/7 PASS**: P01/P02/P04/P05/P06/C01/C02 | `previous-regressions.xml` |
| Probe mới | **3 PASS / 4 FAIL**, tổng 7 | `new-probes.xml`, `VipSessionRound3ProbeTest.kt`, `probes.log` |
| Tổng probe run | **14 tests, 10 PASS / 4 FAIL** | `probes.log` |
| Thiết bị | `adb devices` danh sách trống | Device/Google provider/Play acceptance NOT RUN |

Probe P03 vòng trước (unbound login API) cố ý không dùng làm acceptance: host đã đổi sang bound sign-in. Không ép sửa API login thông thường chỉ để probe không còn phù hợp xanh. XML mặc định Gradle sau probe thuộc probe run; tổng kết 1.043 được lưu riêng trước đó.

## H01 — P1/P2: AuthRequired sau receipt chưa nối tới xác thực lại

### Đường UI sau receipt (P1, CODE VERIFIED)

- `PlayPurchaseVerifier.kt:412–419` đã trả `VerificationResult.AuthRequired` cho HTTP401: control mới C03 và regression P06 cũ PASS.
- Nhưng `BillingManager.kt:983–986` vẫn gọi `tryNotifyPurchaseFailure`, hạ AuthRequired thành callback `(success=false, message, purchase)` giống lỗi thông thường.
- `VipUpgradeDialog.kt:112–128` chỉ Toast khi purchase callback thất bại. Không có typed auth event tới host, không gọi reauth/RESTORE từ nhánh này.
- Tái hiện cần test integration: mua trả receipt, verifier trả AuthRequired, dialog còn mở → chỉ hiện thông báo; không tiếp tục xác thực lại. Quyền có thể phục hồi bằng restore/sync sau đó; chưa chứng minh mất giao dịch vĩnh viễn hay mất tiền.
- S04 mới sửa kiểu dữ liệu ở verifier, chưa hoàn thành event → UI → same-owner reauth → verify/reconcile receipt. Không sửa bằng mở giao dịch mua mới.

### Credential thiếu/hết hạn trước verify (P2, PROBE FAIL)

- `PlayPurchaseVerifier.kt:324–331`: token null/blank trả `Rejected(INVALID_SIGNATURE_OR_TOKEN)` thay vì AuthRequired, nhầm credential đăng nhập với receipt không hợp lệ.
- Cùng chỗ không gọi `isTokenExpired`, vẫn gửi token biết đã hết hạn tới transport. Restore có expiry guard riêng tại `:644–646`, verify chưa tương đương.
- **P03 mới FAIL:** expected AuthRequired, actual Rejected. **P04 mới FAIL:** token exp=1, expected 0 transport calls, actual 1. Backend fake trả 401; không gọi mạng thật.
- Không có evidence grant VIP trái phép: verifier vẫn cần backend. Đây là lỗi phân loại/recovery và request không cần thiết, không phải bypass thanh toán.

## H02 — P2: Bound continuation chưa bắt buộc đúng attempt và callback cũ xóa request mới

- `VipLoginContinuationHandler.kt:130–132` chỉ so request ID khi cả hai không phải -1; caller không truyền ID vẫn tiêu thụ continuation đã bind.
- `MoreFragment.kt:108` fallback success dùng overload không có attempt ID; PDF/ID-card fallback gọi `handleSignInSuccess(profile)` với default -1. PDF/ID-card cancel/error còn đọc `pendingSignInAttempt` sau khi đã đặt null tại đầu callback.
- Khi success đưa ID khác, handler gọi reset thay vì bỏ qua message không thuộc operation đang giữ. Ngược lại `onSignInCancelled/Error` với ID khác đã return, control C02 PASS.
- **P01 mới FAIL:** old success làm isPending của request mới thành false. **P02 mới FAIL:** bound pending bị consume khi thiếu originating ID. Control C01 matching ID dispatch một lần PASS.
- Đây là fault injection ở production continuation handler + caller omissions CODE VERIFIED; không khẳng định Google SDK đã phát stale callback thật hoặc tài khoản bị xâm nhập. Auth core vẫn có guards riêng, không xóa những guards đó.
- Contract cần sửa: foreign/missing ID không được consume/reset pending khác; truyền originating immutable attempt tới mọi success/cancel/error, modern và fallback; không mượn biến pending mới trong callback cũ.

## H03 — P2: Fallback launch thất bại để continuation pending, lần bấm sau bị bỏ qua

- More `performGoogleSignIn` catch tại `MoreFragment.kt:356–360`; PDF `PdfViewerActivity.kt:156–160`; ID-card `IdCardComposeActivity.kt:154–158`: cancel auth attempt, đặt pendingSignInAttempt=null, hiện lỗi, nhưng không kết thúc vipContinuationHandler.
- Host đã bắt exception nên `AppAuthManager.kt:1085–1100` không nhận exception để gọi onError ở lớp ngoài. API signIn đã nhận request vẫn trả true.
- Lần bấm tiếp theo gặp `if (vipContinuationHandler.isPending) return` ở More `:400`, PDF `:241`, ID-card `:230`.
- CODE VERIFIED, chưa tái hiện Activity/SDK launch exception trên thiết bị. Tình huống: provider modern cần fallback, lấy Intent/launcher ném exception → thông báo lỗi xuất hiện nhưng retry cùng màn không mở provider nữa.
- Cần test host/production seam cho launch failure và retry, không viết test chỉ tự gọi handler.reset() vì như vậy không kiểm tra catch block thực.

## H04 — P2: Context điều hướng auth chưa được tiêu thụ an toàn

- Home truyền expected owner + operationId; MainActivity ghi vào Bundle. More đọc operationId tại `:76` nhưng không dùng để đối chiếu hoặc đánh dấu đã tiêu thụ; authReason cũng chỉ đọc.
- `MoreFragment.kt:83` từ chối mismatched owner chỉ khi **user != null**. Request auth bắt đầu cho A nhưng được nhận sau logout (current null) lại được chấp nhận như login guest mới. Không có originating generation/epoch trong envelope để chặn A→logout→A hoặc request qua process khác.
- CODE VERIFIED, navigation replay/device NOT RUN. Không gọi đây là account takeover; đây là callback/navigation cũ khởi động lại hành động sai thời điểm, không đáp ứng contract operation ownership S03.
- Test `MainActivityNavigationTest` hiện tự tạo Map và lambda `shouldAcceptReauth`, không gọi producer/consumer production. Nó kiểm tra guest(null,null) nhưng bỏ sót expected A/current null.
- Cần tách context guest ban đầu với request existing owner; validate origin trước bất kỳ provider/restore/UI side effect, tiêu thụ một lần và không khôi phục request cũ sau process restart.

## Giới hạn kiểm thử / các việc đã đạt

- 7 regression cũ PASS không chứng minh host binding/receipt recovery toàn bộ đã đạt. Các test có tên Viewer/ID-card còn nhiều test handler/resolver; tên suite không thay cho việc chạy Activity callbacks thật.
- Nhánh RESTORE sau reauth hiện dừng bằng toast nếu lại AuthRequired: đây là giới hạn retry hữu hạn chấp nhận được, **không** yêu cầu tự retry vô hạn. Điểm thiếu H01 là chưa vào reauth lần đầu từ receipt failure.
- Không mở lại lỗi G01/G04 hoặc locale đã sửa. Không mở rộng sang OAuth/signing/RTDN/backend deployment.
- Báo cáo triển khai ghi “100%” cao hơn bằng chứng thực; device gates vẫn NOT RUN, production paths H01/H03 chưa hoàn tất.

## Lệnh tái hiện

```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
# Test task luôn thực thi; không thêm probe nếu thiếu property bên dưới.
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle --console=plain
# 7 probes mới: hiện 4 FAIL / 3 PASS.
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle -PsessionRound3Probes --tests 'com.tscanner.app.VipSessionRound3ProbeTest' --console=plain
```

Kế hoạch xử lý: `PLAN_FIX_VIP_SESSION_ROUND3_GEMINI_2026-10-04.md`. Không sửa XML/log cũ để đổi kết quả; lưu run mới vào thư mục triển khai riêng.
