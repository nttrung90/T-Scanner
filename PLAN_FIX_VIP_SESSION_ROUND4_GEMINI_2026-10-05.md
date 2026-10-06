# Gemini (Antigravity) — VIP session vòng 4, U00–U04

Workspace `E:\DU AN AI\T-Scanner`, ngày 05/10/2026.

Đọc `RECHECK_VIP_SESSION_ROUND4_2026-10-05.md` và `docs/vip-session-round4-reaudit-20261005/`. Chỉ sửa K01–K03 và coverage liên quan. **Giữ 14 regression phù hợp của hai vòng trước đã PASS**, không mở lại OAuth, token verification backend hoặc login provider đã hoạt động.

Thực hiện tuần tự, mỗi gói lưu report và kiểm chứng acceptance trước gói sau; không hỏi lại từng gói. Không nhiều agent sửa cùng file. Giữ thay đổi có sẵn; không reset/clean/stash, đổi signing/package/version/R8/dependencies/backend, mua thật, commit/push hoặc phát hành. Chưa có device thì hoàn tất host work độc lập và ghi NOT RUN rõ ràng.

## U00 — Baseline và khóa đúng đường consumer để test

**Scope:** tests/docs, thiết kế seam; production seam thuộc U01/U02. Lưu git status/hashes, chạy 7 probe vòng 4 nguyên bản: hiện 5 FAIL/2 PASS. Giữ tên test và assertion; không sửa kỳ vọng để hợp thức hóa lỗi.

Chuẩn bị production dispatcher/consumer nhỏ được **VipUpgradeDialog.handlePurchaseAuthRequired gọi thực** nếu JVM không chạy được Dialog. Consumer nhận immutable event + current session snapshot + lifecycle + operation recovery state, xuất quyết định Ignore/Defer/RequestReauth/Stop. UI chỉ thực hiện quyết định. Test gọi consumer này; không dựng một hàm giống nó chỉ trong test.

Kiểm thử bắt buộc nối manager event→consumer→fake provider/restore, đếm provider starts, restore calls và extra launch. Không thay bước này bằng cách nhận event vào biến rồi tự gọi restore. Nếu dùng instrumentation để kiểm tra UI binding mà chưa chạy được thì ghi NOT RUN; không báo host integration hoàn tất bằng tên test.

**Checkpoint:** `docs/vip-session-round4-fix/REPORT_U00.md` và `PROGRESS.md`; bảng tests map K01–K03, cách seam được UI gọi. Không sửa các report/evidence cũ.

## U01 — Recovery budget theo user operation và lúc thực sự được nhận (K01)

**Depends:** U00. **Files:** `BillingManager.kt`, recovery state/dispatcher nhỏ mới nếu cần, `VipUpgradeDialog.kt` và host callbacks tối thiểu để truyền operation, tests.

Không dùng purchaseToken singleton set làm hạn mức reauth. Tách receipt identity để query/dedupe khỏi identity của thao tác người dùng. Key recovery gồm origin operation ID + owner + generation/epoch + receipt identity; duplicate callbacks cùng operation phải coalesce nguyên tử.

Hợp đồng trạng thái:

| Tình huống | Quyết định / budget |
|---|---|
| SYNC/RECONCILE gặp auth, không có user recovery | Defer; không mở UI và không tiêu lượt foreground |
| Stale event hoặc không có host hợp lệ/callback nhận việc | Ignore/Defer; không tiêu lượt |
| Event hợp lệ, host chấp nhận bắt đầu reauth | Reserve/commit đúng một lượt của operation; không đánh dấu thành công chỉ vì listener.invoke trả về |
| Provider không khởi chạy do busy/host biến mất | Kết thúc hoặc release reservation đúng operation; retry thủ công sau đó hoạt động |
| Reauth thành công | Recover receipt/RESTORE một lần; 0 extra launchBillingFlow |
| Lỗi auth lần hai trong cùng operation | Stop, cho retry thủ công; không lặp provider |
| Người dùng hủy/thất bại/kết thúc rồi bắt đầu thao tác mới | Operation mới có budget mới dù receipt cũ; không bắt restart app |

Callback phải có kết quả nhận/từ chối/terminal hoặc cơ chế tương đương rõ ràng. Khi nối reauth→restore, giữ operation origin/retry state; không tạo ID mới mỗi automatic retry để vượt giới hạn. Không retry mua mới, cấp VIP hoặc ack sớm. Background receipt vẫn recover được bằng Play query/reconciliation.

**Tests bắt buộc:** P01/P02/P03 vòng 4 xanh, C01 xanh; same-op duplicate event; no listener/hidden host/provider busy; cancellation→new explicit operation; second401 same-op dừng; success same receipt; background không mở UI; concurrent events chỉ reserve một lần. Assert ở production consumer/provider boundary, không chỉ isRetry flag.

**Acceptance/checkpoint:** 1 provider attempt/operation tối đa, new operation được phép retry, deferred event không tiêu budget, 0 extra purchase launch, không cần destroy singleton để reset. Report nêu identity và terminal cleanup cụ thể.

## U02 — Validate event tại thời điểm tiêu thụ (K02)

**Depends:** U01. **Files:** `BillingManager.kt`, production consumer U00/U01, `VipUpgradeDialog.kt`, focused tests. Không thay backend owner binding.

- Giữ immutable event origin. Kiểm tra owner, generation, epoch, operation ownership ngay trong main Runnable trước dispatch, và ngay trong consumer trước mọi toast/dismiss/reauth/restore.
- Nếu listener trước làm đổi session, dừng event cũ trước consumer tiếp theo. Không chỉ guard trước Handler.post. Không suy ra event owner từ AppAuthManager tại thời điểm callback đến.
- UI callback sang host giữ validated expected owner/context. Nếu provider chỉ được mở sau một bước async nữa, kiểm tra lại trước mở. Event missing required origin không được coi là current session tự động; nếu hỗ trợ legacy, giới hạn rõ chỉ thông báo không có auth side effect.
- Stale event không tiêu recovery budget, không cleanup/busy-reset operation mới; cancel cũ không cancel mới.

**Tests:** P04 vòng 4 xanh; event A queued → logout hoặc switch B → drain, provider/restore/toast/dismiss count=0; A→logout→A generation khác; epoch khác; first consumer invalidates session; matching event được nhận một lần. Dùng fake UI scheduler/dispatch boundary để test enqueue/drain production path, không gọi private function bản sao.

**Acceptance/checkpoint:** không còn auth effect từ stale event ở cả manager và consumer; verify owner guards hiện có vẫn giữ. Probe P04 là fault injection, không báo đã tái hiện Google tính tiền sai.

## U03 — Navigation guest phải cùng session trước khi login bắt đầu (K03)

**Depends:** U02. **Files:** `VipNavigationValidator` trong `MoreFragment.kt`, producer MainActivity/Home chỉ nếu cần bảo đảm envelope đầy đủ, tests.

| Origin navigation → current | Quyết định |
|---|---|
| Guest G/epoch E → guest G/E | Accept một lần |
| Guest G/E → guest G+2/E sau login/logout | Discard |
| Guest G/E → A ở phiên khác trước consumer | Discard request cũ, không tự gắn action cho A |
| A G/E → A G/E | Accept một lần |
| Owner/generation/epoch đổi hoặc operation consumed | Discard |

So generation cho cả guest và existing owner. Origin ở thời điểm tạo navigation không phải generation sau sign-in. **Không sửa VipLoginContinuationHandler để chặn chuyển guest G→G+1 của chính attempt thành công**; navigation validation và login continuation là hai thời điểm khác nhau.

**Tests:** P05/C02 vòng 4 xanh; guest→A trước consume; guest→A→logout; existing-owner cases và replay từ vòng trước; guest login continuation P01/P02 vòng 2 vẫn xanh. Test gọi validator và ít nhất một producer/consumer path thực, không tự tái tạo lambda/map.

**Acceptance/checkpoint:** request guest cũ không khởi động provider/restore ở phiên mới; guest flow bình thường và reauth A không hồi quy.

## U04 — Tổng kiểm chứng và bàn giao

**Depends:** U03. Chạy 7 probes vòng 4, 7 vòng 3, 7 regression vòng 2 phù hợp (không dùng unbound P03 cũ), plus permanent regressions và full test/lint/assemble offline. Tách executed/UP-TO-DATE, count XML thật. Baseline 1.046 tests không phải số phải ép khớp.

Chạy integration manager→consumer→provider/restore thực sự; khi UI runner không có, test dispatcher production + source binding evidence và instrumentation NOT RUN phải được ghi riêng. Không gọi contract tự thao tác cancel/restore là host end-to-end PASS.

Device checklist: A Free hết hạn, background sync trước click, đóng dialog trước auth event, cancel→retry cùng receipt, đổi tài khoản khi event đang queued, guest login/logout khi navigation đang chờ, receipt recovery không mở mua mới. Chỉ test Google Play với account/license tester được phép, không mua thật/clear-data; thiếu thiết bị ghi BLOCKED_EXTERNAL, không ghi hoàn tất 100%.

Mỗi gói ghi `REPORT_Uxx.md` + `PROGRESS.md` trong `docs/vip-session-round4-fix/`: files và scope, red→green, commands/log/XML, acceptance, NOT RUN, bàn giao. Cuối cùng `REPORT_FINAL.md` đối chiếu K01–K03 và báo cáo source/host/device riêng. Dừng ở review, không commit/push/phát hành.

## Prompt giao Gemini

```text
Trong E:\DU AN AI\T-Scanner, đọc:
1. PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md
2. RECHECK_VIP_SESSION_ROUND4_2026-10-05.md
3. docs/vip-session-round4-reaudit-20261005/ — nhất là VipSessionRound4ProbeTest.kt, audit.init.gradle, các TEST-*.xml và probes.log.
4. PLAN_FIX_VIP_SESSION_ROUND3_GEMINI_2026-10-04.md để giữ các sửa đã đạt.

Thực hiện tuần tự U00–U04. Chạy probe trước sửa, giữ các control/regression cũ. Sau mỗi gói ghi REPORT_Uxx.md/PROGRESS.md và tự chuyển khi acceptance đạt, không hỏi lại từng gói.

Chỉ khép K01–K03: budget recovery theo user operation, không tiêu lượt bởi background/no UI; kiểm tra event origin lúc dispatch/consume; chặn navigation guest đã khác session. Giữ 1 reauth tối đa mỗi operation, nhưng cho phép người dùng retry bằng operation mới. Receipt recovery không được mở mua mới.

Test phải nối production manager→consumer→provider/restore, không tự gọi restore trong test để giả end-to-end. Không sửa kỳ vọng để xanh. Giữ code có sẵn; không bỏ auth/owner guards, logout ẩn, cấp VIP sớm, đổi OAuth/signing/R8/version/dependencies/backend, mua thật, commit/push/release. Thiếu thiết bị/Play ghi NOT RUN/BLOCKED_EXTERNAL. Báo files, commands, counts và gates chưa chạy.
```
