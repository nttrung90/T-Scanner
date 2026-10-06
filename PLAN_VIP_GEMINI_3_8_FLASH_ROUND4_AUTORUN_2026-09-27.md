# Gemini 3.8 Flash — sửa VIP vòng 4, tự chạy T00–T13

## Lệnh thực hiện và phạm vi

Người dùng yêu cầu Gemini **tự làm lần lượt từ đầu đến cuối, không chờ xác nhận từng gói**. Đây là kế hoạch giao thực hiện; việc tạo file không có nghĩa Gemini đã được khởi chạy. Lượt audit hiện tại chưa sửa mã sản phẩm.

Đọc trước `RECHECK_VIP_FULL_ROUND4_2026-09-27.md`. Bằng chứng mới: Android 919/919, backend 78/78 PASS, build/lint PASS (0 errors/779 warnings); probe vòng 4 có **17 FAIL/2 PASS**. Báo cáo M11 vòng 3 không còn là bằng chứng đủ để kết luận đã hết lỗi.

Mục tiêu: sửa R01–R12, hoàn thiện các yêu cầu vòng 3 chưa làm, bảo toàn tính năng đã đúng. Không đổi giá/UI/thiết kế sản phẩm, OCR, R8, release signing hoặc refactor toàn hệ thống. Không deploy, tạo charge, sửa Console hay ghi Drive thật, không thêm secret vào source. Các bước code/test cục bộ dưới đây được giao tự thực hiện; external gate không chặn việc cục bộ độc lập.

## Cách tự chạy

1. **T00 → T01 → … → T13, tuần tự; không chạy nhiều agent cùng ghi file.** Các gói dùng chung verifier/store/manager nên phải xong gói trước rồi mới chuyển ownership sang gói sau.
2. Trước sửa đọc code hiện tại và diff, giữ mọi thay đổi có sẵn. Không reset/clean checkout. Chỉ sửa file trong phạm vi gói; mở rộng file trực tiếp cần thiết phải ghi lý do trong checkpoint, không lợi dụng để refactor rộng.
3. Regression gọi production logic, fake tại HTTP/storage/clock/scheduler boundary. Đặc biệt không sửa riêng một state pair chỉ để probe xanh; phải kiểm bảng trường hợp ghi trong gói.
4. Mỗi gói ghi `REPORT_VIP_R4_Txx.md` và cập nhật `PROGRESS_VIP_R4_AUTORUN.md`, rồi **tự chuyển gói kế tiếp**. Không hỏi “có tiếp tục không”, không kết thúc công việc chỉ vì một gói xong. Báo cáo là checkpoint nội bộ.
5. T00 đạt khi tái hiện red probes đúng. Probe chưa sửa của gói tương lai ghi known failures, không làm chặn mọi gói. Các phần đã hoàn tất phải tiếp tục xanh; lỗi mới tự sửa trong phạm vi. Không xóa/skip/đổi kỳ vọng để che lỗi.
6. Test cũ kiểm hành vi sai phải được thay bằng đúng contract và giải thích: ví dụ rejection không snapshot không được tự tạo server version; “không gọi server khi Play rỗng” không là restore hợp lệ. Test mới phải chứng minh cả đường dữ liệu thực và kết quả cuối, không chỉ helper.
7. Thiếu credentials/device setup thì hoàn tất code+tests cục bộ, ghi NOT RUN và tiếp tục phần độc lập. Chỉ hỏi nếu thực sự thiếu thông tin bắt buộc không có phương án an toàn trong contract/repo, gặp thay đổi đồng thời xung đột, hoặc cần hành động ngoài phạm vi cho phép. Không coi test đỏ/lỗi compile thông thường là lý do dừng.
8. Lưu checkpoint khi context gần đầy; khi tiếp tục đọc progress, đối chiếu code/test và tiếp tục gói dở, không lặp lại từ đầu. Nếu công cụ/model bị giới hạn cứng phải ghi trạng thái thực, không báo hoàn tất. Cuối T13 gửi một tổng kết cho người dùng.

Viết tắt đường dẫn trong các gói: `A=app/src/main/java/com/tscanner/app`, `AT=app/src/test/java/com/tscanner/app`, `B=backend/billing-verifier`. Workspace: `E:\DU AN AI\T-Scanner`.

## T00 — Khóa bằng chứng, regression và checklist đầy đủ

- Ownership: `AT/VipRound4RegressionTest.kt` mới, `B/test/round4-regression.test.ts` mới; `docs/billing/ROUND4_ACCEPTANCE.md`, progress/report vòng 4.
- Chuyển/adapt harness `build/vip-audit-20260927/VipRound4AuditTest.kt` và `backend-probes.test.ts` thành permanent suites; giữ 19 invariant và 2 đối chứng. Không thêm init script audit vào build sản phẩm.
- Tạo bảng từng acceptance clause trong T01–T13, gồm test ID, production entrypoint, result, evidence path, remaining. Không chỉ bảng 12 lỗi → 12 chữ RESOLVED.
- Validation: 9 Android (8 fail/1 pass), 10 backend (9 fail/1 pass) trên baseline chưa sửa; lỗi compile harness phải giải quyết trước. Lưu baseline XML riêng.
- Acceptance: harness tái hiện lỗi đúng, không claim bug fixed; tự chuyển T01.

## T01 — Auth config và claims fail-closed (R02)

- Depends T00. Files: `B/src/auth.ts`, `B/src/types.ts` phần auth, `B/src/index.ts` phần readiness/auth wiring; `B/test/http-auth.test.ts`, auth config/Google JWT tests mới.
- Normalize googleClientId/expectedAudience với quy tắc nhất quán; thiếu audience không nhận user token. PubSub bắt buộc issuer/audience/service-account/email_verified trong production scheme. Tách readiness user/push/API capability; cấu hình chỉ user auth không được hiểu là push sẵn sàng.
- Giữ RSA/JWKS implementation và cache; không tự bật HMAC từ một biến env bất kỳ. HMAC/service secret nếu còn cần cho môi trường riêng phải tách scheme/mode explicit và test, không đi vòng Google identity.
- Regression: 3 auth failures trong harness + RSA valid control; config trực tiếp và ENV; missing/wrong claims, wrong kid/expiry/signature; endpoint HTTP verify/restore/ack/RTDN reject trước mutation. Fake JWKS public keys, không gọi Google bằng token giả.
- Acceptance: chính xác principal từ token đúng audience, thiếu cấu hình fail closed; readiness phản ánh capability có thật. Handoff field/env contract, không secrets.

## T02 — Đường query V2 và state/product contract (R03)

- Depends T01. Files: `B/src/googlePlayClient.ts`, phần model Play trong `types.ts`, `B/src/verifier.ts` phần mapping state, `B/src/rtdnHandler.ts` mapping; transport/lifecycle tests.
- Production URL dùng subscriptionsv2 đúng API. Giữ acknowledge endpoint phù hợp, không đổi SKU kinh doanh. Parse typed state và product match chính xác, không fallback lineItems[0] cho sản phẩm khác. Unknown/unspecified không grant.
- Shared mapping current state cho verify/RTDN: ACTIVE, CANCELED còn hạn, GRACE, PAUSED, ON_HOLD, PENDING, EXPIRED và pending-purchase-canceled/linked token theo contract. Không làm mất thông tin thành autoRenewing boolean; state suspend phải cập nhật cache authoritative.
- Regression: 3 V2 failures; assert URL production; active/canceled/grace/hold/paused/pending/expired bảng đầy đủ, unknown fields, product mismatch/multi-lineitem, pending payload thiếu field chỉ có sau paid; HTTP 404/429/5xx, parse schema.
- Acceptance: input sai không grant, state suspend không giữ active cache sai; test gọi transport+verifier thật, không chỉ parse V2 fixture trong khi URL vẫn V1.

## T03 — Owner/hash kiểm trước mọi mutation (R05)

- Depends T02. Files: `B/src/verifier.ts`, ownership/revocation tests; không thay auth principal contract.
- Kiểm Play obfuscated owner/hash và existing ownership trước bind active/inactive. Expired/revoked không được chiếm receipt thuộc A cho B. Quyền legacy không hash xử lý theo policy hiện có và server transaction, không bỏ owner validation.
- Regression: expired wrong-hash probe; cùng test với refunded inapp; rightful owner renewal sau attempt sai; active/pending/inactive ma trận; ensure no store/outbox mutation khi mismatch.
- Acceptance: caller không claim token khác bằng inactive branch; đúng owner vẫn restore/renew. Handoff validation ordering cho T04.

## T04 — CAS tổng quát và concurrency mọi state (R04)

- Depends T03. Files: `B/src/storage/{types,schema,sqliteDriver}.ts`, `B/src/store.ts`, `B/src/verifier.ts`, `B/src/rtdnHandler.ts`, concurrency tests.
- Replace special-case ACTIVE→EXPIRED guard bằng concurrency contract tổng quát. Có expected-absent cho first bind; mọi nhánh inapp/subscription dùng guard; conflict không gắn payload cũ version mới. Retry bounded bằng refetch authoritative hoặc serialized operation đúng phạm vi DB/process.
- Event-time guard riêng không thay CAS với request verify. RTDN/verify cập nhật cùng token phải an toàn; đọc/commit state đúng owner và bỏ response cũ mà không mất update mới.
- Regression: inverse callback probe mới, probe chiều cũ, bảng cặp state ACTIVE/EXPIRED/REVOKED/HOLD/GRACE/CANCELED; first insert race; inapp revoke; verify↔RTDN; 2 connections/restart. Không ưu tiên ACTIVE cố định để xanh.
- Acceptance: version tăng chỉ đi cùng state hợp lệ mới; không resurrection/mất renewal; rollback giữ outbox atomically.

## T05 — Server restore known tokens và freshness (R01 backend)

- Depends T04. Files: `B/src/verifier.ts`, restore types/index handler nếu cần, restore tests.
- Candidate set = known owner records + client candidates, dedupe token, owner derive từ principal. Refresh theo policy rõ và có test; authoritative expired/refunded cập nhật tombstone. Cache còn hiệu lực phải có freshness/error status, không gọi fresh SUCCESS nếu bỏ qua invalidation đã biết.
- Phân biệt complete/partial/transient và per-token result; bounded concurrency/timeouts, không giữ HTTP request vô hạn. Không chuyển Google lỗi mạng thành revoked. Không tự cấp quyền receipt của owner khác.
- Regression: empty-candidate lifetime-refund probe; fresh server active với máy không có candidates; mixed known/candidate tokens; offline/partial/auth error/ownership conflict và restart.
- Acceptance: `/restore` trả snapshot có identity/version đúng, đủ cho Android máy mới và revocation recovery. Handoff JSON fixtures **được sinh từ output production backend**, không viết hai schema giả khác nhau.

## T06 — Android response binding và schema (R06)

- Depends T05. Files: `A/utils/billing/PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt` phần result schema, HTTP/schema tests.
- Bắt buộc fields và matching request token/product/type/owner/source; version/timestamp hợp lệ, không coerce dữ liệu lỗi thành quyền hợp lệ. Không biến response product khác thành lifetime. Same contract cho tombstone; mismatch phải error không mutation.
- Regression: responseDifferentToken; wrong owner/product/type/source, blank/null/missing token/version, invalid expiry, status code/body mismatch; success/rejected fixtures từ T05.
- Acceptance: chỉ parse snapshot đúng binding; session/network/cancellation errors phân biệt rõ, không grant/revoke do payload thiếu.

## T07 — Migration identity không vượt equal-version guard (R08)

- Depends T06. Files: `A/utils/billing/BillingEntitlement.kt`, `BillingEntitlementStore.kt`, migration/snapshot tests.
- Canonicalize theo provider+token trước so sánh version; legacy id khác không là lý do chấp nhận payload xung đột cùng version. Equal identical replay, equal different conflict; owner/source collisions fail closed.
- Regression: equalVersionDifferentId; cả hai thứ tự ACTIVE/REVOKED và seed duplicate legacy data, id collision khác token, reload/restart, newer renewal hợp lệ. Migration không mint server version hoặc xóa entitlement khác.
- Acceptance: không hồi sinh quyền vì đổi id; giữ dedupe đã sửa vòng 3.

## T08 — Tombstone đúng token và chỉ do server cấp (R07)

- Depends T07. Files: nhánh rejected trong `A/utils/BillingManager.kt`, `PurchaseVerifier.kt` nếu cần typed refresh-required; revocation tests.
- Bỏ SKU match và tombstone tự tạo state/version. Chỉ apply server snapshot hợp lệ từ T06; rejection không snapshot chuyển thành authoritative-refresh-required/retry, không tự revoke token khác. T10 sẽ nối refresh end-to-end.
- Regression: rejectionWithoutSnapshot/version, rejectionOfOldToken/SKU; malformed tombstone, receipt expired cũ + renewal token mới, đúng tombstone revoke và reload. Sửa các test vòng 3 vốn đòi client tự revoke từ rejection thiếu snapshot: thay bằng server snapshot thật và giải thích contract.
- Acceptance: không có client `snapshotVersion + 1` cho quyền Play; expired X không đổi Y; outcome incomplete được giữ để T10 xử lý.

## T09 — Phiên hợp lệ trước mua và recovery 401 (R09)

- Depends T08. Files: `A/utils/AppAuthManager.kt` session token lifecycle, `A/utils/billing/PlayPurchaseVerifier.kt` credential provider/result, `A/utils/VipPurchaseActionCoordinator.kt`, `BillingManager.kt` preflight; session/readiness tests.
- URL configured khác authenticated-ready. Token expired/missing phải refresh hoặc re-auth trước launch Play; không chỉ đọc idToken lưu lâu trong profile. Owner/generation capture xuyên token acquisition, verify và callback. Server vẫn là bên xác thực chữ ký/token.
- Chốt supported identity SDK refresh/re-auth theo API hiện dùng; dùng bounded retries. Không 401→retry vô hạn token cũ, không silent trial fallback. Callback reauth/UI phải cụ thể và lifecycle-safe.
- Regression: expiredSession preflight; missing token, token expires giữa preflight/verify, 401 refresh success/fail, logout/A→B→A trong refresh, valid session control; test coordinator không mở Play khi cần re-auth.
- Acceptance: không biết chắc token hết hạn mà vẫn cho purchase; đã trả tiền trước network error có recovery khi user đăng nhập lại.

## T10 — Android restore thật theo app account (R01 client)

- Depends T09. Files: `A/utils/billing/PurchaseVerifier.kt`, `PlayPurchaseVerifier.kt` restore API, `BillingReconciliation.kt`, `A/utils/BillingManager.kt`; dialog VIP chỉ nếu hiển thị partial/reauth cần thay; restore integration tests.
- Call server `/restore` từ restore/sync kể cả catalog rỗng; query device là bổ sung candidates. Apply returned snapshot bằng store/profile đúng owner và session, tôn trọng T07/T08. Server partial/error không toast all-restored; empty server/absence chỉ thu hồi theo tombstone/contract rõ, không xóa promotion.
- Regression: emptyCatalogMustReachRemoteRestore thay thành test end-to-end body/auth/owner + server active→profile VIP; fresh install; server revoked/local lifetime stale; Play account khác app account; offline/cache; late generation; missing Play service nhưng server reachable; malformed snapshot.
- Acceptance: máy mới có thể lấy quyền đúng owner từ server; cache cũ nhận revoke; không phụ thuộc local receipt để lấy app-account entitlement. Phải có assertion endpoint được gọi thật, không chỉ seed cache rồi assert còn VIP.

## T11 — Ack authority và durable grant trên Android (R10)

- Depends T10. Files: `A/utils/BillingManager.kt` apply/ack flow, typed server result nếu cần; billing result tests. Backend outbox chỉ chỉnh nếu cần contract phối hợp, ghi rõ phạm vi trong checkpoint.
- Chốt server atomic grant+outbox là chủ thể ack; không chặn apply verified snapshot vì SDK ack dư thừa. Nếu giữ client ack hỗ trợ, nó không thay đổi authoritative grant thành failure. Không cấp VIP từ unverified receipt.
- Regression: redundantClientAckFailure; server already acknowledged nhưng local flag false; client offline sau verify; durable server commit/response lost rồi restore; pending/rejected; apply store failure; exactly-once UI và stale session. Giữ kiểm store commit/profile projection đúng đã có.
- Acceptance: durable grant được áp dụng hoặc báo lỗi lưu/phiên đúng, không phụ thuộc duplicate ack. Backend retry/restart tests vẫn đạt.

## T12 — Cancellation worker Drive (R11)

- Depends T11. Files: `A/utils/GoogleDriveBackupWorker.kt`, transport seam `GoogleDriveService.kt` chỉ khi cần cancellation; worker tests.
- Cooperative cancellation sau token/folder và trước remote mutation/commit; propagate CancellationException. Giữ owner/session/VIP/CAS. Rà việc update thất bại 404 rồi re-create cũng cần checkpoint. Snapshot cleanup phân biệt canceled/retry/terminal đúng policy, không mất snapshot worker khác.
- Regression: canceledDriveWorker probe; cancel cùng owner/session khi chờ folder, replace work, cancel trước re-create, retry snapshot reuse, VIP revoke/logout controls cũ. Không chỉ test hủy bằng cách đổi user.
- Acceptance: chưa gửi request thì canceled không bắt đầu upload; không claim có thể thu hồi bytes đã gửi. Thiết bị WorkManager thực nằm T13.

## T13 — Nghiệm thu theo acceptance clause, không theo số probe (R12)

- Depends T12. Files: báo cáo/progress vòng 4, `docs/billing/ROUND4_ACCEPTANCE.md`, chú thích đính chính báo cáo M11/matrix/progress vòng 3, integration tests.
- Kiểm từng clause của T01–T12 có production entrypoint và evidence. Chạy tất cả permanent Android/backend tests, lint/build. Kiểm contract fixtures xuyên backend HTTP → Android parser → store/profile → watermark/Drive; không thay production wiring bằng fake toàn flow.
- Tự rà biến thể chưa nằm probe cũ: same token/different id/version; mọi subscription state; hai request đảo thứ tự; empty local/empty server/partial; auth config một phần; token hết hạn; cancel không đổi user; stale SDK ack.
- Device/external matrix: Play internal track/license tester mua/restore/pending/refund/renewal; đăng nhập/rotation/process death; hai device khác Play account; watermark export PDF/ID/OCR; Drive cancel/retry; backend restart volume, RTDN thật. Chỉ chạy khi môi trường có sẵn và đúng phạm vi; không tự deploy/charge/ghi tài liệu thật.
- Acceptance: không unresolved P1 cục bộ, 19 probe giữ invariant đúng và kiểm thử mở rộng đạt; mọi ngoại lệ có bằng chứng. External gates thiếu ghi NOT RUN, không “100% READY”. Nếu phát hiện lỗi mới, tự thêm gói nhỏ trong phạm vi VIP, regression và checkpoint rồi làm trước tổng kết; không yêu cầu người dùng canh gói.

## Kiểm tra và báo cáo

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
# Đối chiếu red harness trong T00; không dùng đồng thời với permanent copy trùng class.
./gradlew.bat -I build/vip-audit-20260927/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound4AuditTest --offline --console=plain
node --experimental-strip-types --test build/vip-audit-20260927/backend-probes.test.ts
# Sau khi có permanent suite: dùng tên suite thực tế cho từng gói.
./gradlew.bat :app:testDebugUnitTest --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
# Gate host cuối, chạy lại tests thật
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
```

Mỗi REPORT ghi: file thay đổi, contract/API, red→green evidence, commands và counts, tests của gói trước không regression, known future failures, external gates, gói tiếp. Baseline/probe logs riêng; cache Gradle permission lỗi xử lý như môi trường, không thay code để né.

## Prompt gửi Gemini một lần

```text
Đọc E:\DU AN AI\T-Scanner\PLAN_VIP_GEMINI_3_8_FLASH_ROUND4_AUTORUN_2026-09-27.md
và RECHECK_VIP_FULL_ROUND4_2026-09-27.md.
Tôi giao Gemini 3.8 Flash thực hiện TOÀN BỘ T00–T13, tuần tự từ đầu đến cuối.
Bạn được sửa code/test trong phạm vi kế hoạch, tự kiểm thử và tự chuyển gói,
KHÔNG hỏi tôi xác nhận từng gói. Đọc progress nếu đã tồn tại để tiếp tục phần dở.

Giữ thay đổi có sẵn, ownership tuần tự, regression gọi production thật; không sửa
chỉ một case để xanh. M00–M11 vòng 3 chưa đáp ứng đủ acceptance: xem bằng chứng mới.
T00 tái hiện red probes; known failures thuộc gói tương lai không chặn mọi gói.
Các phần đã hoàn tất phải giữ xanh. Không xóa/skip test hoặc đổi expected để che lỗi.
Sau mỗi gói ghi REPORT_VIP_R4_Txx.md và PROGRESS_VIP_R4_AUTORUN.md rồi tự tiếp tục.

Không deploy/charge/sửa Console/ghi Drive thật hoặc thêm secrets. Thiếu môi trường
bên ngoài thì ghi NOT RUN và hoàn thành mọi code/test độc lập trước; không dừng
cả kế hoạch chỉ vì credentials chưa có. Chỉ hỏi khi có blocker bắt buộc người dùng.
Khi context làm mới, đọc checkpoint và tiếp tục. Cuối T13 rà từng acceptance clause,
chạy tests/build/lint và gửi một báo cáo cuối đúng bằng chứng, không kết luận
100% hoàn tất từ việc vài probe cũ đã xanh.
```
