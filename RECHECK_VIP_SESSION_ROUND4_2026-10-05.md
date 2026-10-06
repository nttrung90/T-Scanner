# Kiểm tra bản sửa T00–T05 — VIP session vòng 4

Ngày 05/10/2026, workspace `E:\DU AN AI\T-Scanner`.

## Kết luận

**Các lỗi cụ thể đã kiểm thử ở hai vòng trước đã được sửa:** 14/14 regression phù hợp PASS. Bản sửa token, continuation ID, fallback cleanup và typed AuthRequired có trong code. Tuy nhiên, phần mới quản lý recovery theo receipt và phát event còn ba nhóm lỗi K01–K03 dưới đây. Chưa đủ điều kiện kết luận toàn bộ luồng đã đúng.

Không chỉnh production code hoặc tests hiện có. Chỉ tạo probes, evidence và kế hoạch. Không có giao dịch Google Play thật hoặc thay đổi OAuth/backend/release.

## Kết quả kiểm tra thực chạy

| Gate | Kết quả | Evidence trong `docs/vip-session-round4-reaudit-20261005/` |
|---|---|---|
| Full unit tests, ép test task thực thi | **1.046 tests, 0 failures/errors/skips** | `host.log`, `host-test-results.json` |
| Lint/assembleDebug | SUCCESS, tasks UP-TO-DATE; lint XML 0 errors, 753 warnings | `host.log`; lint XML tại app/build/reports |
| 7 regression vòng 2 phù hợp | 7 PASS | `TEST-com.tscanner.app.VipSessionReauditProbeTest.xml` |
| 7 regression vòng 3 | 7 PASS | `TEST-com.tscanner.app.VipSessionRound3ProbeTest.xml` |
| 7 probes mới | **2 PASS / 5 FAIL** | `TEST-com.tscanner.app.VipSessionRound4ProbeTest.xml` |
| Tổng probe run | 21 tests, **16 PASS / 5 FAIL** | `probes.log` |
| Android device | `adb devices`: danh sách trống | Google provider/Play/device acceptance NOT RUN |

Không dùng P03 unbound API vòng 2 làm acceptance, vì caller hiện đã truyền owner. Không cộng kết quả probe vào 1.046 test suite. Lỗi probe là assertion failures, không phải lỗi compile.

## K01 — P1: Hạn mức reauth bị tiêu thụ trước khi người dùng được xác thực, và tồn tại suốt đời BillingManager

**Evidence code:** `BillingManager.kt:288` lưu `authRecoveryAttemptedTokens` theo purchaseToken. Nhánh AuthRequired tại `:1016–1019` đánh dấu token trước khi xét origin/stale/listener. Set chỉ clear ở destroy `:385`, không gắn với operationId hoặc xác nhận provider đã bắt đầu. `VipUpgradeDialog.kt:144–146` thấy isRetry=true thì chỉ toast và return.

Ba phép thử gọi **production processPurchase**, fake verifier trả AuthRequired:

- **P01 FAIL:** RECONCILE im lặng trước → PURCHASE sau cùng receipt. Event foreground đầu tiên đã có isRetry=true dù không ai xác thực trước đó.
- **P02 FAIL:** PURCHASE lúc không có listener → gắn UI listener → PURCHASE sau. Event đầu tiên người dùng nhận cũng bị coi là retry.
- **P03 FAIL:** hai `BillingOperationContext.operationId` khác nhau cùng receipt; thao tác mới bị dùng lại retry flag của thao tác trước. Điều này xảy ra cả khi người dùng chưa xác thực hoặc đã hủy lần trước.

**Tác động:** đường tự phục hồi từ purchase auth event có thể chỉ hiện thông báo mà không mở reauth cho tới khi BillingManager được tạo lại; thao tác Restore riêng có thể vẫn cứu được giao dịch. Không chứng minh mất tiền/giao dịch vĩnh viễn. Đây là lỗi giới hạn theo receipt lifetime thay vì giới hạn một lần **trong mỗi user operation**, trái T03.

**Control C01 PASS:** event interactive đầu tiên khi không có tiền sử đúng là isRetry=false và không tự cấp VIP.

## K02 — P2: Event auth cũ có thể đến consumer sau khi phiên đã thay đổi

**Evidence code:** `BillingManager.notifyPurchaseAuthRequired` kiểm tra opContext trước tạo/post Runnable; Runnable tại `:1129` chỉ kiểm tra destroyed/scope, không kiểm tra lại owner/generation/epoch trước dispatch. Vòng listeners tại `:1132–1135` cũng không kiểm tra phiên giữa các consumer. `VipUpgradeDialog.handlePurchaseAuthRequired:138–162` kiểm tra lifecycle/isRetry nhưng bỏ qua `event.targetOwnerId`, `sessionGeneration`, `processEpoch` và `operationContext` trước khi gọi reauth; callback host sau đó đọc tài khoản hiện tại.

**P04 FAIL (fault injection):** listener đầu làm session A→B, listener thứ hai vẫn nhận event của A, expected 0 stale deliveries, actual 1. Probe không khẳng định SDK gửi sự kiện trễ thật trên máy người dùng; nó xác nhận dispatch production không chặn invalidation trong khoảng giao event. Tình huống queued UI Runnable sau logout/account switch được chứng minh bằng source, device NOT RUN.

**Tác động:** có thể mở yêu cầu xác thực/restore cho tài khoản hiện tại từ lỗi giao dịch của phiên trước. Chưa có bằng chứng receipt bị chuyển ownership hoặc VIP cấp sai; backend binding hiện có vẫn phải giữ.

**Sửa cần thiết:** validate ngay lúc event được tiêu thụ, không chỉ lúc tạo; discard stale trước toast/dismiss/provider/restore và không thay owner của event bằng user mới.

## K03 — P2: Điều hướng bắt đầu ở guest chưa kiểm tra generation

`VipNavigationValidator` tại `MoreFragment.kt:692` chỉ so generation khi originOwnerId != null. Với guest gốc, generation thay đổi vẫn Accept.

**P05 FAIL:** origin guest generation 1 → người dùng login rồi logout → current guest generation 3, cùng epoch, request cũ chưa consumed; validator trả Accept thay vì Discard. Không tái dựng logic trong test: gọi validator production. Consumer More gọi validator này trước provider/restore, nên nhánh lỗi có đường dùng thực.

**Control C02 PASS:** guest→guest cùng session vẫn Accept. Cần so generation cả guest; không bỏ hỗ trợ login guest. Phân biệt navigation chưa bắt đầu login với continuation sau login: trường hợp sau có chuyển G→G+1 hợp lệ và không được sửa nhầm.

## Coverage còn thiếu trong báo cáo triển khai

`VipSessionRound3IntegrationTest.receiptAuthRequired...` nhận event vào biến rồi gọi `restorePurchases` trực tiếp; không đi qua `VipUpgradeDialog` để quyết định reauth/isRetry và owner. Test fallback tự gọi cancel + handler.onSignInError, không làm launcher throw qua host. Vì vậy test contract PASS chưa chứng minh dialog/host integration PASS.

Nhánh fallback cleanup hiện được nối đúng qua source; không ghi lại H03 là lỗi chưa sửa chỉ vì coverage thiếu. Nhưng K01/K02 cần regression ở production consumer/dispatcher thực, nếu không sẽ tiếp tục lọt qua tests chỉ kiểm tra kiểu event.

## Cách chạy lại

```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound4ProbeTest' --console=plain
```

Init script chỉ thêm probe lúc được truyền vào, không sửa build.gradle. Giữ evidence cũ; lưu kết quả sau sửa vào thư mục triển khai mới. Kế hoạch: `PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md`.
