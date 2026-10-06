# Kiểm tra VIP sau sửa X00–X12 — vòng 8, 01/10/2026

## Kết luận và phạm vi

**Còn 10 nhóm cần sửa: 9 nhóm tái hiện bằng production probes và 1 nhóm auth recovery xác nhận từ đường gọi UI hiện tại, chưa chạy UI thiết bị.** Các probes gốc vòng 7 đạt 27/27; sửa CAS, linked khác SKU, parser tombstone, default token null và chặn commit coroutine sau destroy đã có chứng cứ host. Acceptance mở rộng X03/X06/X07/X08/X10 vẫn còn thiếu. Payload Google hiện hành cũng phát hiện lỗi unknown RTDN thiếu SKU và bỏ qua full refund lifetime.

Audit chỉ đọc production, chạy tests và tạo tài liệu/probes mới. SHA-256 của **300 file production/resource/backend/config** trước và sau audit không đổi. Không sửa source sản phẩm, permanent tests, signing/SKU/giá hoặc thay đổi hiện có của người dùng.

[Kế hoạch Gemini Y00–Y13](</E:/DU AN AI/T-Scanner/PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND8_AUTORUN_2026-10-01.md>) yêu cầu tự làm tuần tự từ đầu đến cuối, lưu checkpoint và không chờ người dùng sau mỗi gói.

## Kết quả kiểm chứng hiện tại

| Kiểm tra | Kết quả |
|---|---|
| Full Android JVM suite, chạy lại test task | **964 tests, 0 failures/errors/skips** |
| Full backend suite | **108/108 PASS**, exit 0 |
| Original Android round7 harness | **18/18 PASS** |
| Original backend round7 harness | **9/9 PASS**, exit 0 |
| `lintDebug` / `assembleDebug` | BUILD SUCCESSFUL; lint **0 errors, 757 warnings**, một số task UP-TO-DATE |
| Android probes mở rộng | **20 tests: 14 FAIL, 6 PASS, 0 errors** |
| Backend probes mở rộng | **13 tests: 9 FAIL, 4 PASS**, exit 1 |
| Tổng probes mới | **33 tests: 23 FAIL, 10 PASS** |
| ADB | Không có thiết bị kết nối |

23 failures thuộc 9 nhóm tái hiện; **không phải 23 lỗi độc lập**. B801/B802/B809 là cùng một lỗi retry, lần lượt cùng process, reopen và hai physical SQLite connections. G09 không được tính vào số failures vì xác nhận bằng source/UI call path.

Sources, XML/log và fixture notes: [docs/vip-round8-20261001/README.md](</E:/DU AN AI/T-Scanner/docs/vip-round8-20261001/README.md>). Full baseline XML, original-round7 XML, git snapshot và fingerprint inventory nằm tại `build/vip-audit-round8-20261001/`.

## G01 — P1: RTDN linked lỗi rồi gửi lại cùng event bị consumed

**B801/B802/B809 FAIL.** Known new canceled receipt được update; linked old query trả 503; handler trả ERROR. Khi gửi lại **đúng event/time/token**, nó trả SKIPPED_STALE, không query lại old token. Old receipt vẫn `VERIFIED_ACTIVE` trong khi authority mới là EXPIRED. Tái hiện cả sau close/reopen SQLite và trên worker/connection thứ hai.

[rtdnHandler.ts:444](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/rtdnHandler.ts:444>) cập nhật watermark memory trước linked resolution ở line 449–464. Đồng thời event time đã nằm trong durable record; [sqliteDriver.ts:53](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/storage/sqliteDriver.ts:53>) đánh dấu cùng event là stale và handler trả ở line 434–441 trước hoàn tất bước linked.

Route RTDN hiện map ERROR→503 và mọi status khác→200 tại [index.ts:232](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/index.ts:232>). Vì thế retry của webhook sẽ nhận thành công HTTP sau khi bước cần làm vẫn bị bỏ. Probe gọi handler/store; chưa chạy live PubSub delivery.

Tác động: stale VIP/refund/expiry có thể tồn tại dù event được redeliver. Sửa **Y01**: durable completion/pending work cho toàn event hoặc cơ chế tương đương; stale entitlement write không đồng nghĩa linked side effects đã hoàn tất. Không bỏ event-order guard hoặc reapply dữ liệu cũ để đạt test.

## G02 — P1: Unknown linked RTDN mất new entitlement/ack hoặc lỗi với schema Google

**B803/B804 FAIL.** New yearly subscription ACTIVE, chưa acknowledge, có linked monthly đã acknowledge thuộc owner hợp lệ. Test dùng production V2 transport/parser, matching obfuscated owner hash và PubSub envelope type 4 không có `subscriptionId`. Old monthly được refresh thành EXPIRED, nhưng new yearly không có record và không có durable ack job. Handler vẫn trả `PROCESSED/newState=REVOKED`.

[rtdnHandler.ts:245](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/rtdnHandler.ts:245>) chỉ bind new token khi state là PENDING_PURCHASE_CANCELED; mọi linked unknown state đều trả PROCESSED/REVOKED ở line 276–280. Nhánh ACTIVE mới không xử lý grant/bind/ack.

Tác động: nếu RTDN đến trước verify từ app, server không còn receipt active dù giao dịch mới đã thanh toán; thiếu acknowledgement có nguy cơ refund/revoke. Google yêu cầu xử lý purchase ACTIVE và acknowledgement trong thời hạn của purchase mới. [Subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions). Linked token của upgrade/downgrade được mô tả trong [Subscriptions V2 reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).

**B810 FAIL** là biến thể unknown pending-canceled dùng PubSub envelope thực type 20, cũng không có `subscriptionId`. V2 query/parser thành công, nhưng handler gán `productId: subscriptionId` tại line 250; SQLite bind ở [sqliteDriver.ts:205](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/storage/sqliteDriver.ts:205>) ném `ERR_INVALID_ARG_TYPE` vì productId undefined. Old linked đã refresh, new tombstone chưa lưu. Probe bắt lỗi production rồi assert, không phải exception của fixture. Route catch ở [index.ts:239](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/index.ts:239>) sẽ trả 500; đây là suy luận từ source, chưa chạy HTTP route cho ca mới.

Schema SubscriptionNotification Google chỉ có version/type/token; type 20 là pending purchase canceled. Không yêu cầu notification mang SKU; cần lấy product identity từ Play V2/catalog. [RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference).

Đối chứng **B808/B811 PASS**: normal authenticated verify cùng new paid receipt lưu ACTIVE/enqueue ack; known canceled receipt xử lý cùng envelope không có SKU thành công. Sửa **Y02**, tái sử dụng authority/ownership/CAS/outbox policy hiện có và chuẩn hóa identity theo schema thật; không cấp VIP từ notification type đơn thuần hoặc bind owner không xác định.

## G03 — P2: Unknown canceled token bỏ qua bind failure nhưng báo processed

**B805 FAIL.** Inject một lần `bindOrUpdate` trả `{success:false, casConflict:true}` cho new canceled receipt. Handler bỏ qua result, đánh dấu event processed; cùng event retry SKIPPED_STALE và tombstone chưa được lưu.

[rtdnHandler.ts:262](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/rtdnHandler.ts:262>) await bind mà không inspect `success/casConflict/staleIgnored`; line 271–280 vẫn update watermark/trả PROCESSED.

Đây là fault injection cho storage/CAS contract, **không phải chứng cứ cấp VIP sai hoặc race tự nhiên đã đo được**. Sửa **Y03**: bounded retry/re-read và kiểm tra durable completion; thất bại chưa giải quyết không consumed. Giữ G01 retry bảo toàn sau restart.

## G04 — P2: Receipt aggregation, freshness và số lượng chưa nhất quán

| Probe FAIL | Actual |
|---|---|
| B806 | Backend restore trả 3 results cho 2 unique tokens, old linked SUCCESS bị lặp |
| A804 | Một token thất bại local và remote được đếm **2** failures thay vì 1 |
| A805 | Remote SUCCESS mới cho cùng token vẫn giữ **1** lỗi local cũ |
| A812 | Snapshot/results remote rỗng nhưng cache ACTIVE được báo như **1 receipt vừa restore mới** |

[verifier.ts:465](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/verifier.ts:465>) push candidate result trực tiếp; nhánh linked line 485–518 upsert, nhưng candidate old đến sau lại push duplicate. [BillingReconciliation.kt:337](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt:337>) cộng failure count remote với deviceUnresolved bằng số lượng, không giữ token identity. Nhánh results rỗng ở line 351–352 dùng active cache size làm fresh count.

Tác động: UI báo sai tiến độ/lỗi/mức hoàn tất; không chứng minh cấp quyền trái phép. A812 giữ quyền cached theo policy hiện tại, chỉ yêu cầu không relabel cache thành fresh verification. Sửa **Y04 backend**, **Y06 Android**: aggregate theo unique token + kết quả authority cuối/freshness; không dedupe chỉ bằng SKU hoặc bỏ cache toàn bộ.

## G05 — P2: Pending và partial-no-active vẫn mất trạng thái/retry

**A801/A802/A803/A806 FAIL.** ACTIVE + PENDING trả `Restored(count=1,totalCount=1,failedCount=0)`; all-pending thành NoActivePurchases; một EXPIRED và một TRANSIENT_ERROR nhưng không có quyền active cũng thành NoActivePurchases. Local PENDING chưa có trên server biến mất khỏi terminal outcome khi server trả snapshot rỗng.

[BillingReconciliation.kt:136](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt:136>) có pending indicator nhưng không giữ nó trong kết quả cuối. Line 276 gửi RestoreRequest với danh sách candidates rỗng; line 337 chỉ đếm TRANSIENT_ERROR/REJECTED, bỏ PENDING; line 364–365 khi `isVip=false` luôn trả NoActivePurchases. Backend đã giữ PENDING sau X04, nhưng Android consumer chưa xử lý đủ.

Tác động: người chờ thanh toán hoặc cần retry bị báo như không có giao dịch; active+pending bị báo hoàn tất. Pending **không phải paid quyền** và không bắt buộc gọi nó là failure; cần outcome/pendingCount riêng để tránh full-success wording. Sửa **Y05** và Y06 UI mapping, giữ no-active-completed khác pending/unresolved/partial-no-active; không revoke từ pending hoặc lỗi query.

## G06 — P2: SDK callback sau destroy vẫn đến disposed listener

**A807/A808 FAIL.** Sau `BillingManager.destroy()`, callback Play USER_CANCELED hoặc OK+PENDING vẫn gọi listener **1 lần** thay vì 0.

[BillingManager.kt:709](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:709>) không kiểm tra destroyed ở SDK entrypoint. USER_CANCELED line 731–736 đi thẳng notify; pending line 915–918 không vào coroutine guard. [BillingManager.kt:1305](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:1305>) dispatched runnable chỉ xét session stale, không xét manager/job đã dispose.

Đối chứng **A811 PASS**: noncooperative verification response sau destroy đã không commit/callback. Lỗi mới chỉ xác nhận callback lifecycle; **không kết luận bug commit sau destroy vòng7 còn tồn tại**. Sửa **Y10** tại cả entry và khi runnable thực thi; giữ xử lý receipt hợp lệ qua manager mới theo policy, không leak listener cũ.

## G07 — P2: Purchase chưa giữ owner/readiness qua reconnect/product await

**C804/C805 FAIL.** A bấm mua khi đang reconnect; chuyển sang B trước callback; coordinator vẫn gọi real BillingManager và FakeBillingClient nhận BillingFlowParams. Token hết hạn trong lúc chờ products cũng vẫn mở paid flow. B có synthetic JWT khớp B; ca expiry giữ owner A và session generation, không dựa vào tài khoản sai.

[VipPurchaseActionCoordinator.kt:126](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt:126>) kiểm tra credential/config trước wait, nhưng reconnect/query callbacks ở line 151–201 gọi launch không recheck. [BillingManager.kt:633](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:633>) capture purchase context mới từ user hiện tại khi launch, không biết owner đã bấm ban đầu; không có final auth guard trước Play.

Đối chứng **C807 PASS**: cùng owner, phiên hợp lệ sau wait vẫn tới fake BillingClient. Đây là production coordinator→Manager→BillingFlowParams path; không gửi thanh toán thật. C804 chứng minh action cũ mở dưới context mới, **không chứng minh receipt của A đã được chuyển sang B hoặc phát sinh charge thật**. Sửa **Y07**: operation token owner/generation từ button entry, cuối mỗi await và trước BillingClient launch đều kiểm tra readiness/owner/lifecycle.

## G08 — P2: Actual transport còn bỏ guard auth/HTTPS ở các đường khác nhau

**C801/C802/C803 FAIL.** Restore có token expired hoặc malformed, `isAuthReady=false`, vẫn gọi HTTP transport **1 lần**. Verify có URL HTTP, `isBackendConfigured=false`, vẫn gọi transport **1 lần**. X07 đã chặn HTTPS restore nhưng chưa áp dụng đủ policy trên actual sending path.

[PlayPurchaseVerifier.kt:611](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:611>) restore chỉ chặn token blank, không kiểm tra expiry/format. [PlayPurchaseVerifier.kt:242](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt:242>) verify gọi remote với mọi URL nonblank; line 295–323 cũng không dùng HTTPS guard trước bearer.

Đối chứng **C806/C808 PASS**: valid HTTPS restore gọi transport đúng một lần; missing credential đã được chặn. Probes dùng synthetic tokens/fake transport; **không gửi token ra mạng và không chứng minh Android build cụ thể đã truyền cleartext hay backend cấp quyền từ token giả**. Sửa **Y08**: configured/auth/session guard tại cả verify và restore trước transport; typed recovery đúng, cancellation không biến thành network error.

## G09 — P2: AuthRequired hiện chỉ đổi thông điệp, chưa thực hiện continuation

**Xác nhận từ source call path; UI thiết bị NOT_RUN.** Manager nhận AuthRequired rồi gọi `onComplete(false,message)` tại [BillingManager.kt:1134](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/utils/BillingManager.kt:1134>). Restore button của [VipUpgradeDialog.kt:185](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt:185>) chỉ toast, không invoke sign-in callback hoặc resume restore. Logged-in Home vẫn không truyền `onRequestSignIn` tại [HomeFragment.kt:314](</E:/DU AN AI/T-Scanner/app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt:314>); expired user đi vào prompt dismiss+toast.

Probe gốc C05 chỉ assert message có “đăng nhập/Phiên”; nó PASS nhưng không chứng minh sign-in bắt đầu/resume. Kế hoạch X07 đã yêu cầu Home/More recovery, sign-in success/cancel/error, continuation đúng một lần. Vì vậy phần nghiệm thu này chưa thực hiện đủ, không phải thay đổi yêu cầu mới.

Sửa **Y09**: preserve typed AuthRequired qua UI, có continuation restore/purchase đúng action, owner/generation, single-resume; callback cancel/error có terminal state. Thêm production UI/coordinator seam/instrumentation tests phù hợp, ghi riêng device gate.

## G10 — P1: Full refund lifetime bị RTDN bỏ qua, quyền vẫn ACTIVE

**B812 FAIL.** Receipt lifetime đã được normal verify và lưu `VERIFIED_ACTIVE`, expiry null. Play authority sau refund là `purchaseState=1`. PubSub envelope có `voidedPurchaseNotification`, `productType=2`, `refundType=1`, token/order hợp lệ, nhưng handler trả `PROCESSED / Non-billing notification noted`; receipt vẫn ACTIVE.

[rtdnHandler.ts:114](</E:/DU AN AI/T-Scanner/backend/billing-verifier/src/rtdnHandler.ts:114>) chỉ xét subscription/oneTime, nên voided refund đi vào nhánh bỏ qua. Route map PROCESSED→200; chưa chạy live push. Google định nghĩa productType 2 cho one-time và refundType 1 cho full refund tại [VoidedPurchaseNotification reference](https://developer.android.com/google/play/billing/rtdn-reference).

Tác động: webhook full refund đã được nhận nhưng lifetime VIP không bị thu hồi bởi luồng RTDN; không có expiry tự kết thúc. **B813 PASS** cho thấy normal account restore cùng authority refund lưu REVOKED, vì vậy không kết luận mọi đường reconciliation đều sai.

Sửa **Y11** riêng: xử lý voided full refund cho known receipt qua authority/owner/store, durable retry và idempotency; giữ signed push auth hiện có. Không đánh đồng one-time pending-canceled type 2 với hoàn tiền một purchase đã trả tiền; không tự revoke từ thông báo giả, token không bound hoặc lỗi Play tạm thời.

## Fixture integrity và mức độ đã kiểm chứng

Initial purchase probes gặp exception BillingFlowParams.Builder vì `unitTests.returnDefaultValues=true` làm mock Android `TextUtils.isEmpty(null)` trả false. Lỗi này **không tính là production defect**. Audit-only `AuditTextUtils.kt` phục hồi primitive isEmpty/equals đúng behavior, chỉ được thêm bởi round8 init; không mock app/Billing policy. Sau repair, C807 PASS và C804/C805 thất bại tại assertions hành vi, không phải SDK fixture exception. Khi chuyển regression, không copy shim vào production hoặc global test sourceSet tùy tiện.

Watermark/profile controls A809/A810 PASS cho fresh active và authoritative revoke. Full suite còn có Drive/backup/account isolation tests cũ. G01/G02 liên quan server entitlement có thể ảnh hưởng downstream gates, nhưng chưa đo export PDF hay Drive thật. Không gọi suite JVM/mock là device/end-to-end acceptance.

ADB rỗng. Play license tester/upgrade/refund/ack thật, OAuth expiry/re-auth, live PubSub RTDN delivery, staging restart với dịch vụ thật, Android process death/signed upgrade, PDF watermark export và Drive sync đều **NOT_RUN/BLOCKED_EXTERNAL**. Audit không deploy hoặc tạo giao dịch tiền thật.

Báo cáo vòng7 ghi các con số baseline đúng ở checkout này, nhưng coverage C05 chỉ message, A06 chỉ coroutine, B704 chỉ unknown-canceled query, không bao phủ tất cả acceptance được giao. Giữ báo cáo lịch sử; vòng8 cần record thêm variants/durable retry/UI recovery và không đánh dấu DONE chỉ từ probe gốc xanh.

## Lệnh tái hiện từ root workspace

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidRound8AuditTest --tests com.tscanner.app.RootRound8AuthAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round8-20261001/backend-round8-probes.test.ts
```

Lưu XML/log ngay sau mỗi run: Gradle run kế tiếp ghi đè chung thư mục results. Audit init chỉ thêm test sources vòng8; normal sourceSet chưa có probes mới. Final red run là bằng chứng lỗi có sẵn, không phải audit đã sửa production gây regression.
