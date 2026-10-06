# Kế hoạch sửa VIP / Google Play Billing cho mô hình nhỏ

Ngày: 26/09/2026. Repo: `E:\DU AN AI\T-Scanner`.

Đầu vào bắt buộc: `RECHECK_VIP_PLAY_BILLING_2026-09-26.md`, mã hiện tại và bằng chứng trong `build/vip-billing-reaudit/`. Đây là **kế hoạch**, chưa thực hiện sửa production. Mục tiêu: mua/khôi phục VIP đúng giao dịch, đúng tài khoản, không tự cấp miễn phí, không cộng lặp, và đủ bằng chứng trước phát hành.

## Quy tắc thực thi chung

1. Mỗi lần giao mô hình nhỏ **một gói**. Đọc lại symbol và test trước sửa, không áp dòng máy móc. Không reset/checkout/clean hoặc ghi đè thay đổi của người dùng.
2. Mặc định làm **tuần tự**. Các gói dùng chung BillingManager/AppAuthManager/Dialog không được chạy đồng thời. Bàn giao gói trước rồi mới nhận ownership file. Backend chỉ có thể chạy song song client sau khi B03 đóng băng contract và thư mục backend riêng đã được quyết định.
3. Chỉ sửa file whitelist của gói và `REPORT_VIP_BILLING_<ID>.md`. Nếu cần file ngoài phạm vi, ghi phụ thuộc và dừng gói, không tiện tay sửa OCR/Drive/logout hoặc cấu hình ký.
4. Mỗi fix phải có regression gọi logic production thất bại trên hành vi cũ và qua với bản sửa. Không sao chép thuật toán mong muốn vào fake để chứng minh đúng. Fixture không được che lỗi async bằng mọi callback đồng bộ.
5. Không loại bỏ test thất bại để đạt màu xanh. Test cũ yêu cầu lifetime=3650 hoặc gọi grant thẳng không còn đúng contract thì sửa expectation có giải thích và test thay thế, không giữ hành vi sai.
6. Không thêm credential/service-account key vào APK/repo/log. Không triển khai backend, đổi sản phẩm Console, upload AAB hoặc thu tiền thật trong gói sửa code khi chưa có yêu cầu triển khai riêng.
7. Dừng sau mỗi gói; report gồm file đã sửa, defect ID, regression trước/sau, command/exit code/count, kết quả chưa chạy, rủi ro và đầu vào gói tiếp theo.

## Thứ tự và phụ thuộc

```text
B00 -> B01 -> B02 -> B03
                     |
                     +-> B04a -> B04b       (backend, cần chốt môi trường)
                     +-> B05 -> B06 -> B07 -> B08 -> B09 -> B10 -> B11
                                  ^
                                  +-- contract/service B04a

B12: chỉ nghiệm thu bán thật khi cả backend lẫn client và device/Console gates đạt
```

B06 có thể viết adapter theo contract và test bằng fake verifier trước khi backend deployed, nhưng **không được giả kết quả VERIFIED trong production**. Khi endpoint/auth/deployment chưa sẵn sàng, để gate phát hành chưa đạt; các gói local còn độc lập vẫn làm tiếp.

## B00 — Cố định baseline và chuyển probe thành regression

- Phạm vi: `app/src/test/java/com/tscanner/app/BillingManagerTest.kt`, mới `BillingReauditRegressionTest.kt` và `BillingTestFixtures.kt` cùng thư mục test; tài liệu/report. Không sửa main source.
- Việc làm: lưu trạng thái Git; đọc 9 probe hiện có; tách fake preferences theo tên, query list theo SUBS/INAPP; hỗ trợ callback bị trì hoãn, lỗi từng loại query và ack. Chuyển probe sang regression, giữ đối chứng purchase mới/acknowledged/cancel.
- Nghiệm thu: 9 ca lỗi vẫn tái hiện (hoặc giải thích chính xác thay đổi checkout); fixture không phát sinh false failure. Ghi rõ baseline 739 pass không bao phủ lỗi mới. Không yêu cầu toàn suite xanh ở gói ghi regression đỏ.
- Kiểm tra: focused `BillingManagerTest` và `BillingReauditRegressionTest`; lưu XML/log ngoài thư mục kết quả có thể bị lần chạy sau ghi đè.
- Điểm dừng: chỉ bàn giao harness; không sửa defect.

## B01 — Bỏ cấp trial khi thanh toán không thành công (F01)

- File: `ui/dialogs/VipUpgradeDialog.kt`; mới `utils/VipPurchaseActionCoordinator.kt` nếu cần seam; mới test `VipPurchaseActionCoordinatorTest.kt`. Prefix main Kotlin là `app/src/main/java/com/tscanner/app/`, test là `app/src/test/java/com/tscanner/app/`.
- Việc làm: lỗi/mất kết nối/cache trống chỉ thành loading/error/retry; xóa đường release gọi activateTrialVip. Không gọi hậu xử lý mua khi launch chưa thành công. Nếu giữ trial phục vụ dev, tách khỏi nút mua và chứng minh release không thể kích hoạt.
- Regression: disconnected; missing activity; launch error gọi callback rồi return false; async re-query về muộn; hai lần nhấn nhanh. Assert không mutation entitlement, không duplicate completion.
- Nghiệm thu: không có đường mua thất bại tự cấp 365/730 ngày; UI cho thử lại; không làm hỏng continuation đăng nhập đã có.
- Kiểm tra: test coordinator và các test `Vip*Continuation*`; device dialog gate còn mở đến B12.
- Dừng: chưa đổi toàn bộ giá/UI hay backend.

## B02 — Nâng Billing Library còn được hỗ trợ (F11)

- File: `app/build.gradle`, `utils/BillingManager.kt` **chỉ wrapper và API migration**, `BillingTestFixtures.kt`, `BillingManagerTest.kt`.
- Việc làm: chọn version ổn định được Google hỗ trợ tại lúc thực thi, tối thiểu 8 tại mốc kế hoạch; pin version cụ thể. Đọc migration chính thức, chỉnh callback ProductDetails/unfetched products và API pending/reconnect nếu version mới yêu cầu. Không đổi AGP/Gradle/R8 ngoài nhu cầu đã chứng minh.
- Regression: query success/partial/error, launch/ack/queryPurchases wrapper gọi đúng API; app compile với artifact thật, không chỉ fake.
- Nghiệm thu: resolved dependency và manifest metadata khớp bản hỗ trợ; focused tests + assembleDebug qua. Giữ product ID hiện có đến khi B03 đối chiếu Console, không tự đổi ID gây mất restore.
- Dừng: không upload; nếu thiếu artifact online báo rõ dependency chưa tải được, không hạ lại về 7 để giả hoàn tất.

## B03 — Chốt contract entitlement, ownership và backend (F02–F05)

- File: mới `docs/billing/ENTITLEMENT_CONTRACT.md`, `utils/billing/BillingEntitlement.kt`, `utils/billing/PurchaseVerifier.kt`; mới `BillingEntitlementContractTest.kt`. Chưa đổi persistence AppAuthManager.
- Contract tối thiểu: owner app canonical ID; product/type/token reference; nguồn quyền; trạng thái verification; thời hạn do nguồn có thẩm quyền trả về; thời điểm xác minh/freshness; version/operation/session. Lifetime biểu diễn không hết hạn khi còn sở hữu; unknown legacy local VIP không tự biến thành paid verified.
- Phân biệt pending, canceled-auto-renew-but-still-active, grace, hold, expired, revoked, transient failure. Có kế hoạch migration bản ghi `billing_vip_active` chung; lỗi mạng không được vừa xóa dữ liệu vừa cho VIP vô hạn.
- Chốt allowlist, offer/base plan, policy một receipt thuộc một tài khoản app, xử lý guest/unbound và nhiều entitlement cùng tồn tại. Không coi token đã thấy là bỏ mọi renewal: cùng token có thể có trạng thái mới.
- Kiểm tra: unit tests contract dùng expiry tuyệt đối, replay/newer snapshot, nhiều quyền và ownership conflict; đối chiếu tài liệu Play hiện hành.
- Nghiệm thu/dừng: chọn backend hiện hữu nếu có; nếu chưa có, ghi lựa chọn đề xuất và thông tin còn thiếu về runtime/hosting/auth. Không tự provision tài nguyên. B04a cần quyết định này để trở thành implementation cụ thể; vẫn có thể hoàn thành client seam/test ở local.

## B04a — Verifier server và ràng buộc receipt (F04/F05; phụ thuộc B03)

- Ownership dự kiến: **chỉ** `backend/billing-verifier/` (thư mục mới; nếu có backend thật ngoài repo thì thay whitelist bằng đường dẫn được xác nhận trong B03), cùng contract test của backend. Không sửa Android trong gói này.
- Phạm vi nhỏ: một endpoint verify/restore, authentication app user ở server, truy vấn Google Play Developer API cho product/subscription, kiểm package/product/state/expiry/owner, uniqueness token -> owner trong transaction; response theo contract B03. Map obfuscatedAccountId một chiều, không dùng email rõ.
- Regression: receipt lạ, package sai, token không hợp lệ, owner khác, replay đồng thời, API timeout/403/429; API Google được fake ở boundary nhưng policy/database thật phải được test. Không log token/ID token/credential nguyên văn.
- Nghiệm thu: giao dịch chỉ cấp cho owner hợp lệ; dữ liệu expiry/trạng thái đến từ Play; idempotent persistence. Thiếu credentials thực => unit PASS nhưng integration NOT RUN, không trả VERIFIED giả.
- Dừng: chưa làm RTDN/deployment hoặc UI. Report exact runtime/commands sau B03; không đoán endpoint production.

## B04b — Vòng đời subscription, acknowledge/retry và revoke phía server (F03/F06)

- File: chỉ module server B04a, test và cấu hình mẫu không chứa secret.
- Việc làm: xác định một chủ thể chịu trách nhiệm ack và retry bền vững; RTDN/reconciliation đọc lại trạng thái có thẩm quyền; xử lý renewal, linked token, expiry, refund/revoke. Thiết kế cache/offline freshness hữu hạn, lưu kết quả trước ack theo contract nhất quán.
- Regression: notification trùng/đảo thứ tự; restart giữa persist và ack; ack timeout; renewal cùng token; cancellation trước hết kỳ; revoked + quyền lifetime khác vẫn hợp lệ.
- Nghiệm thu: không cộng ngày theo số callback; lỗi tạm không revoke mù; xử lý có retry và quan sát được. Mọi cấu hình external, notification thực/Play integration chưa chạy phải giữ NOT RUN.
- Dừng: triển khai thật là gate riêng B12 khi được yêu cầu; không tuyên bố hoàn tất chỉ với fake Google API.

## B05 — Writer entitlement tuyệt đối và migration local (F02/F03)

- File: `utils/AppAuthManager.kt` **chỉ API/persistence VIP**, `data/model/UserProfile.kt` nếu cần, mới `utils/billing/BillingEntitlementStore.kt`; test `BillingEntitlementStoreTest.kt`, regression F02/F03. Không sửa state machine login/logout đã kiểm tra các vòng trước.
- Việc làm: thêm API apply snapshot đã xác thực, không gọi setter cộng ngày cho restore/sync. Store theo account + nguồn/token; aggregate nhiều entitlement; replay bất biến, version cũ không ghi đè mới. Lưu state và provenance nhất quán, có migration dữ liệu legacy chưa xác thực.
- Regression: receipt hai lần, bind hai lần, process restart, restore gần expiry, monthly theo expiry trả về, lifetime không 10 năm, revoke đúng nguồn, failure ghi storage không report thành công.
- Nghiệm thu: hạn không tăng khi replay; expiry/entitlement UI đúng; source legacy/manual không bị xóa hàng loạt mà thiếu policy. Test gọi writer production.
- Dừng: chưa hook login hay xử lý callback BillingManager, handoff API B06.

## B06 — Verify và session ownership ở client (F04/F05)

- File: `utils/BillingManager.kt` vùng launch/process/grant/bind; mới `utils/billing/PlayPurchaseVerifier.kt` adapter; test `BillingPurchaseVerificationTest.kt`, regressions product/late ack/bind.
- Việc làm: capture owner/session khi bắt đầu; gửi verifier; allowlist trước xử lý; obfuscated account ID khớp server; chỉ apply verified snapshot qua B05. Callback muộn không mutation tài khoản hiện tại khác. Kết quả của A lưu về A chỉ theo contract, không chuyển sang B; guest không auto-claim tùy ý.
- Regression: A->logout->B trước verify/ack; owner conflict; product lạ; receipt invalid; verification timeout; token trùng; guest login thực, không chỉ gọi helper bind thủ công. Result type thể hiện chưa verified thay cho boolean mơ hồ.
- Nghiệm thu: không grant trực tiếp khi chỉ có PURCHASED/isAcknowledged; chưa có backend thì không bật luồng paid production. Không log thông tin nhận dạng đầy đủ như code hiện tại.
- Dừng: chưa sửa aggregator query và UI event, không mở rộng sang toàn bộ xác thực Google.

## B07 — Reconcile và restore chờ đủ kết quả (F03/F06)

- File: `utils/BillingManager.kt` vùng sync/restore; mới `utils/billing/BillingReconciliation.kt` nếu tách; test `BillingReconciliationTest.kt` và regression liên quan.
- Việc làm: tập hợp query SUBS/INAPP + mọi verification/ack cần thiết; de-duplicate token; completion một lần. Kết quả có kiểu; chỉ snapshot đầy đủ và có thẩm quyền mới được revoke, không dùng rỗng do lỗi. Multi-entitlement không tùy thứ tự callback.
- Regression: một query OK/khác lỗi; cả lỗi; cả thành công rỗng; ack/verify failure/delay; nhiều purchase; callback thứ tự đảo; pending không cấp quyền; user đổi trước completion; hai restore chồng.
- Nghiệm thu: restore chỉ success khi entitlement đã apply thành công; không có false success trước ack/verify; không xóa cache khi mất mạng. Pending/cancel/error phân biệt.
- Dừng: chưa sửa event presentation hay app lifecycle.

## B08 — Kết nối và waiter không bị bỏ rơi (F07)

- File: `utils/BillingManager.kt` vùng connection/retry; mới `utils/billing/BillingConnectionCoordinator.kt` nếu cần; test `BillingConnectionCoordinatorTest.kt`.
- Việc làm: coalesce concurrent setup, queue waiters hoặc await shared connection task; completion/timeout/cancel đúng một lần; retries hữu hạn và stale callback guard. Tôn trọng API reconnect bản B02, không chạy hai cơ chế reconnect mâu thuẫn.
- Regression: restore khi CONNECTING rồi success/failure; hai waiter; disconnect giữa query; setup failure lặp; callback setup cũ tới muộn; scope teardown không hồi sinh connection.
- Nghiệm thu: probe connecting xanh và không treo im lặng; timeout test dùng virtual/fake scheduler, không ngủ chờ dài.
- Dừng: chỉ kết nối, không tự thêm retry mua hàng gây mở lại thanh toán ngoài ý muốn.

## B09 — Tách state sync và sự kiện UI mua/restore (F08 + F01 async)

- File: `utils/BillingManager.kt` event API; `ui/dialogs/VipUpgradeDialog.kt`; `ui/more/MoreFragment.kt` **chỉ subscriber**; coordinator B01; test `BillingOperationEventsTest.kt`.
- Việc làm: trả launched/loading/failure bất đồng bộ rõ ràng; operation ID/origin PURCHASE/RESTORE/RECONCILE; cập nhật entitlement state không đồng nghĩa thông báo mua mới. Restore completion duy nhất điều phối onPostUpgradeFlow. UI callback trên main thread và còn host/session hợp lệ.
- Regression: silent sync khi dialog mở; restore nhiều receipt; global listener + completion; dialog dismissed/rotation trước re-query; callback sau logout; single click double event.
- Nghiệm thu: sync nền không toast mua thành công/xin Drive/dismiss; mỗi purchase/restore do user yêu cầu chỉ hậu xử lý tối đa một lần; không giữ Activity quá lifecycle.
- Dừng: chưa chỉnh giá/localization để giảm scope.

## B10 — Hook foreground và đăng nhập thật (F09)

- File: `TScannerApplication.kt`, `utils/AppAuthManager.kt` **chỉ hook sau login đã commit**, `ui/more/MoreFragment.kt` **chỉ bỏ sync trùng**, mới test `BillingLifecycleIntegrationTest.kt`. Nếu chọn host lifecycle khác, B03/B09 phải bàn giao lý do; không rải query vào mọi màn hình.
- Việc làm: sync sau init auth và khi app foreground; sync sau login đúng session; không phụ thuộc More/Dialog. Dùng B06/B07, không cắm hàm bind cộng ngày cũ. App nhiều Activity phải coalesce để không query liên tục.
- Regression: cold start Home; guest query xong trước login; đổi tài khoản; background payment hoàn tất rồi foreground; tái tạo More không cộng hạn; callback sau logout không ghi user mới. Test phải đi qua entry point login/lifecycle thật hoặc seam nằm trực tiếp ở entry point đó.
- Nghiệm thu: login thật và foreground đều được cover, còn logout isolation/VIP continuation cũ vẫn pass.
- Dừng: device pending/reinstall là B12, không tuyên bố hoàn tất từ JVM.

## B11 — Giá, offer và ngôn ngữ mua hàng (F10)

- File: `ui/dialogs/VipUpgradeDialog.kt`, `res/layout/dialog_vip_upgrade.xml`, `utils/BillingManager.kt` **chỉ product/offer presentation**, `data/model/VipTier.kt` nếu loại bỏ giá giả; 8 file `res/values{,-de,-es,-fr,-in,-ja,-pt,-vi}/strings.xml`; mới `BillingOfferPresentationTest.kt`.
- Việc làm: observe product state có lifecycle; chọn base plan/offer theo contract, hiển thị đúng phases và chu kỳ/recurring price; bỏ chuỗi trial thử nghiệm cho mua thật, bỏ giá phụ tháng cố định khi không phù hợp; error/loading không đưa giá fallback thành giá thật.
- Regression: cache trống rồi product đến; không available; trial 0 -> recurring; tiền tệ không VND; nhiều offer; product hiện tại không có offer eligible; locale placeholders.
- Nghiệm thu: giá/offer UI khớp offer token launch; nhãn không hứa dùng thử miễn phí khi không có offer; verify layout trên thiết bị ở B12.
- Dừng: không tạo sản phẩm/giá trên Console trong gói UI.

## B12 — Nghiệm thu tích hợp và sửa báo cáo

- File: regression/integration tests thuộc phạm vi Billing, `REPORT_VIP_BILLING_INTEGRATION_STEP_A.md`, report B12 và docs/billing. Không sửa feature tùy tiện; defect mới trả lại đúng gói.
- Chạy focused regressions, toàn bộ unit tests, lintDebug, assembleDebug; kiểm bản release phù hợp khi chuẩn bị phát hành. Ghi count thật, không hardcode 739 sau khi thêm test. Diff cho thấy không sửa ngoài whitelist.
- Soát artifact release có Billing version hỗ trợ và permission/metadata; không upload tự động. Kiểm riêng trạng thái Console, product IDs/base plans/offers đã active, package/signing/internal testing/license testers.
- Matrix thiết bị/Play bắt buộc: mua thành công; cancel; decline; pending thành purchased khi background; restore/reinstall/thiết bị khác; renewal nhanh license tester; tắt auto-renew còn hạn; expiry/hold/grace/refund/revoke; offline/Play unavailable; đổi app account trong lúc mua; đổi Play account; xoay màn hình/kill process trong ack/verify; cold start Home.
- Kiểm guest/account policy và legacy VIP sau nâng cấp; receipt không phục hồi sang tài khoản khác; source quyền khác vẫn hợp lệ khi revoke billing.
- Đính chính Step A: acknowledge không đồng nghĩa xác thực đầy đủ; không gọi v7 mới nhất; không ghi 100% branch/security/device coverage từ 10 tests.
- Nghiệm thu: tất cả regression lỗi cũ qua; backend integration, release artifact và matrix Play có bằng chứng. Gate thiếu thiết bị/endpoint/Console ghi **NOT RUN**, không viết "đã sẵn sàng bán VIP".
- Dừng: bàn giao kết quả, không tự phát hành.

## Prompt giao việc cho mô hình nhỏ

Mỗi prompt bắt đầu bằng tên kế hoạch và task ID. Thay `<ID>` bằng đúng một gói; `<PREVIOUS>` bằng báo cáo gói phụ thuộc thực tế:

```text
Đọc PLAN_FIX_VIP_PLAY_BILLING_SMALL_MODEL_2026-09-26.md, thực hiện duy nhất gói <ID>.
Đọc RECHECK_VIP_PLAY_BILLING_2026-09-26.md và báo cáo bàn giao <PREVIOUS>.
Xác minh lại mã hiện tại, giữ nguyên thay đổi người dùng, chỉ sửa whitelist của <ID>.
Viết/chuyển regression gọi production logic; chứng minh lỗi trước sửa và hành vi sau sửa.
Không xóa test để đạt xanh, không mở rộng phạm vi, không triển khai hay upload.
Nếu thiếu contract/backend/credential/device, ghi chính xác gate còn thiếu và tiếp tục phần
độc lập có thể làm; không giả thành công hoặc VERIFIED trong production.
Chạy kiểm tra của gói, xuất REPORT_VIP_BILLING_<ID>.md với commands/count/evidence,
file đã sửa, phần chưa chạy và handoff API. Dừng sau gói này.
```

Danh sách ID giao độc lập: `B00`, `B01`, `B02`, `B03`, `B04a`, `B04b`, `B05`, `B06`, `B07`, `B08`, `B09`, `B10`, `B11`, `B12`. Không giao toàn bộ danh sách cho một mô hình nhỏ trong một lượt.

## Nguồn kỹ thuật cần đọc lại khi thực thi

- [Thời hạn phiên bản Billing](https://developer.android.com/google/play/billing/deprecation-faq).
- [Xác thực và chống xử lý receipt sai](https://developer.android.com/google/play/billing/security).
- [Tích hợp Billing, xử lý pending và query khi foreground](https://developer.android.com/google/play/billing/integrate).

Tài liệu có thể thay đổi; chọn version/contract theo tài liệu tại thời điểm thực thi. Báo cáo audit đã kiểm nguồn ngày 26/09/2026.
