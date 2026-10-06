# Vòng 6 — chỉ sửa lifecycle logout chồng nhau

Repository E:\DU AN AI\T-Scanner; module :app. Đọc RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md. Lỗi đã xác nhận: startLogout mới đánh dấu coroutine cũ COMPLETED khi coroutine chưa kết thúc. Không làm lại token/Drive/presenter của các vòng trước.

## Quy tắc

- Tuần tự `K00 → K01 → K02 → K03`, mỗi lượt một gói rồi báo cáo/dừng. Không chạy song song các gói.
- Giữ working tree và probe trong build/vip-login-reaudit6. Không reset/clean/format toàn bộ, không đổi SDK/OAuth/chữ ký/Billing hay publish.
- Test gọi production, không sao chép thuật toán. Không gọi “Google SDK thật” cho fake provider Task/JVM.
- Mỗi gói tạo REPORT_VIP_LOGIN_ROUND6_Kxx.md: file sửa, invariant, assertions trước/sau, lệnh/exit/count, giới hạn/runtime chưa chạy.
- Production paths bên dưới tính từ app/src/main/java/com/tscanner/app; test paths từ app/src/test/java/com/tscanner/app.

## K00 — Giữ regression lỗi và controls

Chỉ thêm VipLoginRound6RegressionTest.kt (hoặc tích hợp vào LogoutCoordinatorTest với tên rõ) và báo cáo. Không sửa production.

Chuyển 3 ca trong build/vip-login-reaudit6/VipLoginRound6ProbeTest.kt: A chưa kết thúc, B kết thúc vẫn phải gate; cả hai hoàn tất thì unlock; Task pending tiếp tục gate sau coroutine completion. Giữ expectation gốc.

Nghiệm thu: 1 đỏ/2 controls xanh; ghi test cũ nào đang giả định start B tự kết thúc A để sửa ở K01. Không xóa test cũ để làm xanh. Dừng.

## K01 — Operation không tự kết thúc khi bị thay thế

File: utils/LogoutCoordinator.kt; LogoutCoordinatorTest.kt và regression K00. Không sửa AppAuthManager/SDK wiring trong gói này.

Thực hiện:
1. startLogout chỉ tạo operation mới hoặc hợp nhất theo hợp đồng có chứng minh; không ghi COMPLETED cho RUNNING operation cũ.
2. Terminal state do Job/caller sở hữu operation báo completed/cancelled/failed. Task pending độc lập, callback đúng operation/task, duplicate idempotent.
3. Giữ toàn bộ active operations trong điều kiện await; cleanup record chỉ khi coroutine thật terminal và không còn pending Tasks.
4. Waiter timeout/cancel không đổi state operation. Operation cũ chưa có Task vẫn có thể đang chờ clearCredentialState, không được bỏ.

Regression: A RUNNING không Task, B done → còn gate; đảo thứ tự completion; A có Task pending; B canceled-before-start; duplicate completion; nhiều waiters; timeout rồi complete. Sửa test cũ để explicit complete/cancel A trước khi mong unlock, không nới tiêu chí an toàn.

Nghiệm thu: regression K00 xanh và suite coordinator xanh. Báo rõ còn K02 tích hợp để tránh giữ operation RUNNING vĩnh viễn khi scope chưa start. Dừng.

## K02 — Chứng minh lifecycle của AppAuthManager khi clearCredentialState còn chờ

File: utils/AppAuthManager.kt vùng signOut/signIn; AppAuthGoogleLogoutIntegrationTest.kt; coordinator chỉ adapter cần thiết đã báo rõ. Cho phép thêm seam nhỏ cho CredentialManager.clearCredentialState nếu JVM không điều khiển được; seam phải được production thật dùng.

Thực hiện:
- Mỗi cleanup Job kết thúc/cancel-before-start/throw phải báo đúng operation, không dựa vào operation active mới nhất. Không đánh dấu provider Task completed khi coroutine timeout/cancel.
- Test quan trọng: logout A bị giữ tại clearCredentialState trước khi tạo GoogleSignIn Task; logout B kết thúc; login C yêu cầu. C chưa được gọi provider/commit đến khi A thật sự dừng và mọi Task liên quan đã xong.
- Thả A tiếp tục tạo Task có kiểm soát, giữ Task pending; C vẫn phải chờ. Hoàn tất Task/Job thì login mới tiếp tục theo hợp đồng timeout/retry hiện có.
- Không dùng sleep; dùng deferred/latch và TaskCompletionSource. Có control normal logout/login và failed-before-task-start.
- Nếu invalidation quyết định bỏ A, phải bảo đảm A không còn provider side effect đang chạy hoặc sẽ chạy; recheck đơn lẻ không thay thế tracking completion của thao tác đã phát ra.

Nghiệm thu: integration chạy qua signOut/signIn production, covering cả coroutine chưa có Task và Task đã tạo; old tests vẫn xanh. Token/owner guards không đổi. SDK/device thật còn gate riêng. Dừng.

## K03 — Tổng hợp và báo cáo đúng mức

Không sửa production; nếu tìm lỗi mới ghi gói riêng. Chạy mới toàn bộ tests, lint, assembleDebug. Kiểm tra assertions các vòng trước không bị bỏ. Ghi số lượng thực tế; baseline 718 không phải mục tiêu cứng.

Đính chính REPORT_VIP_LOGIN_ROUND5_J04.md hoặc liên kết báo cáo mới: host xanh không phải “sản xuất hoàn chỉnh”; số lint lấy XML; bỏ mô tả thành phần không có thật nếu không đối chiếu được tên mã. Giữ lịch sử kết quả trước sửa.

Thiết bị/Play gate: logout A chậm tại credential clear, logout B, login ngay; xoay/đóng màn hình giữa cleanup; Task timeout rồi hoàn tất; login/Drive grant thật trên đúng bản Play. Thiếu thiết bị/Console thì NOT RUN, không kết luận lỗi ban đầu hết, không đổi OAuth theo phỏng đoán.

## Lệnh và prompt

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipLoginRound6RegressionTest --offline --console=plain
# K03
.\gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Dùng tên test thực tế nếu tích hợp vào suite có sẵn. Không wildcard Windows/gradle clean.

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_FIX_VIP_LOGIN_ROUND6_SMALL_MODEL_2026-09-25.md và RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md. Khi được giao triển khai, làm DUY NHẤT K00.
Giữ working tree và build/vip-login-reaudit6. Đưa 1 probe lỗi + 2 controls thành regression lâu dài gọi production, giữ nguyên invariant. Không sửa production. Chạy test và ghi REPORT_VIP_LOGIN_ROUND6_K00.md với số đỏ/xanh thực tế và giới hạn. Dừng, không làm K01.
```

Các gói sau: đọc kế hoạch và báo cáo trước, chỉ làm một Kxx đúng phạm vi/nghiệm thu, báo cáo rồi dừng.
