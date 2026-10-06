# VIP audit vòng 8 — harness và evidence, 01/10/2026

Đây là audit độc lập sau Gemini thực hiện X00–X12. Chỉ thêm tài liệu/test harness; không sửa production hoặc permanent tests. Đọc [báo cáo](</E:/DU AN AI/T-Scanner/RECHECK_VIP_FULL_ROUND8_2026-10-01.md>) và [kế hoạch tự chạy Y00–Y13](</E:/DU AN AI/T-Scanner/PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND8_AUTORUN_2026-10-01.md>) để xem tác động, đường gọi và acceptance mở rộng.

## Snapshot kết quả

| Run | Tests | FAIL | PASS | Ghi chú |
|---|---:|---:|---:|---|
| Full Android JVM | 964 | 0 | 964 | 0 errors/skips; không dùng init vòng8 |
| Full backend | 108 | 0 | 108 | Exit 0 |
| Original R7 Android/backend | 27 | 0 | 27 | 18 Android + 9 backend |
| Android R8 operations/lifecycle | 12 | 9 | 3 | A801–A812 |
| Android R8 auth/purchase | 8 | 5 | 3 | C801–C808; 0 errors |
| Backend R8 | 13 | 9 | 4 | B801–B813; exit 1 |
| **Tổng R8 mới** | **33** | **23** | **10** | **9 nhóm tái hiện; G09 xác nhận từ source** |

`lintDebug`/`assembleDebug` BUILD SUCCESSFUL, lint 0 errors/757 warnings; một số tasks UP-TO-DATE. ADB không có thiết bị. Host tests không chứng minh Play/OAuth/PubSub/Drive/PDF export/device acceptance; các gate ấy chưa chạy.

## Probe registry

| Probe | Kết quả trước sửa | Invariant / nhóm / gói |
|---|---|---|
| A801 | FAIL | ACTIVE+PENDING không full-complete; G05/Y05 |
| A802 | FAIL | All-pending không NoActivePurchases; G05/Y05 |
| A803 | FAIL | EXPIRED+transient/no-active giữ retry; G05/Y05 |
| A804 | FAIL | Cùng token lỗi local/remote đếm một receipt; G04/Y06 |
| A805 | FAIL | Fresh authoritative SUCCESS resolve lỗi verify local cũ; G04/Y06 |
| A806 | FAIL | Local pending không mất khi server snapshot rỗng; G05/Y05 |
| A807 | FAIL | USER_CANCELED sau destroy không callback listener cũ; G06/Y10 |
| A808 | FAIL | OK+PENDING sau destroy không callback listener cũ; G06/Y10 |
| A809 | PASS | Fresh success/profile/watermark đối chứng |
| A810 | PASS | Authoritative revoke/profile/watermark đối chứng |
| A811 | PASS | Noncooperative verify sau destroy không commit/callback, fix R7 còn hiệu lực |
| A812 | FAIL | Cached ACTIVE + empty remote results không fresh restored count; G04/Y06 |
| C801 | FAIL | Expired credential restore không transport; G08/Y08 |
| C802 | FAIL | Malformed credential restore không transport; G08/Y08 |
| C803 | FAIL | HTTP verify endpoint không transport; G08/Y08 |
| C804 | FAIL | Purchase A không launch dưới B sau reconnect; G07/Y07 |
| C805 | FAIL | Credential hết hạn trong products wait không launch; G07/Y07 |
| C806 | PASS | Valid HTTPS restore một transport call đối chứng |
| C807 | PASS | Same-owner/unexpired delayed purchase tới real Manager/client seam |
| C808 | PASS | Null credential đã AuthRequired/zero transport đối chứng |
| B801 | FAIL | Identical RTDN retry cùng process finish linked work; G01/Y01 |
| B802 | FAIL | Identical retry sau SQLite reopen/new handler; G01/Y01 |
| B803 | FAIL | Unknown paid ACTIVE upgrade bind new quyền; G02/Y02 |
| B804 | FAIL | Unknown paid unack upgrade enqueue durable ack; G02/Y02 |
| B805 | FAIL | One-shot bind rejection không consumed; G03/Y03 |
| B806 | FAIL | Backend restore một result per unique token; G04/Y04 |
| B807 | PASS | Unknown canceled pre-bind query503 retry đối chứng |
| B808 | PASS | Normal paid upgrade verify bind/ack đối chứng |
| B809 | FAIL | Identical retry worker thứ hai, hai physical SQLite connections; G01/Y01 |
| B810 | FAIL | Unknown canceled envelope không subscriptionId không lỗi persist; G02/Y02 |
| B811 | PASS | Known canceled cùng canonical envelope xử lý đúng |
| B812 | FAIL | Full voided refund lifetime không giữ ACTIVE; G10/Y11 |
| B813 | PASS | Normal restore cùng lifetime refund lưu REVOKED |

G09/Y09 là đường auth recovery Manager→Dialog→Home thiếu continuation, không cộng vào 23 assertion failures. Y00 port regressions; Y12 host integration; Y13 external acceptance.

## Fixture integrity

- `audit.init.gradle` chỉ thêm thư mục này vào test Kotlin sourceSet khi gọi `-I`; normal suite chưa có probes R8. Sources: `AndroidRound8AuditTest.kt`, `RootRound8AuthAuditTest.kt`, `backend-round8-probes.test.ts`.
- C804/C805/C807 dùng production purchase coordinator→real BillingManager→BillingFlowParams→FakeBillingClient. Synthetic JWT format/expiry hợp lệ, subject khớp owner; expiry giữ owner và generation. Không có giao dịch tiền thật.
- Initial SDK fixture exception do Android mock jar trả `TextUtils.isEmpty(null)=false` đã được sửa bằng `AuditTextUtils.kt` chỉ phục hồi primitive isEmpty/equals. Đây không phải app fix. Final XML có **0 errors**, C804/C805 fail assertions và C807 PASS. Không copy shim vào main/global normal tests; port 3 SDK cases bằng isolated job/sourceSet hoặc Android runtime phù hợp.
- B801/B802/B809 dùng identical event/time/token; SQLite production driver thật, lỗi Play503 có chủ đích. B805 inject `bind.success=false` một lần để kiểm tra contract; không tuyên bố đã đo race tự nhiên hoặc exploit.
- B803/B804 và B810/B811 đi qua real PubSub decode + production Google Play V2 transport/parser với fake fetch, matching owner hash, SKU catalog thực. Envelope SubscriptionNotification không có subscriptionId; type4 paid/type20 pending-canceled. Fixtures khác có subscriptionId chỉ là compatibility input cho các branches riêng, không phải schema chuẩn.
- B810 bắt exception production `ERR_INVALID_ARG_TYPE` từ SQLite rồi assert fail; không phải fixture setup lỗi. HTTP500 là suy luận từ route catch, không phải HTTP run mới. B812 dùng Base64 voided fullrefund (`productType=2`, `refundType=1`), receipt đã normal-verified ACTIVE trước authority đổi purchaseState1; B813 kiểm tra đường restore đối chứng.
- Nguồn schema/type: [Google RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference). Paid/ack authority: [Subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions), [Subscriptions V2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).
- HTTP/auth dùng synthetic tokens và fake transports; không gửi secrets hoặc query Google thật. Unit/seam suites không phải device/end-to-end coverage.

## Lệnh tái hiện

Chạy từ `E:\DU AN AI\T-Scanner` với Node hỗ trợ SQLite/strip-types và Gradle/Android SDK đã có. Lưu log/XML sau từng Gradle run vì task kế tiếp ghi đè thư mục kết quả.

```powershell
$env:GRADLE_USER_HOME='C:/Users/nguye/.gradle'
./gradlew.bat :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --offline --console=plain
node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts
./gradlew.bat -I docs/vip-round7-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidAgentAuditTest --tests com.tscanner.app.RootVipAuthAuditTest --tests com.tscanner.app.RootVipParserAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round7-20261001/backend-agent-probes.test.ts
./gradlew.bat -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.AndroidRound8AuditTest --tests com.tscanner.app.RootRound8AuthAuditTest --offline --console=plain
node --experimental-strip-types --test docs/vip-round8-20261001/backend-round8-probes.test.ts
```

Nếu Java/javapath lỗi, dùng JDK đã có `C:/Users/nguye/.jdks/openjdk-21.0.1`; không cài hoặc đổi project config để che lỗi môi trường. Final R8 runs exit nonzero là hành vi production chưa sửa được tái hiện, không phải regression do audit thay sản phẩm.

## Evidence inventory

`evidence/` giữ bản final:

- `android-baseline.log`, `android-baseline-summary.json`: full964/lint/build.
- `backend-baseline.log`, `backend-baseline.exit.txt`: full108/exit0.
- `android-original-round7.log`, `backend-original-round7.log`, `backend-original-round7.exit.txt`: original27PASS.
- `android-new-probes-final.log`, `android-operation-probes.xml`, `android-auth-probes.xml`: final20 Android/14FAIL/0errors. Không dùng initial SDK-exception run để kết luận.
- `backend-agent-probes.log`, `backend-agent-exit.txt`: final13 backend/9FAIL/exit1.
- `summary.json`: counts/findings của final harness; `production-integrity.json`: 300 files không đổi; `adb-devices.txt`: không có device.

Thư mục `build/vip-audit-round8-20261001/` giữ full baseline XML (`baseline-xml/`), originals XML (`original-round7-xml/`), git inventory trước/sau, SHA256 inventory và SQLite files thử nghiệm. Fingerprint bao gồm app Java/resources, backend src, app Gradle/proguard/manifest đã lưu ở `production-before.json`; không tuyên bố hash toàn bộ workspace. Các production files trong scope ấy không đổi trong audit.
