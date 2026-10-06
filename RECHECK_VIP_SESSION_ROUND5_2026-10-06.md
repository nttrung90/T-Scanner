# Kiểm tra bản sửa VIP session vòng 4 — ngày 06/10/2026

## Kết luận

**Chưa nghiệm thu trọn vẹn U00–U04.** Bản sửa đã cải thiện scope theo operation, dispatch stale event và guest navigation. Phần U01 còn thiếu handshake nhận/từ chối recovery: manager vẫn tiêu lượt trước khi UI/provider thực sự nhận. Đây là yêu cầu có sẵn ở kế hoạch vòng 4, không phải mở rộng phạm vi mới.

Chỉ thêm tài liệu/probe ngoài production; không sửa code ứng dụng. Checkout có nhiều thay đổi đang có, giữ nguyên. Snapshot/source hashes và source trích dòng nằm trong `docs/vip-session-round5-reaudit-20261006/`.

## R01 — [P1] Tiêu lượt recovery trước khi có accepted start

`BillingManager.kt:1031–1035` dùng `authRequiredListeners.isNotEmpty()` và đánh dấu `attemptedRecoveryKeys` ngay khi phát AuthRequired. Listener tồn tại không chứng minh dialog đang hiện hoặc host/provider đã nhận.

`VipPurchaseAuthConsumer.kt:49–50` trả Defer nếu UI inactive, nhưng không trả kết quả cho manager để hoàn lượt. Lần sau cùng operation/receipt, manager phát isRetry=true và consumer `:78–79` trả Stop. Người dùng có thể bị chặn recovery của thao tác dù chưa có lần đăng nhập được nhận.

`VipUpgradeDialog.kt:191–195` gọi callback `(action) -> Unit`; không nhận accepted/rejected và không chuyển operation context. `MoreFragment.kt:453–456` đã có kết quả `performGoogleSignIn()` nhưng chỉ reset continuation khi false, không trả kết quả đến recovery budget. `AppAuthManager.signInWithGoogle` thực sự có nhánh return false khi busy. Vì vậy đổi key sang operation không đủ khép U01.

Biểu hiện thứ hai: hai listener hợp lệ nhận cùng event isRetry=false đều được consumer trả RequestReauth; không có quyền nhận recovery độc quyền. Đây là bằng chứng thiếu coalescing ở manager/consumer, **không phải bằng chứng Google SDK mở hai màn hình hoặc thu tiền hai lần**; AppAuthManager còn guard duplicate riêng.

### Probe và giới hạn bằng chứng

`VipSessionRound5ProbeTest.kt` gọi BillingManager.processPurchase thật → production VipPurchaseAuthConsumer thật. Verifier và ranh giới provider được giả lập; không dùng token hay giao dịch thật.

| Probe | Kỳ vọng | Kết quả |
|---|---|---|
| P01 inactive → active, cùng operation | 1 RequestReauth sau Defer | FAIL: thực tế 0 |
| P02 provider giả lập từ chối → available | 1 accepted start sau lần bị từ chối | FAIL: thực tế 0 |
| P03 hai consumer cùng operation/event | 1 RequestReauth | FAIL: thực tế 2 |
| C01 một consumer active, emit hai lần | 1 RequestReauth; không tự cấp VIP | PASS |

P01/P02 đếm boundary giả lập; không khẳng định đã chạy lifecycle/Google SDK. P03 là fault injection hai listener. Các lỗi này cần sửa contract trước rồi mới chạy device acceptance.

## Thiếu bằng chứng tích hợp của U00/U04

Đã có consumer production được Dialog gọi, nhưng `VipSessionRound3IntegrationTest.kt:54` vẫn hứng event vào biến, sau đó tự gọi restore tại phần cuối test. Test đó không đi qua consumer/provider/host callback nên không phát hiện R01. Kết quả xanh của test này không chứng minh recovery UI hoàn chỉnh. Đây là khoảng trống kiểm thử của cùng R01, không tách thành lỗi runtime khác.

## Kết quả kiểm tra hiện tại

- Full JVM suite: **1.075 tests, 0 failures/errors/skipped**, chạy lại test task, không suy từ báo cáo Gemini. `host-test-results.json` lưu số từng suite trước khi chạy probe.
- Lint/build: BUILD SUCCESSFUL, 9 phút; **0 lint errors, 768 warnings** toàn dự án. Lint chạy phân tích; assembleDebug **UP-TO-DATE**, không báo đã rebuild APK mới. Tổng Gradle: 6 executed, 49 up-to-date.
- Probes chọn lọc: **25 tests, 22 PASS / 3 FAIL**, không errors. Cả **21/21 regression cũ PASS** (7 vòng 2 được chọn, 7 vòng 3, 7 vòng 4); ba FAIL chỉ ở P01/P02/P03 vòng 5. XML và log đi kèm. Vòng 4 đã sửa đúng các tình huống probe cũ, nhưng chưa khép toàn bộ contract U01.
- `adb devices -l`: danh sách trống; **device/Play acceptance BLOCKED_EXTERNAL**. Không kiểm chứng Gmail thật, hết hạn token theo thời gian, Activity lifecycle thực hoặc giao dịch Play.

Lệnh full: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle --console=plain`. Không bật sessionRound3Probes; init chỉ ép chạy test task.

Lệnh probes: `powershell -NoProfile -File docs/vip-session-round5-reaudit-20261006/run-probes.ps1`. Init thêm nguồn probe ngoài app; không sửa Gradle config production. Chạy 4 vòng 5 + 7 vòng 4 + 7 vòng 3 + 7 regression vòng 2. Loại riêng P03 unbound API của vòng 2 theo phạm vi đã chấp nhận trước; không âm thầm bỏ test đang đỏ của vòng này.

Lần probe đầu đã hiện ba assertion đỏ nhưng script PowerShell dùng ErrorActionPreference=Stop ngắt thu log khi Gradle ghi stderr. Đã sửa **script audit** sang Continue rồi chạy lại: compile thành công, Gradle kết thúc do đúng 3 assertion failures; kết quả cuối dùng XML của lần sau. Giữ `probes-first-run.log` để minh bạch.

## Hướng xử lý

Thực hiện `PLAN_FIX_VIP_SESSION_ROUND5_GEMINI_2026-10-06.md`, V00–V03. Điểm nghiệm thu quyết định là production coordinator nhận accepted/rejected, atomic reservation và giữ context tới restore; không chỉ đổi key hoặc thêm test đọc isRetry. Giữ các sửa stale event/navigation đã đạt, không mở lại cấu hình đăng nhập hoặc backend.
