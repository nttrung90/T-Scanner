# Gemini (Antigravity): VIP session vòng 3 — T00–T05

Ngày 04/10/2026; workspace `E:\DU AN AI\T-Scanner`.

Hoàn tất bàn giao ngày 05/10/2026 (Asia/Saigon). Giữ tên file theo ngày bắt đầu kiểm tra để khớp evidence.

Đọc `RECHECK_VIP_SESSION_ROUND3_2026-10-04.md` và `docs/vip-session-round3-reaudit-20261004/` trước. Kế hoạch này chỉ khép H01–H04, giữ các sửa vòng 2 đã đạt. Không mở rộng sửa toàn bộ auth/Billing.

Thực hiện tuần tự T00→T05. Mỗi gói có checkpoint với command/evidence, chỉ chuyển khi acceptance đạt; không hỏi lại từng gói. Không giao nhiều agent sửa cùng file. Giữ staged/unstaged/untracked, không reset/clean/stash, không đổi OAuth/package/signing/R8/version/dependencies/backend, không mua thật, commit/push/phát hành.

## T00 — Khóa bằng chứng và hợp đồng trước khi sửa

**Scope:** docs/tests. Đọc source hiện tại, lưu git status và hashes các file sẽ sửa vào `docs/vip-session-round3-fix/`. Chạy 7 probes mới nguyên bản; giữ 4 failure và 3 controls làm mốc. Chạy 7 regression phù hợp vòng 2, không đưa unbound P03 cũ làm tiêu chí sai.

Chuẩn bị 3 test integration đang thiếu, có tên và kịch bản cụ thể:

1. `receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase`: fake verifier trả AuthRequired sau receipt PURCHASED, production Billing event→host quyết định reauth RESTORE/receipt; assert owner A, 1 auth request, 0 extra launch, không grant VIP trước verify success.
2. `fallbackLaunchFailureAllowsRetryOnSameHost`: provider modern chuyển fallback, launcher throw; production catch/terminal xử lý, click tiếp theo được nhận. Chạy contract cho More, PDF, ID-card.
3. `navigationFromOwnerARejectedAfterLogoutAndOnReplay`: producer tạo request A; trước consumer logout; consumer trả ignored, 0 provider/restore calls; request hợp lệ tiêu thụ đúng một lần.

Nếu UI runner hiện tại chưa hỗ trợ, tách một production coordinator/dispatcher nhỏ ở gói sở hữu; **handler thật của từng host phải gọi nó**. Test gọi chính coordinator đó, fake chỉ provider/Billing/navigation boundary. Bổ sung instrumentation cho việc click/callback binding nếu JVM không chạy host được; ghi NOT RUN nếu chưa chạy được. Không tự dựng lại if/Map trong test thay producer/consumer. Không thêm seam chỉ được test gọi mà UI vẫn xử lý riêng.

**Acceptance/checkpoint:** mỗi test phải có đường production xác định và expected outputs; tests chưa thể chạy UI ghi rõ, không báo PASS. Bàn giao contracts dưới đây cho T01–T04.

## T01 — Bắt buộc identity của attempt khi consume continuation (H02)

**Files:** `VipLoginContinuationHandler.kt`, `MoreFragment.kt`, `PdfViewerActivity.kt`, `IdCardComposeActivity.kt`, focused tests. `AppAuthManager.kt` chỉ khi cần chuyển immutable attempt ra callback, không thay login provider policy.

Hợp đồng phải thực hiện:

| Pending | Kết quả đến | Hành động |
|---|---|---|
| Bound B | ID A khác B hoặc ID thiếu | Ignore; giữ nguyên B, không dispatch/reset B |
| Bound B | B, owner/generation/epoch đúng | Consume một lần; dispatch action đã lưu |
| Bound B | B nhưng session/owner/epoch đã invalidated | Kết thúc B an toàn, không dispatch |
| Không pending | Bất kỳ kết quả | Không side effect |
| Bound B | Cancel/error của A khác B | Ignore; giữ B |

- Các overload legacy thiếu ID không được consume bound request. Chỉ cho phép legacy ở trạng thái thực sự unbound nếu còn caller hợp lệ; production VIP phải bound.
- More fallback phải truyền `attempt` đã capture ở đầu callback, không dùng overload thiếu ID. PDF/ID-card truyền `attempt.requestId` vào success/cancel/error trước khi xóa state. Modern callbacks capture origin của riêng invocation, không lấy ID từ pending field có thể đã thuộc request mới.
- Giữ guest→A generation transition hợp lệ, same-owner reauth và process-death rejection; không bỏ guard để làm test xanh.
- **Regression:** P01/P02 mới xanh, C01/C02 xanh; callback A tới trong lúc B pending, fallback modern cùng contract, cancel/error A không xóa B, guest/restore và stale process.
- **Acceptance/checkpoint:** bảng mọi success/cancel/error caller truyền exact attempt; tests red→green. Không tuyên bố host PASS chỉ từ handler test.

## T02 — Kết thúc đúng continuation khi fallback không mở được (H03)

**Depends:** T01. **Files:** ba host ở T01 + terminal seam dùng chung nếu cần, focused tests. Không sửa backend.

- Một đầu mối sở hữu terminal error cho cùng attempt. Khi getGoogleSignInIntent hoặc launcher throw: cancel đúng auth attempt, clear đúng continuation theo T01, clear đúng pending field, hiện lỗi một lần, trả UI về trạng thái retry.
- Có thể để exception đi về onError của auth manager hoặc xử lý hoàn toàn tại host; chọn một cách thống nhất, không vừa catch/nuốt vừa trông chờ lớp ngoài dọn dẹp. Rethrow coroutine CancellationException theo lifecycle policy, không biến thành technical fallback retry.
- Nếu host biến mất trước launch, kết thúc state thuộc host/attempt cũ, không cleanup request mới.
- **Regression:** `fallbackLaunchFailureAllowsRetryOnSameHost` cho More/PDF/ID-card; intent creation throw và launcher throw riêng; double error/cancel; retry tạo đúng một attempt mới; draft PDF/ID-card giữ nguyên.
- **Acceptance/checkpoint:** sau fail, auth busy=false và continuation pending=false cho request đã fail; click tiếp theo được nhận, không logout/clearCredentialState. Đây là tiêu chí quan trọng hơn chỉ thấy error toast.

## T03 — Khép auth recovery của giao dịch đã có receipt (H01)

**Depends:** T02. **Files:** `PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt`, `BillingManager.kt`, `VipUpgradeDialog.kt`, UI recovery dispatcher/host tối thiểu và tests. Client only.

### Hợp đồng verifier

- Endpoint chưa cấu hình → MissingBackendGate.
- Purchase token rỗng/SKU không hợp lệ → receipt/catalog rejection như hiện có.
- Với endpoint hợp lệ và credential provider đã gắn: login token null/blank/malformed/expired → **AuthRequired trước transport**, không receipt rejection.
- Token fresh + HTTP401 → AuthRequired. 403 theo backend contract, không tự kết luận mọi 403 là hết hạn hay mọi 403 là ownership conflict; network/429/5xx giữ transient semantics.
- Token retrieval vẫn phải giữ owner/generation guards; không log token, không bypass signature verification backend.
- **Tests:** P03/P04 mới xanh; fresh401 C03 xanh; null/blank/malformed/expired mỗi case 0 transport calls; business rejection/network vẫn phân biệt.

### Hợp đồng event → UI → recovery

- Không hạ AuthRequired thành purchase failure string trước host. Cung cấp typed event/callback mang immutable operation context, owner/generation/epoch, receipt identity và trạng thái đã retry. Giữ tương thích các listener cũ nhưng không phát hai terminal events.
- Foreground purchase còn host hợp lệ: event yêu cầu reauth đúng A; reauth thành công → verify/reconcile receipt cũ (RESTORE/recovery), **không** launchBillingFlow và không mở hộp nâng cấp để mua lại.
- Background sync hoặc host không còn hợp lệ: giữ deferred recovery, không tự bật Google UI. Dựa Play query/reconciliation để recover receipt sau restart; không cần invent persistence schema mới nếu đường hiện có đủ, nhưng phải test thực.
- Mỗi user operation tối đa 1 lần reauth + 1 recovery retry; lần AuthRequired thứ hai kết thúc với retry chủ động. Cancel giữ tài khoản/receipt, không grant/ack sớm. Logout/đổi owner vô hiệu hóa recovery cũ.
- **Tests:** integration T00 từ production manager event tới production host dispatcher; fail nếu typed event bị đổi về generic toast. receiptAuth→reauth→same receipt success; second401; cancellation; process restart query; background silent; 0 extra launch, 0 early grant, owner không đổi.
- **Acceptance/checkpoint:** bằng chứng cả parser lẫn event-to-host, không coi chỉ `VerificationResult.AuthRequired` PASS là hoàn tất T03.

## T04 — Điều hướng auth có origin, tiêu thụ một lần (H04)

**Depends:** T03. **Files:** `HomeFragment.kt`, `MainActivity.kt`, `MoreFragment.kt`, navigation request producer/consumer production và tests.

Envelope tối thiểu: `operationId`, `action`, `originOwnerId` (null chỉ cho guest ban đầu), `originGeneration`, `processEpoch`, `authRequiredReason`. Dùng dữ liệu tại origin; không tái tạo owner mới khi message cũ đến.

Consumer trước mọi UI/provider/restore call:

| Origin → current | Quyết định |
|---|---|
| A → A, generation/epoch khớp, op chưa consume | Nhận đúng một lần |
| A → null sau logout | Bỏ request, không chuyển thành guest login |
| A → B, hoặc A→logout→A với generation khác | Bỏ request |
| Guest → Guest cùng session/epoch | Nhận login guest bình thường |
| Epoch khác hoặc op đã consume | Bỏ request |

- Xác thực origin rồi mới consume op. Không dùng operationId đọc xong bỏ; không tăng global boolean cho toàn app. Giữ hoặc kết thúc đúng operation khi consumer bận, không chiếm continuation khác.
- Backend auth-required với local token còn hạn vẫn force reauth cho **origin còn hợp lệ**. Sau logout không ép login lại từ request cũ.
- **Regression:** integration T00, replay cùng envelope, rotation, process restart, A→null/A→B/A→A mới, guest đúng luồng, backend401 fresh token.
- **Acceptance/checkpoint:** tests gọi producer/consumer thực được UI dùng; xóa `forceReauth`/owner/origin validation trong bản thử nghiệm phải làm regression fail. Không đổi source thực để mutation rồi quên khôi phục; dùng isolated test technique hoặc nêu coverage evidence cụ thể.

## T05 — Tổng kiểm chứng, báo cáo và device gate

**Depends:** T04. Chạy focused tests, 7 probes mới và 7 regression cũ phù hợp, rồi full unit/lint/assemble offline. Đọc XML, phân biệt executed và UP-TO-DATE. Baseline 1.043/0 failures không phải con số ép khớp.

Kiểm tra binding của More/PDF/ID-card và dialog qua UI test hoặc production dispatcher tests + source trace rõ ràng; không gọi bộ test Map/lambda là host integration. Giữ bộ probe read-only lịch sử, thêm permanent regression tương ứng trong test source app.

Device gate: expired A Free từ các nút VIP; cancel/nhầm B; fallback launch failure qua debug test seam an toàn; receipt auth failure ở môi trường test; reauth không mua mới; logout khi navigation queued; rotation/process death. Dùng license tester được phép, không mua thật, không gỡ app/clear-data. Thiếu thiết bị/Play access thì NOT RUN và tổng hợp nhu cầu một lần.

Mỗi gói lưu `docs/vip-session-round3-fix/REPORT_Txx.md` + `PROGRESS.md`: evidence trước/sau, files sửa tách phần có sẵn, command/XML results, acceptance đạt/chưa đạt, NOT RUN, bàn giao. Cuối cùng `REPORT_FINAL.md` đối chiếu H01–H04 và 3 integration tests T00. Không ghi “100%” khi UI/device gate hoặc event path chưa kiểm chứng.

**Điểm dừng cuối:** bàn giao để review, không tự commit/push/phát hành.

## Prompt giao Gemini

```text
Trong E:\DU AN AI\T-Scanner, đọc:
1. PLAN_FIX_VIP_SESSION_ROUND3_GEMINI_2026-10-04.md
2. RECHECK_VIP_SESSION_ROUND3_2026-10-04.md
3. docs/vip-session-round3-reaudit-20261004/ — nhất là VipSessionRound3ProbeTest.kt, audit.init.gradle, new-probes.xml, previous-regressions.xml và probes.log.
4. PLAN_FIX_VIP_SESSION_ROUND2_GEMINI_2026-10-04.md để giữ các sửa đã đạt.

Thực hiện tuần tự T00–T05. Chạy lại probe trước khi sửa, giữ controls và regression cũ; sau mỗi gói lưu REPORT_Txx.md/PROGRESS.md rồi tự chuyển khi acceptance đạt. Không hỏi lại từng gói.

Khép đường receipt AuthRequired → same-owner reauth → recovery receipt, không mua lại. Sửa verify khi token thiếu/hết hạn; bắt buộc đúng originating attempt; fallback launch fail phải cho retry; bỏ navigation cũ sau logout/replay. Làm đúng các bảng contract và integration tests trong kế hoạch.

Không sửa expectation chỉ để xanh, không copy logic UI vào test, không coi parser PASS là event-to-host PASS. Giữ code có sẵn; không logout ẩn, bỏ guard, cấp VIP sớm, đổi OAuth/signing/R8/version/dependencies/backend, mua thật, commit/push/release. Thiếu device/Play ghi NOT RUN/BLOCKED_EXTERNAL; hoàn tất host độc lập và báo cáo rõ gates còn thiếu.
```
