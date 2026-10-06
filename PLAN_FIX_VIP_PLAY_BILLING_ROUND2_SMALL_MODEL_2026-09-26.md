# Kế hoạch sửa VIP Billing vòng 2 cho mô hình nhỏ

Ngày 26/09/2026 — repo `E:\DU AN AI\T-Scanner`.

Đọc trước: `RECHECK_VIP_PLAY_BILLING_ROUND2_2026-09-26.md` và harness `build/vip-billing-reaudit2/`. Kế hoạch này bổ sung, không xóa audit/kế hoạch B00–B12. Chưa sửa mã sản phẩm trong lượt audit.

## Kỷ luật và thứ tự

- Mỗi lượt giao **một gói Qxx**; chạy tuần tự vì có file chung. Không tự spawn nhiều agent sửa BillingManager/AppAuthManager/index.ts đồng thời. Khi được yêu cầu song song, chỉ tách backend/client sau contract đóng băng và ownership file độc quyền.
- Bảo toàn tất cả staged/unstaged/untracked. Không git reset/checkout/clean. Không sửa OCR/Drive/logout ngoài hook billing được liệt kê.
- Whitelist ghi dưới đây + test mới của gói + `REPORT_VIP_BILLING_ROUND2_<ID>.md`. File mới có tên đề xuất được phép; nếu đổi vị trí phải ghi mapping, không mở rộng subsystem.
- Regression phải gọi production logic/HTTP handler/storage thật tại boundary cần chứng minh. Fake chỉ ở Google API/network bên ngoài; không giả luôn persistence/auth/ownership đang cần test.
- Mỗi report ghi test trước đỏ/sau xanh, command và count thực, API handoff, rủi ro, việc chưa chạy. Không xóa assertion lỗi cũ hoặc bật local fallback để đạt xanh.
- Credentials/deployment/Console là gate riêng. Viết HTTP adapter, auth validation và database adapter có thể hoàn tất/test local khi chưa có credential thật; không để hàm throw Not implemented rồi gọi là xong.
- Không deploy/upload/đổi product/charge thật trong các gói code; không ghi secret vào APK/repo/log. Chưa cấu hình verifier đầy đủ thì chặn mở purchase mới với thông báo khả dụng, không tự cấp VIP hoặc im lặng mở thanh toán.

Thứ tự mặc định:

```text
Q00 -> Q01 -> Q02 -> Q03 -> Q04
                           |
                           v
Q05 -> Q06 -> Q07 -> Q08 -> Q09 -> Q10 -> Q11 -> Q12
```

Q05–Q10 là backend. Contract auth/JSON/version/durable store phải được cập nhật và bàn giao trước Q11. Q12 chỉ đóng release gate sau bằng chứng end-to-end; thiếu thiết bị/Console phải giữ NOT RUN.

Quy ước: `M/` = `app/src/main/java/com/tscanner/app/`; `T/` = `app/src/test/java/com/tscanner/app/`; `S/` = `backend/billing-verifier/src/`; `ST/` = `backend/billing-verifier/test/`.

## Q00 — Sửa fixture và cố định regression (R11)

- File: `T/BillingTestFixtures.kt`, mới `T/BillingRound2RegressionTest.kt`, `T/BillingPurchaseFixtureTest.kt`; mới `ST/round2-regression.test.ts`. Không sửa main source.
- Chuyển 10 assertion production thất bại từ harness sang regression vĩnh viễn; sửa encoding pending JSON bằng self-test getter SDK thực. Không sửa expectation lỗi business.
- Làm rõ verifier explicit fake trong test; ghi danh sách test dùng fallback mặc định, để Q11 loại bỏ phụ thuộc classpath JUnit trong production.
- Test: factory PURCHASED/PENDING; pending không acknowledge/grant; 10 ca production đỏ vẫn tái hiện; đối chứng replay/owner conflict qua.
- Nghiệm thu/dừng: harness phân biệt đúng pending và lỗi thật; snapshot baseline 834 Android/21 backend được giữ làm bằng chứng, không hardcode count mới.

## Q01 — Khóa migration legacy và guest ownership (R02)

- File: `M/utils/BillingManager.kt` chỉ bind/migration; `M/utils/billing/BillingEntitlementStore.kt` chỉ guest bind; `T/BillingGuestOwnershipTest.kt` mới, regression tương ứng.
- Bỏ dựng VERIFIED_ACTIVE từ last receipt/boolean global. Legacy phải UNVERIFIED_CLIENT, không tự thành paid. Không tự chuyển owner verified receipt bằng thao tác local; yêu cầu kết quả server ràng buộc owner. Không tăng snapshotVersion server khi bind cục bộ.
- Test: A mua -> B login -> C login; guest thật khác legacy; owner conflict; login nhiều lần; unknown legacy; crash giữa bind/clear; không cấp B từ A.
- Nghiệm thu: probe chuyển receipt A->B xanh; không làm mất bản ghi A; các gói sau nhận API migration rõ (unverified candidate, không grant).
- Dừng: không tự viết verifier HTTP, không sửa đăng nhập Google chung.

## Q02 — Context bất biến cho purchase/restore/reconcile (R04 và phần session R03)

- File: `M/utils/BillingManager.kt`, `M/utils/billing/BillingReconciliation.kt` chỉ truyền context, mới `M/utils/billing/BillingOperationContext.kt`; `T/BillingOperationSessionTest.kt` mới.
- Mỗi operation có ID, owner, generation lúc khởi tạo và origin. Bỏ dùng biến activePurchaseOwner chung cho restore/sync. Định nghĩa hợp đồng callback stale, bao gồm error/ITEM_ALREADY_OWNED, và kiểm lại khi callback được dispatch main thread.
- Test: A launch rồi B restore; query A về sau login B; A->B->A; hai operation chồng nhau; guest->login trong verify/ack; UI callback tới sau dismiss/session mới.
- Nghiệm thu: request restore của B không mang A; stale mutation/event bị loại, dữ liệu server hợp lệ của A chỉ ghi namespace A theo contract. Dừng sau handoff context cho Q04/Q11.

## Q03 — Monotonic snapshot và propagate lỗi lưu (R05/R06)

- File: `M/utils/billing/BillingEntitlement.kt`, `BillingEntitlementStore.kt`; `M/utils/AppAuthManager.kt` chỉ writer entitlement; `M/utils/BillingManager.kt` chỉ apply/result; `T/BillingSnapshotPersistenceTest.kt` mới.
- Equal version+identical payload = no-op; equal+khác payload = conflict, không ghi đè. Giữ server version độc lập local migration. Validate owner của từng item khớp snapshot.
- Một API apply có kết quả typed, không lưu hai lần rồi bỏ Boolean. Không success nếu commit false/throws; giữ trạng thái retryable và không hỏng quyền trước. Chốt grant/ack ownership với Q09, không tự coi local write fail là giao dịch Play thất bại/hoàn tiền.
- Test: ACTIVE/REVOKED cùng version, version cũ/mới; owner mismatch; commit false/throw trước/sau ack; restart; replay sau retry; receipt verified inactive không báo đã kích hoạt VIP.
- Nghiệm thu: hai probe equal-version và commit-failure xanh; result thật tới restore/UI, không toast thành công sai. Dừng sau writer API handoff.

## Q04 — Reconciliation thu hồi bền vững đúng nguồn (R03)

- File: `M/utils/billing/BillingReconciliation.kt`, `BillingEntitlementStore.kt`; `M/utils/AppAuthManager.kt` chỉ load/apply entitlement; `T/BillingRevocationReconciliationTest.kt` mới.
- Thay set FREE mù bằng snapshot/tombstone authoritative theo owner/context/version; update store và profile nhất quán. Promotion/lifetime khác nguồn còn hiệu lực phải giữ. Không dùng query Play account rỗng để xóa mọi quyền app account cross-device khi server chưa xác nhận.
- Không revoke chỉ vì danh sách sau filter pending rỗng. Lỗi/partial query không là full snapshot rỗng; một receipt fail trong nhiều receipt phải trả partial result rõ.
- Test: revoke -> reload/login/restart; promotion + no Play; Play account khác; query cũ tới sau mua mới; stale A->B; pending-only; nhiều quyền có một revoked; partial failures.
- Nghiệm thu: cả 3 probe R03 xanh; receipt mất hiệu lực không sống lại từ store; profile/store nhất quán. Dừng: không mở rộng chính sách khuyến mãi ngoài việc bảo toàn nguồn hiện có.

## Q05 — Xác thực HTTP user và Pub/Sub (R07)

- File: `S/index.ts`, mới `S/auth.ts`, `S/httpServer.ts` nếu tách để test, `S/types.ts`, `docs/billing/ENTITLEMENT_CONTRACT.md`; `ST/http-auth.test.ts` mới.
- Verify user token bằng issuer/audience/expiry/signature, derive canonical owner từ principal; body owner chỉ đối chiếu hoặc bỏ, không tin làm identity. Verify/restore/ack đều authorization. RTDN xác minh signed push token và audience/service identity được cấu hình.
- Không chỉ kiểm Authorization có tồn tại hoặc decode JWT không verify. Không tự đoán production audience; cấu hình thiếu thì fail closed.
- Test HTTP thật: missing/expired/wrong audience/wrong signature/mismatch owner; user B hỏi A; forged RTDN; trusted mocked token verifier với principal xác định cho success. Auth failure không gọi store/API.
- Nghiệm thu: probe unauth restore HTTP 200 chuyển 401/403; ack không bypass verify/owner. Không deploy trong gói.

## Q06 — Ownership store bền vững và version qua restart (R08)

- File: `S/store.ts`, mới `S/storage/` và migrations, `S/index.ts` chỉ wiring storage, `backend/billing-verifier/package.json`/lockfile nếu thêm driver; `ST/storage-integration.test.ts` mới.
- Chọn DB thực theo runtime đã xác nhận trong B03/report; không cần provision production để test local. Interface hiện hữu giữ tương thích hoặc adapter chuyển tiếp rõ. Transaction unique purchaseToken-owner, monotonic server revision/CAS; nhiều worker/process không bind trái owner.
- Test bằng DB thật local/test: restart rồi restore; hai process claim cùng token; write fail/rollback; stale concurrent update; sequence version không reset. Không dùng 2 Map làm chứng minh durability.
- Nghiệm thu: owner/version không mất qua restart, unique constraint đúng. Nếu runtime/DB mục tiêu chưa chốt, ghi quyết định cần thiết và làm interface/test contract; gate DB integration chưa được đánh PASS.
- Dừng: chưa xử lý retry worker, handoff transaction/outbox capability cho Q08/Q09.

## Q07 — Server restore không trả cache đã bị phủ định (R10)

- File: `S/verifier.ts`, `S/types.ts`, `S/store.ts` chỉ API update trạng thái cần thiết; `ST/restore-revocation.test.ts` mới.
- Expired/revoked authoritative phải commit trạng thái mới của đúng receipt, không chỉ return Rejected. Restore tổng hợp kết quả verify thay vì bỏ qua; response partial/transient/no-active nhất quán với contract client.
- Test: active cache + Play expired; refunded lifetime; API 503 giữ cache có freshness nhưng không nói mới verified; một trong nhiều receipt lỗi; owner mismatch; empty candidate list không rò owner khác.
- Nghiệm thu: probe stale active restore xanh, mới hơn thắng dữ liệu cũ; response có trạng thái để Q11 diễn giải. Dừng trước RTDN.

## Q08 — RTDN retry, dedup và snapshot hiện hành (R09)

- File: `S/rtdnHandler.ts`, `S/index.ts` chỉ HTTP status/enqueue RTDN, `S/store.ts`/storage chỉ event transaction, `ST/rtdn-recovery.test.ts` mới.
- Chỉ đánh dấu completed sau durable commit. Lỗi tạm trả retryable non-2xx hoặc ack sau enqueue bền vững; retry không bị skip bởi timestamp đã lưu trước lỗi.
- Notification là tín hiệu query trạng thái hiện hành, không lấy event type cũ đè snapshot mới. Serialize/CAS theo token; xử lý event trùng/đảo thứ tự/concurrent. One-time revoke/lifecycle phải cập nhật hoặc theo luồng authoritative tương ứng, không trả PROCESSED khi chưa làm.
- Test: Google lỗi rồi same-event thành công; process restart trước/sau commit; RTDN cũ query chậm hơn RTDN mới; unknown token sau đó bind; one-time revoke; HTTP retry code thực.
- Nghiệm thu: probe lost-retry xanh; không acknowledge event mất dữ liệu; auth Q05 không bị bypass. Dừng: không tạo Pub/Sub production.

## Q09 — Acknowledge outbox và worker thật (R08, phối hợp R05)

- File: `S/ackService.ts`, `S/verifier.ts` chỉ enqueue sau verified commit, `S/index.ts` chỉ lifecycle worker, mới `S/ackWorker.ts` và storage outbox; `ST/ack-restart.test.ts` mới.
- Chọn server là chủ thể ack có durability; persist entitlement+ack job cùng transaction trước network. Worker bounded retry/backoff, retryable/permanent distinction, startup recovery và shutdown; không chỉ có drainPendingQueue không caller.
- API ack không cho caller tùy ý ack token chưa verified/không thuộc mình. Handoff ack ownership cho Q11 để client không thực hiện hai luồng mâu thuẫn.
- Test: crash sau grant trước ack; restart job còn; timeout ack thực tế đã thành công; duplicate; permanent lỗi; worker bounded retry; không ack khi chưa lưu quyền.
- Nghiệm thu: worker thật được index đăng ký, queue tồn tại qua restart; không phải test tự gọi drain thì kết luận đã chạy nền. Dừng: không schedule cloud deployment.

## Q10 — Triển khai Google Play API adapter (phần server R01)

- File: `S/googlePlayClient.ts`, `S/types.ts`, `S/verifier.ts`/`S/rtdnHandler.ts` chỉ mapping authoritative state, package/lockfile; `ST/googlePlayTransport.test.ts` mới.
- Thay các throw Not implemented bằng SDK/HTTP adapter và ADC/service identity server-side. Đọc API hiện hành; map expiry/state/linked token/ack theo sản phẩm. Catalog alias của Android và server phải thống nhất, không xóa product legacy gây mất restore.
- Test ở transport boundary: request path/package/token/auth; product/subscription state/expiry; renewal cùng token; hold/grace/paused/revoked; 401/403/404/429/5xx/malformed response; ack idempotence. Mock server/network, không fake nguyên method getSubscription để bỏ qua adapter.
- Nghiệm thu: có executable production implementation; không có credential thì live test NOT RUN nhưng contract test transport vẫn chạy. Không lộ secret hoặc đưa service key vào Android.
- Dừng: không tự deploy hay gọi API production bằng credential chưa được cấp.

## Q11 — Android HTTP verifier, readiness và parity test/release (phần client R01/R11)

- File: `M/utils/billing/PlayPurchaseVerifier.kt`, `PurchaseVerifier.kt`, `BillingManager.kt` chỉ verifier injection/ack-result contract; `M/utils/VipPurchaseActionCoordinator.kt` chỉ preflight readiness; `M/ui/dialogs/VipUpgradeDialog.kt` và 8 locale strings chỉ unavailable text; Gradle config nếu cần endpoint build field; mới `T/PlayPurchaseVerifierHttpTest.kt`/`T/BillingReadinessTest.kt`.
- Implement HTTPS authenticated request, token provider theo session, schema parsing/owner verification/timeout/retry classification. URL có cấu hình thật; singleton không khóa nhầm default-null rồi bỏ config sau. Không dùng Class.forName JUnit để thay semantics production; inject fake verifier tường minh trong test.
- Preflight không mở thanh toán mới khi thiếu verifier config; không self-grant. Xử lý missing login token/expired credentials/owner changed rõ. Dùng Q05/Q07/Q09 contract, không ack client trước khi server durable grant nếu server là owner ack.
- Test qua local mock HTTP: request auth/schema, success/subscription/lifetime/revoked, malformed 200, 401,503, timeout, callback muộn, commit failure; production constructor config vẫn chạy cùng HTTP code dưới JVM. Chặn launch khi config thiếu.
- Nghiệm thu: control MissingBackendGate của audit được thay bằng transport success/error test có ý nghĩa; test fake không bật paid fallback production. Chưa server deployed => gate live riêng, không tuyên bố bán được.

## Q12 — Re-audit, đính chính báo cáo và release gates (R12)

- File: tests phạm vi Billing, `REPORT_VIP_BILLING_B12.md`, `REPORT_VIP_BILLING_INTEGRATION_STEP_A.md`, `docs/billing/ENTITLEMENT_CONTRACT.md`, report Q12. Không tiện tay sửa feature khi nghiệm thu đỏ; trả defect về owner gói tương ứng.
- Chạy focused regressions + toàn bộ Android unit/lint/assemble và backend tests. Đọc count XML thật. Tách test fixture đã sửa, adapter transport, DB restart, HTTP auth, probe logic, và live Play.
- Mapping đúng F01–F11 audit đầu -> Bxx implementation -> R01–R12 vòng 2 -> evidence mới. Bỏ tuyên bố SignatureVerifier/hoàn tất mọi lỗi khi không có source/test tương ứng.
- Không ghi client/server complete nếu còn Not implemented. Verify release artifact có Billing hỗ trợ, config endpoint HTTPS/auth đúng, không có fallback/test credentials.
- Device/Console/backend live gates: signed internal testing/license tester; purchase/cancel/decline/pending khi app background; restore/reinstall/two devices; app-account và Play-account khác nhau; renewal/expiry/grace/hold/refund; mất mạng/kill process lúc persist/ack; backend restart/two replicas/RTDN retry. Không có thiết bị/endpoint thì NOT RUN, không coi JVM là device proof.
- Nghiệm thu/dừng: report đủ bằng chứng, không tự upload hoặc bật bán VIP. Chỉ kết luận sẵn sàng khi tất cả gate bắt buộc đạt.

## Prompt bàn giao một gói

```text
Đọc PLAN_FIX_VIP_PLAY_BILLING_ROUND2_SMALL_MODEL_2026-09-26.md, thực hiện duy nhất gói <Qxx>.
Đọc RECHECK_VIP_PLAY_BILLING_ROUND2_2026-09-26.md và report phụ thuộc trước đó.
Kiểm tra code hiện tại; giữ nguyên thay đổi người dùng; chỉ sửa whitelist của gói.
Viết regression gọi production logic/transport/storage đúng boundary; chứng minh trước đỏ/sau xanh.
Không sửa expectation để che lỗi, không dùng local fallback để giả VERIFIED, không đánh complete
khi adapter còn Not implemented. Không triển khai hoặc upload. Thiếu credential/device thì
ghi gate cụ thể và hoàn tất phần local độc lập có thể làm.
Chạy kiểm tra của gói, xuất REPORT_VIP_BILLING_ROUND2_<Qxx>.md với file, commands,
counts, evidence, contract/API handoff, NOT RUN và rủi ro. Dừng sau gói này.
```

Giao lần lượt Q00 tới Q12, không gom toàn bộ 13 gói vào một lượt mô hình nhỏ.

Nguồn chính thức: [Google verification](https://developer.android.com/google/play/billing/security), [Pub/Sub push authentication](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions), [Push ack/retry](https://docs.cloud.google.com/pubsub/docs/push). Đọc lại API/migration thực tại thời điểm implementation.
