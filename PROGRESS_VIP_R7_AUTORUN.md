# Tiến độ VIP Round 7 — Tự động chạy X00 → X12

| Gói | Mục tiêu | Trạng thái | Ghi chú / Test counts |
|---|---|---|---|
| **X00** | Baseline, bảo toàn checkout và regression bền vững | **DONE** | Port 18 Android probes (15 FAIL / 3 PASS) vào `VipRound7RegressionTest.kt`; 9 backend probes (7 FAIL / 2 PASS) vào `round7-regression.test.ts`. Baseline giữ nguyên. |
| **X01** | CAS cho mọi linked bind/update (F01) | **DONE** | B701 PASS, B711 PASS. Áp dụng explicit absent sentinel (`expectedVersion: null, expectedAbsent: true`) và CAS retry loop trong `resolveLinkedSubscriptionToken`. 103/108 backend tests PASS. |
| **X02** | Linked identity và typed resolution outcome (F02) | **DONE** | B702 PASS, B703 PASS, B709 PASS, B710 PASS. Xác định đúng SKU khác nhau (yearly <-> monthly) từ Subscriptions V2 lineItems/catalog. Trả về `LinkedResolutionOutcome`, không nuốt lỗi upstream. 105/108 backend tests PASS. |
| **X03** | RTDN canceled pending và linked authority (F03) | **DONE** | B704 PASS. Truy vấn Google Play authority khi nhận RTDN cho token chưa bind, trích xuất linked token và làm mới quyền qua `resolveLinkedSubscriptionToken`. 106/108 backend tests PASS. |
| **X04** | Backend restore contract đầy đủ theo từng token (F06 backend) | **DONE** | B705 PASS, B706 PASS. Trả per-token PENDING trong results[], aggregate REJECTED/PARTIAL/SUCCESS chuẩn xác. 108/108 backend tests PASS. |
| **X05** | Android parser theo state, strict required fields và metadata (F09, F06 parser) | **DONE** | D01-D04 PASS, A04 PASS, A05 PASS. Strict source/owner validation, state-based expiry (canceled pending tombstone accepted), results[] parsed into typed items. R5/R6 intact. |
| **X06** | Credential thật, default model và readiness (F07 preflight) | **DONE** | C01-C03 PASS, C06 PASS. idToken default null, fail-closed isTokenExpired, isAuthReady strict check. 11/18 R7 Android probes PASS. |
| **X07** | Restore transport guard và auth recovery (F08, F07 restore) | **DONE** | C04 PASS, C05 PASS. isBackendConfigured enforced before transport, 401 mapped to AuthRequired without swallow. 13/18 R7 Android probes PASS. |
| **X08** | Owner/generation trước await và cancellation sau blocking HTTP (F04) | **DONE** | A06 PASS, A07 PASS. Capture initialContext trước await, guard synchronous commit sau destroy. 15/18 R7 Android probes PASS. |
| **X09** | Account backend refresh khi Play query lỗi (F05) | **DONE** | A01 PASS. Cho phép backend restore độc lập cho authenticated owner khi Play queries trả lỗi. 17/18 R7 Android probes PASS. |
| **X10** | Partial/pending/unresolved đi tới UI và feature gates (F06 Android) | **DONE** | A02-A05 PASS. Surface partial message và actual failedCount lên BillingManager/UI. Toàn bộ 18/18 R7 Android probes PASS! |
| **X11** | Kiểm chứng host độc lập và acceptance toàn bộ | **DONE** | 27 R7 probes PASS, R5/R6 probes PASS, 964/964 Android tests PASS, 108/108 backend tests PASS, 0 lint errors, assembleDebug PASS. |
| **X12** | External gates và bàn giao cuối một lần | **DONE** | Ghi nhận NOT_RUN / BLOCKED_EXTERNAL, hoàn thành REPORT_VIP_R7_FINAL.md & docs/billing/ROUND7_ACCEPTANCE.md. Hoàn tất toàn bộ chu trình X00–X12! |
