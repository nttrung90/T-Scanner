# Tái kiểm tra VIP sau M00–M11 — vòng 4, 27/09/2026

## Kết luận và bằng chứng

**Vẫn còn lỗi, chưa đủ điều kiện nghiệm thu VIP.** Đã đối chiếu checkout hiện tại với kế hoạch vòng 3, `PROGRESS_VIP_R3_AUTORUN.md`, báo cáo M08/M11 và contract. Không sửa mã sản phẩm/test gốc. Chỉ thêm harness audit và tài liệu vòng 4.

| Kiểm tra mới | Kết quả |
|---|---|
| Android `testDebugUnitTest --rerun`, lint, assemble debug, offline | BUILD SUCCESSFUL, **919 tests, 0 failures/errors/skipped** |
| Lint XML | **0 errors, 779 warnings** |
| Backend test gốc | **78/78 PASS** |
| Probe Android vòng 4 | **9 tests: 8 FAIL, 1 PASS**, 0 errors/skipped |
| Probe backend vòng 4 | **10 tests: 9 FAIL, 1 PASS** |
| Play license tester, OAuth/Google API/RTDN thật, Drive thật, device/process death | **NOT RUN trong lượt này** |

Tổng **19 probe mới, 17 assertion FAIL, 2 đối chứng PASS**. Đây là các vi phạm invariant của production logic, không phải lỗi biên dịch. Hai đối chứng: RSA user token đúng audience được chấp nhận; canceled-active còn hạn được BillingManager cấp quyền đúng. Backend probe dùng RSA/JWKS tổng hợp, fake HTTP và Play response; Android probe gọi manager/store/verifier/worker thật với seam kiểm soát. Không gửi token/receipt hay tài liệu thật ra mạng.

Bằng chứng ở `build/vip-audit-20260927/`: `git-before.txt`, `baseline.log`, `baseline-counts.json`, `baseline-results/`, `backend-baseline.log`, `backend-probes.test.ts`, `backend-probes.log`, `VipRound4AuditTest.kt`, `audit.init.gradle`, `android-probes.log`, `android-probe-results.xml`. Baseline XML đã được lưu trước khi probe ghi đè báo cáo Gradle.

## R01 — P1 — Chưa có restore theo app account; backend trả cache chưa refresh

- `BillingReconciliation.kt:151-170` chỉ đọc store local khi Play catalog rỗng, rồi trả NoActivePurchases. Android verifier chỉ có `/verify`, không có đường `/restore`/snapshot theo app account.
- Probe `emptyCatalogMustReachRemoteRestore` FAIL: máy mới, app account hợp lệ, catalog rỗng → **0 request HTTP**. Thiết bị mới không lấy được VIP của tài khoản; lifetime bị revoke trên server cũng không được cập nhật nếu máy giữ cache mà không có receipt local.
- Backend `verifier.ts:370-400` chỉ verify candidates caller gửi rồi trả mọi entitlement đã lưu. Probe `empty-candidate restore must refresh known lifetime receipt before success` FAIL: lifetime từng active, Play đã refunded, restore `purchases=[]` vẫn trả VERIFIED_ACTIVE.
- M08 đã sửa lỗi tự revoke khi catalog rỗng nhưng chưa thực hiện hết yêu cầu restore server-authoritative. Contract vòng 3 mục 1.2 vẫn yêu cầu server restore là nguồn quyền.
- Nghiệm thu: server refresh known tokens theo policy freshness rõ ràng, phân biệt stale/transient/partial; client gọi server dù Play rỗng, apply snapshot đúng owner/session; test fresh install và revoked lifetime không có local receipt. Không xóa cache vì lỗi mạng.

## R02 — P1 — Auth còn bỏ sót audience và cấu hình Pub/Sub

- `backend/.../auth.ts:223-224`: `config.googleClientId` không được dùng để điền `expectedAudience`; `:252` vẫn coi configured. `:423` chỉ kiểm audience khi expectedAudience có giá trị.
- Probe configured googleClientId FAIL: truyền `googleClientId='expected-client'`, RSA token aud khác vẫn được chấp nhận. Phạm vi: cách khởi tạo bằng config này; khi ENV GOOGLE_CLIENT_ID cung cấp expectedAudience thì không phải cùng lỗi.
- `auth.ts:550,558` audience/service-account của PubSub đều tùy chọn; không có kiểm issuer ở nhánh PubSub. Hai probe FAIL: thiếu cấu hình PubSub vẫn nhận token RSA hợp lệ nhưng dành cho nơi khác; khi đã cấu hình audience/email, issuer sai vẫn qua.
- RSA signature verification đã có thật; không còn kết luận cũ “chỉ HMAC”. Lỗi hiện tại nằm ở đầy đủ claim/config và fail-closed. `/readiness` (`index.ts:71-84`) chỉ kiểm storage+AuthService.isConfigured, không chứng minh cả user auth, push auth, Play credential đã sẵn sàng.
- Nghiệm thu: normalize config một lần; audience bắt buộc và thống nhất; route PubSub thiếu audience/service-account phải fail closed; issuer/email_verified đúng; readiness từng capability và HTTP integration tests. Không bật fallback HMAC ngầm để vượt gate.

## R03 — P1 — subscriptionsv2 chưa được dùng thật; state và product chưa bảo toàn

- `googlePlayClient.ts:242` vẫn gọi URL `purchases/subscriptions/{sku}/tokens/{token}` (v1), dù có hàm parse V2. Vì vậy positive test parse V2 không chứng minh production đang query V2.
- `:328` không thấy product yêu cầu thì lấy `lineItems[0]`. Unknown state không bị reject; PAUSED chỉ đổi autoRenewing=false. `verifier.ts:125,192` vẫn quyết định theo paymentState/expiry/autoRenewing, không theo `subscriptionState` đầy đủ.
- Ba probe qua production HTTP adapter+verifier đều FAIL: state UNKNOWN nhận active; PAUSED với expiry tương lai thành CANCELED_ACTIVE; receipt line item product khác vẫn cấp VIP product caller yêu cầu. Đây là payload tổng hợp để kiểm boundary, không phải bản ghi giao dịch Play thật. State PAUSED không được suy active chỉ vì timestamp.
- ON_HOLD được map paymentState=0 rồi return PENDING trước cập nhật store; code có nguy cơ giữ quyền cache bị suspend (CODE VERIFIED, chưa có probe riêng cho nhánh này).
- Nghiệm thu: gọi V2 thật; strict state/product; mapping chung cho verifier và RTDN; suspended/unknown không grant; không downgrade trạng thái bằng boolean autoRenewing. Bộ test phải xác nhận URL lẫn payload/state cuối cùng.

## R04 — P1 — CAS chỉ chặn một chiều, callback cũ vẫn hồi sinh quyền

- `sqliteDriver.ts:70-85`: khi version mismatch, chỉ ignore nếu existing VERIFIED_ACTIVE và incoming EXPIRED. Các cặp trạng thái khác vẫn ghi và tăng version.
- Probe `late ACTIVE verification must not resurrect newer EXPIRED snapshot` FAIL: request ACTIVE cũ bị giữ; request EXPIRED mới commit; thả callback cũ → store thành VERIFIED_ACTIVE.
- `verifier.ts:106` dùng undefined cho expectedVersion khi record chưa tồn tại; nhánh revoke in-app `:235` không truyền expectedVersion. Đây là các nhánh phải mở rộng kiểm thử, không coi probe một chiều trước đây là CAS tổng quát.
- Nghiệm thu: conflict áp dụng cho mọi state và cả expected-absent; refresh authoritative sau conflict theo retry hữu hạn hoặc serialize đúng scope. Không lựa chọn ưu tiên ACTIVE/EXPIRED cố định. Test bảng state, verify↔RTDN, hai connections và first-bind concurrency.

## R05 — P1 — Receipt hết hạn/thu hồi có thể bị bind nhầm owner trước kiểm hash

- `verifier.ts:160,235` lưu expired/revoked branch trước kiểm `externalAccountIdFromPlay` ở `:281-282`.
- Probe expired receipt owned by another Play hash FAIL: receipt chưa có local DB record, Google hash thuộc A nhưng caller B, expiry đã qua → bản ghi bị bind vào B. Nếu receipt gia hạn lại, A có thể bị ownership conflict.
- Nghiệm thu: validate owner/hash trước mọi mutation ở mọi state, kể cả inactive. Không đổi owner đã bind. Kiểm active/expired/revoked/pending, receipt legacy không hash và concurrent first bind; owner lấy từ authenticated principal.

## R06 — P1 — Android “strict” parser vẫn không ràng buộc response với request

- `PlayPurchaseVerifier.kt:427-477` kiểm state/expiry nhưng không so sánh token/product/type/owner đầy đủ; vẫn fallback source, id và version (coerceAtLeast).
- Probe `responseDifferentTokenMustBeRejected` FAIL: request receipt X, response SUCCESS receipt Y cùng owner được trả VerificationResult.Success. BillingManager apply theo entitlement trả về, không có request-token crosscheck bổ sung.
- Nghiệm thu: validate toàn bộ schema, source/product/type, positive finite version, request↔response binding; malformed tombstone không được chuyển thành quyền tự suy diễn. Test khác token/owner/product/type, missing/null/negative fields, unknown source, HTTP code/body conflict.

## R07 — P1 — Fallback tombstone tự tạo version và thu hồi nhầm token cùng SKU

- `BillingManager.kt:908-924` khi rejected không có tombstone: tìm matching bằng token **hoặc productId**, dựng state và `snapshotVersion + 1` tại client.
- Hai probe FAIL: version server v10 bị client đổi thành v11 khi response không chứa snapshot; rejection của receipt cũ cùng SKU làm receipt mới còn hạn bị thu hồi.
- Rejected bị lỗi parse tombstone cũng có thể rơi vào fallback này. Đây là vi phạm contract server version và token ownership dù probe vòng 3 “rejection phải revoke” đã xanh.
- Nghiệm thu: không mint server version, không match theo SKU để thu hồi; thiếu snapshot hợp lệ phải yêu cầu refresh authoritative và báo incomplete/retry. Test rejected X không đổi Y, malformed tombstone không xóa cache, response đúng mới revoke đúng token.

## R08 — P1 — Đổi id cho cùng token vượt hàng rào equal-version conflict

- `BillingEntitlement.kt:219-224`: equal version, payload khác nhưng id khác thì thay entry, thay vì conflict.
- Probe equalVersionDifferentId FAIL: REVOKED v9 rồi ACTIVE v9 cùng token id khác → VIP hồi sinh.
- Nghiệm thu: canonical identity trước merge, equal+identical replay, equal+different conflict bất kể legacy/canonical id. Migration không cấp quyền mới, không tạo server version. Test cả thứ tự và reload, id/token collision.

## R09 — P1 — Token đăng nhập hết hạn vẫn qua preflight mua

- `AppAuthManager.kt:159` trả idToken lưu trong profile; `PlayPurchaseVerifier.kt:158` readiness chỉ xét URL HTTPS. Kiểm token `:273` chỉ blank, không expiry; HTTP 401 `:360` thành TransientError, chưa có luồng refresh/re-auth tích hợp.
- Probe expiredSessionMustNotBePurchaseReady FAIL: token có exp=1 vẫn được coi ready. Trong điều kiện URL và product sẵn sàng, user có thể mở Play mua rồi không verify được đến khi đăng nhập lại.
- Nghiệm thu: readiness gồm valid/refreshable authenticated session; refresh/re-auth trước purchase và recovery 401 sau network; owner/generation xuyên suốt. Không kiểm chữ ký Google trên client để thay backend; local expiry chỉ giúp UX/preflight.

## R10 — P1 — Client acknowledge trùng vẫn chặn entitlement đã durable ở server

- `BillingManager.kt:837` gọi SDK acknowledge nếu Purchase local chưa acknowledged, chỉ apply snapshot khi ack OK. Backend đã chịu grant+outbox atomic, nhưng client vẫn giữ dependency cũ.
- Probe durableVerifiedEntitlementMustSurviveRedundantClientAckFailure FAIL: verifier SUCCESS mô phỏng durable server grant, SDK ack ERROR → store Android chưa nhận VIP.
- Nghiệm thu: một authority ack rõ ràng; apply durable verified snapshot độc lập với ack client dư thừa, server outbox recovery. Test server ack xong nhưng local Purchase stale, SDK/network lỗi, restart, đúng một UI outcome và session switch. Không cấp quyền từ receipt chưa verify.

## R11 — P2 — Coroutine worker bị hủy vẫn tiếp tục upload

- Worker đã thêm VIP/session checkpoint sau token/folder; giữ lại cải thiện đó. Nhưng `GoogleDriveBackupWorker.kt:147-202` không kiểm coroutine cancellation sau các bước đồng bộ.
- Probe canceledDriveWorkerMustNotStartUploadAfterTokenWait FAIL: hủy Job ngay trong token lookup khi user/session/VIP giữ nguyên, uploader vẫn được gọi 1 lần. Không gửi dữ liệu Drive thật.
- Nghiệm thu: cancellation cooperative/checkpoint sau chờ, trước mutation và commit; propagate cancellation đúng; kiểm snapshot cleanup/retry khi WorkManager dừng hoặc replace work. Không hứa thu hồi byte đã gửi. Test cả cancel cùng user/phiên, không chỉ logout.

## R12 — P2 — Báo cáo hoàn tất quá mức, còn yêu cầu kế hoạch chưa thực hiện

- `REPORT_VIP_R3_M11.md:88-92` khẳng định mọi lỗi kiến trúc đã giải quyết triệt để; `PROGRESS_VIP_R3_AUTORUN.md:24` nói không còn P1 cục bộ. 17 assertion mới bác bỏ kết luận này.
- 919/78 tests xanh là số liệu đúng và đã chạy lại. Thiếu sót là đồng nhất probe cũ xanh với mọi acceptance criterion: M08 chưa có server restore, M02 vẫn query V1, M04 CAS chọn một state pair, M09 ack trùng, M10 thiếu cancellation.
- Nghiệm thu: checklist từng acceptance clause, đường production gọi thật, mở rộng test ma trận; phân loại implementation/probe/runtime/external gate; không lặp lại “100% triệt để”. Không sửa expected thành hành vi lỗi để xanh.

## Cải thiện đã xác nhận và giới hạn

Bootstrap verifier có cấu hình thật, HTTPS readiness và redirect disabled; RSA/JWKS verifier có implementation; stable server id và token dedupe đã có; canceled-active được grant; store projection bỏ double-write; outbox enqueue trong transaction; event watermark có DB column; VIP/session recheck Drive đã thêm. Không yêu cầu viết lại các phần tốt này.

Đã đọc các điểm tích hợp auth/profile/restore và worker, đối chiếu quyền watermark dùng AppAuthManager ở lượt trước cùng baseline hồi quy hiện tại. Không tìm thêm lỗi watermark độc lập trong phạm vi kiểm tra này; quyền đầu vào sai do R01–R10 vẫn ảnh hưởng watermark/Drive. Không có kiểm chứng UI/Play/Drive thật trong lượt này và không tuyên bố toàn bộ app hết lỗi.

Tài liệu Google đã đối chiếu: [SubscriptionPurchaseV2 và state](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2), [Pub/Sub authenticated push](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions). Các khuyến nghị state và audience/email dựa trên contract chính thức; các lỗi cụ thể dựa trên mã/probe ở trên.

## Chạy lại

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat -I build/vip-audit-20260927/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.VipRound4AuditTest --offline --console=plain
node --experimental-strip-types --test build/vip-audit-20260927/backend-probes.test.ts
```

Hiện hai command đỏ do assertion cố ý kiểm invariant chưa được đáp ứng; không dùng init audit trong baseline bình thường. Kế hoạch tự chạy Gemini: `PLAN_VIP_GEMINI_3_8_FLASH_ROUND4_AUTORUN_2026-09-27.md`.
