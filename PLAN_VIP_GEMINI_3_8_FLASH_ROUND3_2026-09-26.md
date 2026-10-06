# Kế hoạch sửa VIP cho Gemini 3.8 Flash — vòng 3, 26/09/2026

## Trạng thái và phạm vi

**ĐÃ ĐƯỢC NGƯỜI DÙNG CHO PHÉP GEMINI TRIỂN KHAI TOÀN BỘ M00–M11 TỰ ĐỘNG, TUẦN TỰ.** Người dùng yêu cầu không chờ xác nhận từng gói. Việc cập nhật hướng dẫn này chưa có nghĩa Gemini đã được khởi chạy hay production đã được sửa. Bằng chứng gốc: `RECHECK_VIP_FULL_ROUND3_2026-09-26.md`. Baseline tại thời điểm audit: Android 906/906 PASS, lint 0 errors/777 warnings, assemble PASS; backend 64/64 PASS. Probe độc lập: Android 9 FAIL, backend 7 FAIL. Không coi baseline xanh là nghiệm thu VIP.

Mục tiêu: mua/khôi phục/thu hồi VIP đúng tài khoản và trạng thái authoritative; giữ watermark và Drive nhất quán; sửa auth, schema, persistence và race còn lại. Không đổi thiết kế UI, giá/sản phẩm kinh doanh, R8, OCR hay refactor rộng. Không bật thử nghiệm/local fallback cho release. Không tự deploy, tạo giao dịch trả tiền, sửa Console, ghi dữ liệu Drive thật hay đưa secrets vào app/repo.

Chạy **liên tục từ M00 đến M11, tuần tự từng gói, không hỏi người dùng có tiếp tục hay không**. Nhiều gói dùng chung BillingManager/verifier/store nên không chạy đồng thời. Hoàn tất kiểm thử và báo cáo nội bộ của gói rồi tự chuyển gói kế tiếp. Không `git reset/clean`, không ghi đè thay đổi đang có. Nếu file thay đổi trong lúc làm, đọc lại diff và đối chiếu, không khôi phục về bản cũ.

## Quy tắc cho mỗi phiên Gemini

1. Đọc báo cáo audit, gói đang được giao và handoff gói trước; đối chiếu mã hiện tại, không mặc định số dòng vẫn giữ nguyên.
2. Chỉ sửa file thuộc ownership của gói. Test phải gọi logic sản phẩm thật, fake ở transport/storage/clock/scheduler boundary; không sao chép thuật toán sửa vào test.
3. Giữ probe chứng minh lỗi cũ, viết permanent regression trước khi sửa; test phải thất bại vì invariant đúng trên bản cũ. Với test cũ khẳng định hành vi sai, cập nhật theo contract và ghi lý do cụ thể.
4. Chạy focused tests. Cuối gói ghi `REPORT_VIP_R3_Mxx.md`: file thay đổi, contract/API thay đổi, red→green evidence, command/kết quả thật, remaining issue, gate chưa chạy, việc gói sau cần biết. Không dùng “triệt để/100% production ready” từ số unit tests.
5. **Không dừng sau từng gói để xin xác nhận.** Tự sửa lỗi compile/test phát sinh trong phạm vi đã giao và chạy lại focused tests. M00 đạt khi tái hiện đúng các probe đỏ; các probe thuộc gói tương lai được giữ trong danh sách lỗi đã biết, không chặn tiến độ gói hiện tại. Từ M01, chỉ chuyển gói khi test của phần đã sửa và các phần đã hoàn tất đạt, không phát sinh regression mới không giải thích được. Tất cả regression bắt buộc phải xanh tại M11.
6. Thiếu credential/Console/deployment/device scenario thì ghi external gate và tiếp tục mọi phần code/test độc lập có thể hoàn thành; không xin người dùng canh hoặc mở khóa từng gói. Không giả credential/success, không đánh dấu PASS cho việc chưa chạy. Chỉ hỏi khi thực sự thiếu thông tin bắt buộc mà không thể suy ra an toàn từ contract/repo, có thay đổi đồng thời xung đột không thể giải quyết, hoặc cần hành động ngoài phạm vi đã được phép. Lỗi code khó hay một lượt test đỏ không phải lý do tự dừng.
7. Cập nhật `PROGRESS_VIP_R3_AUTORUN.md` sau mỗi gói: trạng thái từng gói, bằng chứng, lỗi còn lại, quyết định contract, gói hiện tại và bước tiếp theo. Báo cáo handoff là checkpoint nội bộ, không phải điểm chờ phê duyệt. Nếu context được làm mới, đọc progress và báo cáo để tự tiếp tục gói chưa xong, không chạy lại từ đầu. Không hứa có thể tự vượt giới hạn cứng của công cụ/model; nếu bị ngắt bắt buộc, để checkpoint đủ cho lần tiếp tục duy nhất.

Đường dẫn bên dưới tương đối với `E:\DU AN AI\T-Scanner`. Viết tắt: `A=app/src/main/java/com/tscanner/app`, `AT=app/src/test/java/com/tscanner/app`, `B=backend/billing-verifier`. Những thư mục viết tắt chỉ là hướng dẫn; agent phải mở đúng file trong repo.

## M00 — Giữ bằng chứng và chốt contract (không sửa production)

- Ownership: `AT/VipRound3RegressionTest.kt` mới; `B/test/round3-regression.test.ts` mới; `docs/billing/ROUND3_CONTRACT.md` mới; báo cáo M00.
- Copy/adapt 9+7 probe từ `build/vip-audit-20260926/`, đổi tên class/import cho permanent test. Giữ nguyên kỳ vọng; baseline suite hiện có là đối chứng. Không copy `audit.init.gradle` vào build production.
- Chốt contract: token identity ổn định; chỉ server cấp version; snapshot có owner/generation; empty device query khác server revoke; success dựa trên state đã commit; CANCELED_ACTIVE/GRACE còn hạn vẫn active; rejection có tombstone nếu authoritative; grant+ack outbox atomic.
- Validation: permanent probes reproduce cùng 16 assertion thất bại; lỗi compile/test harness phải sửa trước khi giao M01. Ghi mapping F01–F12 và tên test; phân biệt F02/F09 crash/F11 là code evidence cần test thêm.
- Acceptance: baseline và red probes lưu riêng; không tuyên bố các defect đã xanh. Tự chuyển M01 khi harness tái hiện đúng các lỗi đã biết.

## M01 — Google user/PubSub auth thật, chặn cấu hình thiếu (F01)

- Depends: M00. Ownership: `B/src/auth.ts`, phần AuthConfig/Principal trong `B/src/types.ts`, `B/src/index.ts` chỉ phần auth wiring/readiness, `B/package.json` và lockfile nếu cần thư viện auth; `B/test/http-auth.test.ts`, test Google identity mới.
- Bỏ development secret mặc định và HMAC verifier khỏi đường production Google identity. Test JWT factory chuyển vào test helpers; nếu giữ HMAC cho một service riêng thì phải có scheme/issuer/alg riêng tường minh, không nhận thay Google token.
- Kiểm Google signature với key rotation, issuer, audience, expiry; PubSub thêm audience đúng endpoint, allowlisted service account, email_verified. Thiếu cấu hình fail closed. Token refresh/reauth app sẽ được nối tại M06.
- Regression: hai auth probe đỏ; RSA/JWKS synthetic positive và invalid signature, alg mismatch, missing/expired exp, wrong iss/aud, unknown kid, key refresh lỗi, missing service account. Test qua HTTP verify/restore/ack/RTDN, không chỉ gọi helper.
- Acceptance: unauthorized/mismatch bị chặn trước store/API; positive dùng cùng verifier production với transport test; không gọi live endpoint bằng dữ liệu giả. Handoff ghi contract credential và env required, không ghi giá trị secret.

## M02 — Schema và state Google Play authoritative (F06 backend, nền F08)

- Depends: M01. Ownership: `B/src/googlePlayClient.ts`, model Google Play của `B/src/types.ts` nếu cần, `B/test/googlePlayTransport.test.ts`, tests schema/lifecycle mới.
- Parse typed/validated response; HTTP 200 `{}`, unknown state, timestamp sai không trở thành paid. Migrate subscription query sang subscriptionsv2/current state theo tài liệu Google, giữ acknowledge endpoint thích hợp và SKU legacy cần thiết.
- Map active/grace/canceled/hold/paused/pending/expired rõ ràng; không suy paid chỉ từ expiry hoặc default paymentState. Validate product/package/line item, tiền đề owner hash. Timeout/401 refresh/backoff có giới hạn; error không log token/credential thô.
- Regression: empty Play JSON probe; realistic captured-shape synthetic payloads cho từng state, malformed JSON/schema, 401/403/404/429/5xx, timeout. Test URL và parsing bằng production transport fake HTTP.
- Acceptance: dữ liệu thiếu không grant; cung cấp cùng mô hình authoritative cho verify và RTDN. Handoff enum/schema cho M03/M04; không dùng eventType làm nguồn state chính.

## M03 — Identity ổn định và tombstone backend (F04, phía server F05)

- Depends: M02. Ownership: `B/src/verifier.ts`, entitlement/response schema trong `B/src/types.ts`, test restore/revocation/token identity.
- Dùng một canonical identity theo provider+token cho mọi state; không tạo id khác khi revoke. Tombstone trả đúng owner/token/product/version, lấy version bản ghi thực sự đã commit.
- Expired/refunded branch vẫn kiểm owner/hash trước bind; xử lý bind failure chứ không trả snapshot như đã lưu. Chốt response contract REJECTED kèm authoritative snapshot so với error không có quyền thu hồi.
- Regression: backend stable-ID probe; active→expired→renewal, active lifetime→revoked, store owner conflict, same receipt replay, partial restore. Dùng output thật của verifier qua nhiều bước, tránh seed id thủ công không giống production.
- Acceptance: cùng token luôn một id, chỉ trả snapshot đã commit; migration server có test nếu dữ liệu cũ cần chuyển. Handoff schema/version cho M07/M08.

## M04 — Serialize/CAS trạng thái và RTDN bền vững (F08)

- Depends: M03. Ownership: `B/src/storage/schema.ts`, `B/src/storage/types.ts`, `B/src/storage/sqliteDriver.ts`, `B/src/store.ts`, `B/src/verifier.ts`, `B/src/rtdnHandler.ts`; concurrency/RTDN tests.
- Version tăng là kết quả commit, không cho payload cũ tự trở thành mới. Chọn cơ chế per-token transaction/CAS với refresh authoritative khi conflict, hoặc queue/lock có phạm vi đúng giữa process. Không chỉ mutex trong RAM nếu production dùng nhiều instance.
- RTDN dùng current state từ M02, durable dedupe/event ordering; recheck khi commit. One-time notification phải xác minh state hiện hành. Unknown-token event phải có recovery contract, không âm thầm đánh dấu xử lý nếu còn cần cập nhật quyền sau bind.
- Regression: hai latch probe backend; verify và RTDN chạy đan xen; restart rồi replay; hai SQLite connections/processes cùng token; API failure/retry; một token không ảnh hưởng token khác. Test transaction rollback và bounded retries.
- Acceptance: callback cũ không đảo ngược trạng thái mới; event retry không mất; server version bền vững. Handoff APIs giao dịch cho M05.

## M05 — Durable store/readiness và grant+ack outbox atomic (F09, F11)

- Depends: M04. Ownership: `B/src/storage/*`, `B/src/store.ts`, `B/src/verifier.ts`, `B/src/ackService.ts`, `B/src/ackWorker.ts`, `B/src/index.ts`, storage/ack/readiness tests, deployment config guide trong `docs/billing/`.
- Bắt buộc storage path durable ở production, test truyền `:memory:` tường minh. `/health` có thể là liveness nhưng cần readiness riêng với điều kiện thực tế. Không silently chạy memory khi thiếu cấu hình.
- Một transaction commit entitlement và outbox; enqueue cho purchased entitlement cần ack kể cả canceled-active còn hạn. Chốt server chịu trách nhiệm ack/retry, client không còn là điều kiện để recovery. API response cho biết durable result, không lẫn “Google đang chờ xử lý” với lỗi mất quyền.
- Retry idempotent, startup recovery, xử lý job thất bại quá số lần có trạng thái quan sát/recovery; không âm thầm bỏ receipt. Persistent volume/DB shared contract nêu rõ giới hạn instance.
- Regression: canceled-active outbox probe; process/fault injection tại trước commit, sau commit, trước/giữa ack; restart; missing storage config; hai workers; token đã ack; timeout lâu hơn một vòng polling.
- Acceptance: không tồn tại active unacknowledged receipt cần xử lý mà thiếu job do crash; restart không mất owner/version/queue; startup thiếu storage không báo ready. Chỉ chuẩn bị config, không deploy.

## M06 — Nối verifier Android vào bootstrap và session (F02)

- Depends: M05. Ownership: `app/build.gradle` chỉ cấu hình verifier, `A/TScannerApplication.kt`, `A/utils/AppAuthManager.kt` chỉ token/session provider, `A/utils/BillingManager.kt` chỉ construction/readiness, `A/utils/billing/PlayPurchaseVerifier.kt` chỉ config/transport credential, readiness/bootstrap tests.
- URL config có nguồn cụ thể đọc được bởi production; initialize trước singleton BillingManager. Không hardcode URL giả, không bật sandbox grant. Missing config chặn mua trước Play sheet.
- HTTPS URL strict; readiness biết thiếu credential/reauth; refresh token đúng phiên, token cũ không dùng cho owner mới; disconnect/timeout/redirect policy an toàn, không gửi bearer sang host khác. Nếu không refresh được thì flow re-auth rõ, không silent failure sau thanh toán.
- Regression: httpEndpointMustNotBeReady; cold start/login/logout/foreground qua composition production; missing URL/token, expired session, A→B→A trong khi chờ refresh, configured build gửi auth header và đúng endpoint. Fake chỉ tại credential/HTTP boundary.
- Acceptance: có config hợp lệ thì production manager thực sự dùng remote verifier; thiếu config fail closed. Handoff configuration requirements cho người vận hành; không đòi người dùng nhập secret vào chat.

## M07 — Android strict response và lưu tombstone (F05, F06 Android)

- Depends: M06. Ownership: `A/utils/billing/PlayPurchaseVerifier.kt`, `A/utils/billing/PurchaseVerifier.kt`, nhánh verification trong `A/utils/BillingManager.kt`, HTTP/revocation/schema tests.
- Validate status code+body schema, known state/source, owner/token/product/type khớp request, expiry bắt buộc với subscription, version dương và timestamps hợp lệ. Không mặc định unknown→ACTIVE hoặc missing expiry→lifetime.
- Rejected authoritative mang snapshot/tombstone theo M03; propagate và persist. Invalid token/ownership/auth/transient không được tự biến thành revoke. Preserve CancellationException.
- Regression: malformedStateMustNotBecomeActive, rejectionMustPersistAuthoritativeRevocation; thêm HTTP thật loopback test-only nếu phù hợp, response nhầm token/owner, 409 SUCCESS, null fields, authoritative lifetime revoke→reload→watermark. Dùng JSON đầu ra backend M03 làm contract fixture.
- Acceptance: không grant từ payload thiếu; quyền bị revoke không còn active sau restart; transport lỗi giữ cache hợp lệ. Ghi checkpoint rồi tự chuyển M08.

## M08 — Restore server-authoritative và migration identity client (F03, F04 client)

- Depends: M07. Ownership: `A/utils/billing/BillingReconciliation.kt`, `A/utils/billing/BillingEntitlement.kt`, `A/utils/billing/BillingEntitlementStore.kt`, restore API trong `PurchaseVerifier.kt`/`PlayPurchaseVerifier.kt`, integration trong `BillingManager.kt`; reconciliation/migration tests.
- Lấy snapshot theo authenticated app account qua server restore/sync, kể cả device catalog rỗng. Chỉ coi receipt Play là candidates. Không local revoke hay tự tăng server version; giữ promotion theo nguồn.
- Migrate/dedupe dữ liệu legacy có nhiều id cùng token; identity contract M03, owner isolation và version nguồn. Không xóa quyền của token khác hoặc mất guest candidate chưa verify. Cache offline có trạng thái freshness rõ theo contract, không coi lỗi mạng là revoke.
- Regression: emptyDeviceCatalogMustPreserveServerEntitlement, expiredServerIdMustReplaceActiveToken; app account trên Play account khác, reinstall/empty store/server active, revoked server/empty Play, partial server error, A→B→A callback, cùng version conflicting payload. Test cũ yêu cầu empty Play tự revoke phải sửa và giải thích trong handoff.
- Acceptance: server active vẫn active khi device không có receipt; revoke server thay entry cũ; client không mint version. Không dùng snapshot thiếu owner như quyền áp dụng cho mọi user.

## M09 — Durable apply một lần và kết quả UI đúng (F07, thống nhất ack client F09)

- Depends: M08. Ownership: `A/utils/BillingManager.kt`, `A/utils/AppAuthManager.kt` phần projection, `A/utils/billing/BillingEntitlementStore.kt`, `BillingReconciliation.kt` phần result, `A/ui/dialogs/VipUpgradeDialog.kt` nếu cần hiển thị partial; test snapshot/result.
- Store trả snapshot đã commit và result typed (applied/replay/stale/conflict/persistence failure). Profile/UI suy ra từ snapshot này, không commit store lần hai. Rà save profile error/restart recovery.
- Thành công theo `isCurrentlyActive()` của entitlement cuối cùng đúng owner/token; canceled/grace hợp lệ không bị báo hết hiệu lực. Stale incoming ACTIVE không thể phát success khi committed state revoked. Partial restore phải thể hiện partial, không toast thành công toàn bộ.
- Theo M05, bỏ ack client trùng trách nhiệm hoặc thực hiện contract phối hợp được chứng minh; không chờ một ack redundant để cấp quyền đã durable. Capture/recheck session tại thời điểm callback UI; exactly once completion/cancel lifecycle.
- Regression: canceledPaidPeriodMustRestoreSuccessfully, staleActiveSnapshotMustNotReportPurchaseSuccess, secondStoreCommitFailureMustNotReportSuccess. Sau bỏ double-write, thay fault injection thành projection failure/recovery test và giữ assertion số durable commits=1; không giữ một test fail-second-write không bao giờ được kích hoạt rồi coi là bằng chứng recovery.
- Acceptance: success đồng nghĩa quyền active đã lưu và profile đúng; lỗi có recoverable result; reload nhất quán, watermark và Drive quan sát cùng quyền. Test cancel/switch trong lúc ack/network.

## M10 — Gate VIP/session của Drive worker sau chờ (F10)

- Depends: M09. Ownership: `A/utils/GoogleDriveBackupWorker.kt`; `A/utils/CloudBackupManager.kt` chỉ checkpoint session/VIP nếu cần; test worker/session mới. Không sửa DocumentRepo/export ngoài phạm vi.
- Kiểm session generation, owner, current VIP và coroutine cancellation sau token/folder lookup, ngay trước upload/update và trước commit local. Snapshot/revision/CAS hiện có phải được giữ.
- Nếu request đã gửi thì ghi rõ không thể thu hồi byte đã gửi; hủy cooperative ở transport khi khả thi, không báo SYNCED sai sau đổi session. Không xóa snapshot còn cần cho retry do auth/temporary failure.
- Regression: driveWorkerMustRecheckVipAfterTokenWait; revoke trong folder wait, logout/cancel token wait, A→B→A, transient retry và snapshot tồn tại, thành công hợp lệ, local revision đổi khi upload.
- Acceptance: chưa bắt đầu remote mutation thì quyền/phiên hết hiệu lực phải chặn; cancellation không tiếp tục upload; không phát sinh duplicate/mất file. Device WorkManager acceptance nằm M11.

## M11 — Tích hợp, đính chính báo cáo và gate release (F12 + tất cả)

- Depends: M10. Ownership: báo cáo M11, bảng đối soát mới trong `docs/billing/`, đính chính `REPORT_VIP_BILLING_ROUND2_Q12.md` và integration summary liên quan bằng chú thích lịch sử; integration tests. Không âm thầm sửa production vượt scope: nếu phát hiện lỗi mới, ghi test + gói bổ sung.
- Chạy tất cả permanent regressions và bộ test Android/backend; build/lint. Chỉ bỏ init harness sau khi permanent tests bao phủ đầy đủ, không duplicate test source.
- Rà code production bootstrap → Google auth → verify/restore → storage/outbox → Android store/profile → watermark/Drive. Hợp đồng JSON phải dùng cùng fixture qua hai bên, không hai fake có schema khác nhau.
- Device matrix: FREE/VIP/canceled/grace/revoked; login/cancel/rotation; purchase pending/retry; restore account khác Play account; offline/reconnect; app restart/process death; foreground renewal/refund; watermark PDF/ID/OCR/export thực; Drive revoked sau token wait và retry snapshot. Có thiết bị kết nối trong audit nhưng chưa chạy matrix này.
- External gates: cấu hình server auth/storage/Play API; Play internal test/license tester; service account/RTDN; hai thiết bị/account; restart backend trên đúng persistent volume. Chỉ thực hiện khi môi trường/quyền truy cập phù hợp đã có; không tự tạo charge hay deployment trong gói audit/plan.
- Acceptance: F01–F12 có trạng thái và evidence thật; tất cả local tests đạt, không còn unresolved P1 trước release. Nếu external gate chưa chạy, kết luận local implemented/validated và liệt kê NOT RUN; không READY toàn hệ thống.

## Lệnh kiểm tra

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
# Trong M00: chạy harness audit để đối chiếu, không chạy đồng thời với Gradle khác.
./gradlew.bat -I build/vip-audit-20260926/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipIndependentAuditTest --offline --console=plain
node --experimental-strip-types --test build/vip-audit-20260926/backend-probes.test.ts
# Sau khi chuyển thành permanent tests: dùng tên suite thực tế của từng gói.
./gradlew.bat :app:testDebugUnitTest --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
# Gate host cuối
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Nếu wrapper cache Access denied, xử lý quyền/cache môi trường trước; không sửa dependencies để che lỗi môi trường. Dependencies auth mới chỉ cài theo scope M01; nếu network thiếu thì ghi rõ check chưa chạy, không báo PASS.

## Prompt giao việc một lần cho toàn bộ kế hoạch

Người dùng đã cho phép Gemini tự triển khai lần lượt M00–M11. Gửi toàn bộ prompt dưới đây một lần; không cần gửi từng gói.

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_VIP_GEMINI_3_8_FLASH_ROUND3_2026-09-26.md.
Bạn là Gemini 3.8 Flash. Tôi cho phép bạn triển khai TOÀN BỘ kế hoạch M00–M11,
làm tuần tự từ đầu đến cuối, tự kiểm thử và tự chuyển gói; KHÔNG chờ tôi xác nhận từng gói.
Đọc RECHECK_VIP_FULL_ROUND3_2026-09-26.md và PROGRESS_VIP_R3_AUTORUN.md nếu đã tồn tại.
Bắt đầu ở gói đầu tiên chưa hoàn tất, đối chiếu code/test thật trước khi tin báo cáo.

Mỗi gói: chỉ sửa file thuộc phạm vi; giữ thay đổi có sẵn; viết regression gọi production
logic, tái hiện lỗi cũ, sửa, chạy focused tests và tự khắc phục regression phát sinh.
M00 chủ đích tái hiện probe đỏ. Probe thuộc gói tương lai ghi vào danh sách lỗi đã biết;
không đổi expected/skip/xóa test để làm xanh. Các phần đã sửa phải tiếp tục đạt.
Sau mỗi gói, ghi REPORT_VIP_R3_Mxx.md và cập nhật PROGRESS_VIP_R3_AUTORUN.md,
rồi tự làm gói tiếp theo. Báo cáo/checkpoint không phải yêu cầu phê duyệt.

Không refactor rộng, không bật local fallback cho release, không deploy/charge/sửa Console/
ghi Drive thật, không thêm secrets. Thiếu credential hoặc môi trường thật thì ghi NOT RUN
và tiếp tục mọi phần code/test không phụ thuộc; không dừng cả kế hoạch vì external gate.
Chỉ hỏi khi thiếu thông tin bắt buộc không thể tự giải quyết an toàn hoặc có hành động
ngoài phạm vi đã cho phép. Nếu context được làm mới, đọc checkpoint và tự tiếp tục.

Cuối M11 chạy đầy đủ Android/backend regressions, build và lint; rà lại F01–F12 bằng
bằng chứng mới. Gửi một báo cáo tổng kết cuối: phần đã sửa, test thật, lỗi còn lại,
external gates chưa chạy. Không kết luận toàn hệ thống READY khi còn gate chưa kiểm chứng.
```

Thứ tự tự chạy: **M00 → M01 → M02 → M03 → M04 → M05 → M06 → M07 → M08 → M09 → M10 → M11**. Giữ ranh giới gói để kiểm thử/checkpoint; không dùng ranh giới đó để dừng chờ người dùng.
