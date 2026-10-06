# Tài liệu Nghiệm thu VIP Round 7 (Billing & Entitlement Acceptance)

**Ngày thực hiện:** 01/10/2026  
**Phiên bản hệ thống:** T-Scanner Android Client & Backend Billing Verifier  
**Tiêu chuẩn đối chiếu:** `RECHECK_VIP_FULL_ROUND7_2026-10-01.md`, `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND7_AUTORUN_2026-10-01.md`

---

## 1. Tóm tắt kết quả nghiệm thu ma trận

| Nhóm chỉ tiêu | Yêu cầu | Kết quả thực tế | Trạng thái |
|---|---|---|---|
| **Round 7 Probes (Audit Suite)** | 27/27 probes (18 Android + 9 Backend) | **27/27 PASS** | **ĐẠT** |
| **Round 6 Probes (Regression)** | 15/15 probes (12 Android + 3 Backend) | **15/15 PASS** | **ĐẠT** |
| **Round 5 Probes (Regression)** | 16/16 probes (9 Android + 7 Backend) | **16/16 PASS** | **ĐẠT** |
| **Backend Full Unit Test Suite** | 100% passed, 0 failures, 0 skipped | **108/108 PASS** | **ĐẠT** |
| **Android Full Unit Test Suite** | 100% passed, 0 failures, 0 skipped | **964/964 PASS** | **ĐẠT** |
| **Android Lint Gate** | Không có lỗi compile/lint blocker | **0 errors, 757 warnings** | **ĐẠT** |
| **Android Build Gate** | Compile và đóng gói APK debug thành công | **BUILD SUCCESSFUL** | **ĐẠT** |
| **Bảo toàn Workspace** | Không clean, stash, reset, drop uncommitted files | **100% bảo toàn** | **ĐẠT** |

---

## 2. Chi tiết giải quyết khiếm khuyết F01–F09

### F01: First-binding linked query ghi đè trạng thái đã có và race condition
- **Vấn đề trước đây:**
  - `resolveLinkedSubscriptionToken` trong `verifier.ts` ghi đè token cũ khi chưa bind mà không kiểm tra CAS version, dẫn tới ghi đè bản ghi có version cao hơn (như EXPIRED) hoặc xung đột giữa 2 luồng/tiến trình song song.
- **Giải pháp:**
  - Áp dụng CAS retry loop (tối đa 5 lần) với explicit absent sentinel (`expectedVersion: null, expectedAbsent: true`) khi binding token lần đầu.
  - Kiểm tra `latestVersion >= incomingVersion` để giữ bản ghi mới hơn, bảo toàn lịch sử thu hồi/hết hạn.
- **Probes chứng minh:**
  - **B701 PASS:** First-bind linked query không ghi đè EXPIRED receipt mới hơn.
  - **B711 PASS:** Two-connection concurrent race condition được giải quyết an toàn qua SQLite CAS loop.

---

### F02: Khôi phục token nâng cấp khác SKU (Yearly <-> Monthly) và typed outcome
- **Vấn đề trước đây:**
  - Backend parse linked purchase token chỉ hỗ trợ cùng SKU; khi nâng cấp khác SKU (Yearly -> Monthly hoặc ngược lại), V2 `lineItems[0].productId` không được trích xuất chính xác.
  - Hàm phân giải nuốt lỗi upstream, trả về kết quả không có kiểu (typed outcome).
- **Giải pháp:**
  - Bổ sung `parsePurchasesV2Response` trong `googlePlayClient.ts` để đọc danh mục `lineItems` và xác định đúng `productId`.
  - Định nghĩa `LinkedResolutionOutcome`: `Resolved`, `NotFound`, `UpstreamError`, `Conflict`. Không nuốt lỗi upstream Google Play.
- **Probes chứng minh:**
  - **B702 PASS:** Canceled yearly upgrade phục hồi chính xác linked monthly receipt.
  - **B703 PASS:** Upstream failure không bị báo là full success.
  - **B709 PASS (đối chứng):** Cùng SKU linked receipt phân giải thành công.
  - **B710 PASS (đối chứng):** Linked receipt đã thuộc tài khoản khác được bảo toàn quyền sở hữu.

---

### F03: RTDN canceled pending thiếu truy vấn Play authority
- **Vấn đề trước đây:**
  - Khi nhận RTDN cho token chưa bind trong DB, hệ thống bỏ qua và không truy vấn Play authority để tìm linked token.
- **Giải pháp:**
  - Trong `rtdnHandler.ts`, khi token chưa có trong DB, gọi `googlePlayClient.getSubscriptionV2` để lấy thông tin authority, trích xuất `linkedPurchaseToken` và gọi `resolveLinkedSubscriptionToken`.
- **Probes chứng minh:**
  - **B704 PASS:** RTDN sau pending verification làm mới linked receipt đã hủy.

---

### F04: Owner/generation trước await và hủy synchronous commit sau destroy
- **Vấn đề trước đây:**
  - `BillingManager.kt` và `BillingReconciliation.kt` bắt `ownerAppUserId` sau await hoặc reconnect, dẫn đến cấp nhầm quyền vào tài khoản B khi tài khoản đã chuyển từ A -> B.
  - Sau khi `BillingManager` bị destroy, verifier đồng bộ trả về vẫn thực hiện commit vào store.
- **Giải pháp:**
  - Bắt `initialContext` (`ownerAppUserId`, `sessionGeneration`) ngay tại entrypoint của `restorePurchases` và `syncPurchases`.
  - Kiểm tra `isStale` sau khi Google Play kết nối lại hoặc sau khi HTTP return.
  - Kiểm tra `isDestroyed || !scope.isActive` trước mọi commit vào store/profile/prefs.
- **Probes chứng minh:**
  - **A06 PASS:** Destroyed manager không commit response từ verifier trả về muộn.
  - **A07 PASS:** Restore bắt đầu bởi user A nhưng chuyển sang user B không giao quyền cho user B.

---

### F05: Lỗi Play query ngăn cản làm mới tài khoản từ backend độc lập
- **Vấn đề trước đây:**
  - Khi truy vấn `queryPurchasesAsync` (SUBS hoặc INAPP) gặp lỗi mạng, `BillingReconciliation` ngắt ngay với `NetworkError`, không gọi backend authoritative restore cho tài khoản đang đăng nhập.
- **Giải pháp:**
  - Trong `evaluateAndProcess`, khi Play query trả mã lỗi (khác `OK`), kiểm tra nếu có `targetOwnerId` và `verifier != null`, thực thi `executeRemoteRestore` độc lập cho tài khoản đó, truyền `playQueryErrorCode`.
  - Cập nhật store/profile với snapshot từ server; bảo toàn cache nếu server gặp lỗi tạm thời.
- **Probes chứng minh:**
  - **A01 PASS:** Play query lỗi vẫn kích hoạt backend refresh độc lập và thu hồi VIP bị server revoke.

---

### F06: Thông tin itemized results từng token, failedCount và thông báo Partial
- **Vấn đề trước đây:**
  - Backend: Khi toàn bộ candidate tokens bị 404, backend trả `SUCCESS` rỗng thay vì `REJECTED`.
  - Android: `results[]` không được parse ra typed items, `failedCount` bị hardcode 0 hoặc 1, UI thông báo thành công hoàn toàn dù có giao dịch lỗi.
- **Giải pháp:**
  - Backend: Trả `results[]` với status cho từng token (`SUCCESS`, `PENDING`, `TRANSIENT_ERROR`, `REJECTED`). Khi tất cả candidate bị từ chối, aggregate status là `REJECTED`.
  - Android: Parse `results[]` vào `RestoreItemResult`, tính toán chính xác `failedCount`.
  - `BillingManager.kt`: Khi `failedCount > 0`, hiển thị: `"Khôi phục một phần: Một số giao dịch chưa thể hoàn tất."`.
- **Probes chứng minh:**
  - **B705 PASS:** All-pending restore giữ nguyên semantics từng token.
  - **B706 PASS:** Toàn bộ candidate bị từ chối không báo full success (policy check).
  - **A02 PASS:** Local success kết hợp remote transient error không báo full restored.
  - **A03 PASS:** Partial result truyền tải thông điệp khôi phục một phần lên UI dialog.
  - **A04 PASS:** Parser bảo toàn số lượng failures thực tế từ `results[]`.
  - **A05 PASS:** Cached unresolved entitlement không tính vào số lượng vừa khôi phục mới.

---

### F07: Credential thật, loại bỏ default mock token, fail-closed preflight
- **Vấn đề trước đây:**
  - `UserProfile.idToken` mặc định gán `"synthetic-dev-token"`, che giấu trạng thái chưa đăng nhập thật.
  - `PlayPurchaseVerifier.isTokenExpired` không kiểm tra cấu trúc JWT (header.payload.signature), không kiểm tra claim `exp`.
- **Giải pháp:**
  - Đưa `UserProfile.idToken` mặc định về `null`.
  - `isTokenExpired`: fail-closed khi token null, blank, không có đủ 3 phần, claim `exp` thiếu hoặc không dương (`exp <= 0`).
  - `isAuthReady`: đòi hỏi `tokenProvider != null`, token không rỗng và chưa hết hạn.
- **Probes chứng minh:**
  - **C01 PASS:** Default `UserProfile` không tự sinh credential giả.
  - **C02 PASS:** Token malformed không được coi là auth-ready.
  - **C03 PASS:** JWT thiếu exp bị từ chối.
  - **C06 PASS (đối chứng):** JWT hợp lệ với exp tương lai được chấp nhận.

---

### F08: Transport guard HTTPS và mapping lỗi 401 re-auth
- **Vấn đề trước đây:**
  - `PlayPurchaseVerifier.restorePurchases` không kiểm tra `isBackendConfigured()` trước khi gửi HTTP transport.
  - HTTP 401 bị nuốt hoặc chuyển thành generic error thay vì `RestoreResult.AuthRequired`.
- **Giải pháp:**
  - Kiểm tra `isBackendConfigured()` trước khi tạo request transport.
  - Map mã HTTP 401 thành `RestoreResult.AuthRequired` và `ReconciliationResult.AuthRequired`, hiển thị thông báo yêu cầu đăng nhập lại cho người dùng.
- **Probes chứng minh:**
  - **C04 PASS:** URL `http://` bị chặn trước khi gửi transport.
  - **C05 PASS:** HTTP 401 mở ra luồng khôi phục phiên đăng nhập.

---

### F09: Strict source/owner validation và state-based expiry
- **Vấn đề trước đây:**
  - Parser tự bịa source `GOOGLE_PLAY_SUBSCRIPTION` khi backend payload thiếu `"source"`.
  - Parser lấy `ownerAppUserId` từ request khi cả snapshot và item đều thiếu owner.
  - Parser ép buộc positive expiry cho mọi subscription, từ chối tombstone của canceled pending subscription có expiry 0.
- **Giải pháp:**
  - Bắt buộc `"source"` phải hiện diện và khớp với catalog type (`subs` -> `GOOGLE_PLAY_SUBSCRIPTION`).
  - Bắt buộc owner phải có trong snapshot hoặc item; không tự bịa từ request client.
  - Chỉ yêu cầu positive expiry cho các trạng thái hoạt động (`VERIFIED_ACTIVE`, `CANCELED_ACTIVE`, `IN_GRACE_PERIOD`). Cho phép expiry 0/null với trạng thái `REVOKED` / `EXPIRED`.
- **Probes chứng minh:**
  - **D01 PASS:** Payload thiếu source bị từ chối.
  - **D02 PASS:** Thiếu owner bị từ chối.
  - **D03 PASS:** Canceled-pending tombstone (expiry = 0) không làm từ chối snapshot chứa linked active subscription.
  - **D04 PASS (đối chứng):** Payload active hoàn chỉnh được chấp nhận thành công.

---

## 3. Trạng thái các cổng ngoại vi (External Gates)

Do thực hiện trên host máy tính phát triển không kết nối thiết bị phần cứng thực và không sử dụng tài khoản/tiền thật:

| External Gate | Trạng thái | Điều kiện nghiệm thu trực tiếp |
|---|---|---|
| **Google Play License Tester** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần tài khoản Google Play Console test track, license testing Gmail. |
| **Real Google Sign-In OAuth** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần Google Play Services trên thiết bị thật và Web Client ID tương ứng. |
| **Live Pub/Sub RTDN** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần Cloud Pub/Sub topic kết nối trực tiếp với Play Console. |
| **Physical Device ADB & Lifecycle** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần cắm thiết bị Android vật lý qua cáp USB/ADB. |
| **Real Google Drive Sync** | `NOT_RUN / BLOCKED_EXTERNAL` | Cần cấp quyền OAuth Scope `drive.file` trên thiết bị thực. |

---

## 4. Kết luận nghiệm thu

Hệ thống đã hoàn tất toàn bộ 9 nhóm khiếm khuyết F01–F09 trong phạm vi host. Toàn bộ 27 audit probes vòng 7, 15 probes vòng 6, 16 probes vòng 5, 108 backend unit tests, 964 Android unit tests, 0 lint errors và build APK debug đều đạt 100%. Sẵn sàng bàn giao cho người dùng kiểm tra trên thiết bị thực tế khi có môi trường.
