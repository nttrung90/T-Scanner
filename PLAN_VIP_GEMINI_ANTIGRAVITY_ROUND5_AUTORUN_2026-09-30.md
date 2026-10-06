# Gemini (Antigravity) — sửa VIP vòng 5, tự chạy tuần tự

## Mục tiêu và cách thực hiện

Đọc `RECHECK_VIP_FULL_ROUND5_2026-09-30.md` và bộ chứng cứ `docs/vip-round5-20260930/`. Sửa đủ F01–F08; bảo toàn các sửa đúng từ vòng trước. Chạy **U00 → U01 → … → U11** tự động, không hỏi người dùng sau từng gói. Không coi 928/89 test cũ xanh là đã hoàn thành.

Đây là kế hoạch giao cho Gemini trong Antigravity, chưa phải xác nhận agent Gemini đã được khởi chạy. Trong phiên lập kế hoạch, Codex không sửa sản phẩm.

Quy tắc chung cho mọi gói:

1. Đọc trạng thái checkout và `PROGRESS_VIP_R5_AUTORUN.md`; giữ toàn bộ thay đổi người dùng, không reset/clean/stash/drop. Không tự commit/push/deploy/publish hoặc dùng giao dịch trả tiền thật.
2. Mỗi gói có phạm vi file, test red trước sửa, acceptance và báo cáo riêng. Không chạy nhiều gói sửa song song; file dùng chung được bàn giao theo thứ tự.
3. Chuyển test production-boundary vào suite bền vững; giữ đối chứng dương. Không viết lại giả lập để bắt chước logic muốn có, không xóa/nới assertion để che lỗi.
4. Sau khi đạt focused tests, ghi `REPORT_VIP_R5_Uxx.md` gồm file thay đổi, test trước/sau, số PASS/FAIL, contract đã đổi, giới hạn, gói tiếp theo; cập nhật progress rồi tự làm gói tiếp. Đây là checkpoint nội bộ, **không phải điểm chờ người dùng**.
5. Khi lỗi kỹ thuật, tự chẩn đoán/sửa và chạy lại đúng kiểm tra liên quan. Không lặp lại rộng toàn suite sau mỗi chỉnh sửa nhỏ.
6. Thiếu thiết bị/credential/môi trường: ghi BLOCKED_EXTERNAL/NOT_RUN chính xác, làm tiếp các phần độc lập. Gói phụ thuộc vào code chưa sửa thì phải để BLOCKED_DEPENDENCY, không giả PASS. Chỉ hỏi một lần cuối khi có blocker thật cần người dùng quyết định.
7. Không thay SKU, giá, gói thuê bao, signing, secrets, release config, kiến trúc auth hay storage lớn ngoài nhu cầu sửa. Không tạo fallback cấp VIP offline/local. Không ghi bearer token/receipt đầy đủ vào report.

## U00 — Baseline và bảo toàn regression

- Files: `app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`, `backend/billing-verifier/test/round5-regression.test.ts`, progress/report; chưa sửa production.
- Đọc hai file probe trong `docs/vip-round5-20260930/`. Chuyển sang suite test, đổi tên class/import thích hợp, giữ test cũ. Snapshot git status trước khi làm, tách thay đổi có sẵn.
- Chạy lại 16 probes: dự kiến Android8 FAIL/1 PASS, backend6 FAIL/1 PASS. Nếu checkout đã khác, xác minh lại từng hành vi; ghi rõ ca nào đã sửa, không cố tạo FAIL giả.
- Baseline thông thường: Android928/928, backend89/89, lint0 errors757warnings, assemble PASS. Chạy focused red trước; không cần full lint lặp vô ích.
- Acceptance: red xuất phát từ logic sản phẩm, không phải fixture chưa connect, compile/import hay môi trường. Bàn giao map A01–A09/B01–B07 ↔ F01–F08.
- Prompt gói: `Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND5_AUTORUN_2026-09-30.md; thực hiện U00 rồi tự tiếp U01.`

## U01 — Lifecycle Play V2 nhất quán (F02)

- Depends U00. Files: backend `src/googlePlayClient.ts`, `src/verifier.ts`, `src/types.ts`, shared mapping helper nhỏ nếu cần, tests. Nếu chỉnh RTDN để dùng helper, bàn giao contract cho U03, chưa sửa concurrency ở đây.
- Loại bỏ đường V2 thiếu state rồi rơi xuống mapping v1. Validate enum và lineItem/expiry theo từng state, không blanket reject các pending hợp lệ chỉ vì không có grant time. Không đánh đồng ON_HOLD với payment pending lúc mua.
- Dùng lifecycle authoritative trước fallback v1. Lưu ON_HOLD/PAUSED/tombstone theo owner/version; không để return pending sớm giữ paid cache.
- Tests: B03/B04; ACTIVE/CANCELED/GRACE/PAUSED/ON_HOLD/PENDING/EXPIRED, missing/unknown/numeric state theo contract đã chọn; pending purchase canceled + linked token theo API thật; sai SKU, malformed payload. Có seed ACTIVE trước trạng thái không còn quyền.
- Acceptance: không cấp paid quyền từ state thiếu/hỏng, không bỏ invalidation. Giữ renewal, grace, canceled còn hạn. Đối chiếu tài liệu Google đã dẫn trong audit; không suy đoán enum số.
- Handoff: mapping + response types/chính sách malformed cho U02/U03/U05.

## U02 — CAS có expected-absent và xử lý conflict (F01 B01/B02)

- Depends U01. Files: backend `src/storage/types.ts`, `src/storage/sqliteDriver.ts`, `src/store.ts`, `src/verifier.ts`, regression storage/verifier. Không đổi schema lớn nếu không cần.
- Phân biệt explicit expected-absent với không yêu cầu CAS; mọi query Play chụp version trước await. Áp dụng cho active/expired/revoked/paused/hold, cả subscription và inapp.
- Conflict không trả old ACTIVE như một lần xác thực tươi thành công. Re-read/refetch có giới hạn; hết budget trả trạng thái retryable rõ ràng, không viết payload cũ. Định nghĩa contract để U03 dùng lại.
- Tests: B01/B02 + biến thể renewal đến sau revoke, initial insert đồng thời, owner conflict, inapp refund, hai store connections cùng SQLite file, persistence/restart, outbox rollback vẫn atomic.
- Acceptance: không hồi sinh quyền bởi response cũ; không bỏ mất update mới vì “first commit wins”; không quy định inactive luôn thắng và khóa renewal. Không mint version ở client.
- Handoff: expected-version contract, conflict/error behavior, retry bound và outbox invariant.

## U03 — RTDN và verify dùng chung cơ chế đồng thời (F01 B07)

- Depends U02. Files: backend `src/rtdnHandler.ts`, storage/verifier helper tối thiểu từ U02, RTDN tests.
- Chụp expectedVersion trước Play query, commit qua CAS từ U02. Event timestamp chỉ dùng ordering event, không thay version guard giữa RTDN và verify. Dùng mapping authoritative U01.
- Khi retry/conflict thất bại, không đánh dấu event processed rồi làm mất redelivery. Trạng thái/payload đã commit và kết quả trả HTTP phải nhất quán.
- Tests: B07, hai handler instance, RTDN↔verify cả hai thứ tự, duplicate/out-of-order event, backend restart, transient upstream retry; không chỉ test Map trong một process.
- Acceptance: không stale resurrection, duplicate idempotent, retry không mất event. Bàn giao result taxonomy cho restore U04.

## U04 — Restore backend không bị metadata client vô hiệu hóa (F03)

- Depends U03. Files: backend `src/verifier.ts`, restore request/response types nếu cần, backend restore route validation trong `src/index.ts`, tests.
- Known token dùng bound product/type/owner trong DB. Candidate cùng token mà khác metadata không được ghi đè nguồn tin cậy hoặc bỏ refresh token đó. Validate/dedupe các candidate mới.
- Xác định complete/partial/transient/rejected per-token; failure chưa được giải quyết không thành fresh SUCCESS. Giữ cache khi upstream tạm lỗi, nhưng trả freshness/error chính xác.
- Tests: B05/B06; mixed known/new token, invalid SKU/type, owner conflict, known refunded/expired, partial/no-success, timeout/upstream auth error. Không cho phép caller tự chọn principal ngoài auth server.
- Acceptance: refund được cập nhật dù client gửi metadata sai; response snapshot chỉ thuộc principal đúng; restore partial chỉ mô tả những gì thực sự đã xác thực. Handoff JSON fixtures cho U05/U07.

## U05 — Kiểm tra contract HTTP restore Android (F04)

- Depends U04. Files: `app/.../utils/billing/PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt` nếu cần typed result, Android verifier tests.
- Validate HTTP/status, snapshot owner = expected operation/session owner; required schema, catalog SKU/type/source, token/version/timestamp/expiry. Kiểm tra duplicate/conflicting tokens. Không dùng dữ liệu response làm expected request để tự khớp.
- Không âm thầm drop invalid item rồi trả full Success. Chọn explicit invalid-response hoặc partial có lỗi theo server contract. Không bắt token restore nằm trong local candidates vì restore máy mới cần receipt chưa có trên máy.
- Kiểm tra cả verify/rejected tombstone để helper dùng chung không hồi quy. URL/auth readiness và lỗi 401/403 phải có outcome phù hợp cho U08, không rò credentials qua URL sai.
- Tests: A01–A04/A07; missing/null owner, item owner mismatch, wrong source/type/SKU, empty token, invalid version/expiry, mixed valid/invalid, status-body mismatch, legitimate remote-only receipt.
- Acceptance: malformed/cross-owner response không mutation và không báo thành công; valid restore vẫn được nhận. Handoff typed parser result cho U06/U07.

## U06 — Guard phiên và vòng đời restore sau await (F05)

- Depends U05. Files: Android `BillingReconciliation.kt`, `BillingManager.kt` orchestration nếu cần, `BillingOperationContext.kt` tối thiểu, tests.
- Gắn coroutine restore với scope/job được quản lý; capture operationId/owner/generation trước tác vụ. Recheck sau token/HTTP await và trước mọi store/prefs/profile/event/UI side effect.
- Kiểm tra stale/cancellation cho mọi outcome kể cả PARTIAL và lỗi; không chỉ guard callback cuối tại Manager. Không đổi tài khoản đang sở hữu thao tác khi reconnect.
- Tests: A05; A→B, logout, A→B→A, concurrent restore, cancellation, late success/partial/error, destroy/recreate màn hình. Dùng deferred responses và kiểm tra cả store, flags, profile, event/callback.
- Acceptance: tác vụ cũ không mutation hoặc báo kết quả cho phiên mới; tác vụ mới vẫn hoàn tất. Bàn giao guard/scope cho U07.

## U07 — Một snapshot đã commit cho restore, profile và tính năng (F06/F07)

- Depends U06. Files: `BillingReconciliation.kt`, `PurchaseVerifier.kt`/result model nếu cần, `BillingManager.kt` result mapping, `AppAuthManager.kt` projection API; watermark/Drive tests. Chỉ sửa UI dialog/resources cần cho thông báo partial, không thay giao diện ngoài phạm vi.
- Dùng typed apply, lấy snapshot hợp nhất đã commit. Không apply hai lần rồi đọc incoming để quyết định; phân biệt persistence failure/conflict/stale. Profile, flag, count và callback phải phản ánh state thực lưu.
- SUCCESS/PARTIAL cùng cơ chế project; partial có receipt lỗi không báo full success. Partial0-active không phát Restored thành công. Preserve quyền khác của tài khoản theo merge/version, không xóa mọi quyền khi snapshot rỗng thiếu chứng cứ revoke.
- Tests: A06/A08; store commit=false/exception, equal-version conflict, partial active→revoke và Free→VIP, mixed multiple entitlements, app restart; gọi `WatermarkHelper` và `GoogleDriveBackupWorker.performBackup` qua seam thật để kiểm tra gate. Giữ các regression Drive cancellation/revision/owner/immutable retry snapshot.
- Acceptance: UI và watermark/Drive thống nhất với entitlement đã commit; không báo success giả; callback UI đúng thread/lifecycle. Handoff fixture integration và lỗi cần thiết bị cho U10.

## U08 — Hết hạn phiên có đường re-auth rõ (F08)

- Depends U07. Files: `AppAuthManager.kt`, `PlayPurchaseVerifier.kt`, `VipPurchaseActionCoordinator.kt`, `VipLoginContinuationHandler.kt`, `BillingManager.kt` preflight, dialog/resources liên quan, tests.
- Tách backend configured khỏi authenticated-ready (missing/expired/session changed). Dùng cơ chế refresh của identity provider được hỗ trợ, hoặc yêu cầu đăng nhập lại rõ ràng; không tự chế token hoặc lặp lại token cũ từ profile.
- Resume purchase/restore chỉ sau phiên hợp lệ, đúng owner/generation và đúng một lần. Nếu refresh cần quyết định kiến trúc ngoài phạm vi, thực hiện re-auth tối thiểu an toàn trong luồng hiện có.
- Tests: A09; expired/missing token, valid token control, re-auth thành công/hủy/lỗi, đổi user lúc chờ, A→B→A, repeated click/resume, 401 khi restore, không launch Play trước readiness. Fake acquisition để deterministic; thiết bị thật ở U10.
- Acceptance: hết hạn phiên không bị báo “dịch vụ chưa sẵn sàng” canRetry=false vô lối; không mua trước khi có phiên xác thực; không cấp trial hoặc quyền khi lỗi.

## U09 — Regression tổng hợp và kiểm tra độc lập

- Depends U08. Files: tests, probe harness/acceptance docs; chỉ quay lại đúng gói production khi phát hiện lỗi.
- Chạy cả 16 probe vòng5 và regression vòng3/vòng4, full Android + backend, lint/build theo lệnh audit. Giữ bản gốc probe để đối chiếu; nếu API đổi, chỉ adapt harness có giải thích, không đổi hành vi mong đợi.
- Bổ sung ít nhất biến thể chưa có trong 16 ca: inapp race, two-connection SQLite, partial revocation, persistence failure, session A→B→A, HTTP restore malformed + remote-only token, expired credential resume, watermark/Drive integration.
- Acceptance: mọi regression liên quan PASS, compile/build/lint không có error mới; báo số tests thực tế (không copy số928/89 nếu đã thêm test). Lưu command/exit/result XML/log, kiểm tra diff không ngoài scope, không secrets.
- Nếu FAIL, quay lại gói sở hữu và tự sửa; chưa qua thì không đánh dấu code complete. Không mở rộng test vô hạn khi đã đáp ứng ma trận.

## U10 — Các gate thiết bị/môi trường thật

- Depends U09. Dùng môi trường test đã được cấp sẵn; không tự tạo production service/deploy/publish hoặc mua bằng tiền thật.
- Thử license tester: purchase yearly/lifetime, pending, cancel còn hạn, hold/paused, expire/refund, restore máy mới/reinstall, hai app accounts/hai Play accounts, token hết hạn và re-auth, mất mạng, process death, đổi tài khoản trong khi restore, RTDN retry.
- Thử watermark PDF export và Drive backup với VIP active/partial/revoked, cancellation/account switch, file thực trên thiết bị.
- Kiểm tra backend durable storage sau restart, quyền service account và Pub/Sub audience/issuer qua môi trường staging đã có. Không in secret.
- Thiếu thiết bị/token/service: ghi từng gate NOT_RUN/BLOCKED_EXTERNAL cùng cách chạy, không giả PASS, tự chuyển U11. Không yêu cầu người dùng canh từng case.

## U11 — Kết luận và bàn giao cuối

- Files: `PROGRESS_VIP_R5_AUTORUN.md`, `REPORT_VIP_R5_FINAL.md`, `docs/billing/ROUND5_ACCEPTANCE.md`; không viết lại lịch sử báo cáo vòng4 để xóa chứng cứ.
- Ma trận bắt buộc: F01–F08 → Uxx → code path → regression → kết quả host → gate external. Phân biệt FIXED_VERIFIED_HOST, FAILED, BLOCKED_EXTERNAL, NOT_RUN.
- Tóm tắt files changed, actual test counts, các thay đổi contract, lỗi còn lại, gate chưa chạy, command để tiếp tục. Không nói “100% xong/production ready” khi U10 còn thiếu.
- Nếu chỉ còn external gate, kết thúc phần code và báo đúng một lần các việc cần người dùng cung cấp/thực hiện. Nếu còn lỗi host, tiếp tục sửa trong scope hoặc báo blocker cụ thể; không tự tuyên bố hoàn thành.

## Prompt giao một lần cho Gemini trong Antigravity

```text
Làm việc tại E:\DU AN AI\T-Scanner.
Đọc PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND5_AUTORUN_2026-09-30.md,
RECHECK_VIP_FULL_ROUND5_2026-09-30.md và docs/vip-round5-20260930/.

Tôi giao bạn thực hiện toàn bộ các gói U00 đến U11 theo đúng thứ tự.
Tự làm, tự chạy test, tự sửa lỗi và chuyển gói khi đạt acceptance;
không dừng hỏi tôi sau mỗi gói. Giữ các thay đổi hiện có và đúng phạm vi.
Phải có regression bắt lỗi trước sửa; không làm xanh bằng cách bỏ/nới test.
Lưu PROGRESS_VIP_R5_AUTORUN.md và REPORT_VIP_R5_Uxx.md để tiếp tục được
nếu phiên làm việc bị gián đoạn. Khi tiếp tục, đọc progress và xác minh
checkout/test tương ứng rồi đi từ gói chưa xong, không làm lại mù quáng.

Thiếu thiết bị/credentials thì ghi BLOCKED_EXTERNAL/NOT_RUN, làm tiếp
các phần độc lập và tổng hợp một lần cuối. Không tự deploy/publish/push,
thay signing/SKU/giá hoặc mua bằng tiền thật. Không tuyên bố production
ready dựa riêng vào unit tests. Kết thúc bằng REPORT_VIP_R5_FINAL.md và
ma trận acceptance trung thực cho F01–F08 cùng các gate còn thiếu.
```
