# Round 3 VIP & Billing Architectural Contract (26/09/2026)

Tài liệu này xác lập hợp đồng kiến trúc (architectural contract) có thẩm quyền giữa Android Client (`:app`) và Backend Verifier (`backend/billing-verifier`), giải quyết dứt điểm các phát hiện F01–F12 từ `RECHECK_VIP_FULL_ROUND3_2026-09-26.md`.

---

## 1. Các Nguyên Tắc & Hợp Đồng Cốt Lõi

### 1.1. Token Identity Ổn Định (F04)
- **Quy tắc:** Mỗi `purchaseToken` có duy nhất một `id` chuẩn (canonical identity: `GOOGLE_PLAY_SUBSCRIPTION_${purchaseToken}` hoặc `GOOGLE_PLAY_INAPP_${purchaseToken}`) xuyên suốt toàn bộ vòng đời.
- **Bất biến:** Khi chuyển đổi trạng thái từ `VERIFIED_ACTIVE` sang `EXPIRED`, `CANCELED_ACTIVE`, hoặc `REVOKED`, backend và client tuyệt đối không đổi `id` (không đổi thành bare `purchaseToken` hoặc uuid ngẫu nhiên).
- **Hệ quả:** Snapshot client merge theo `id` hoặc `purchaseToken` sẽ ghi đè chính xác bản ghi cũ, triệt tiêu lỗi giữ song song 1 bản ghi active cũ và 1 bản ghi expired mới.

### 1.2. Thẩm Quyền Versioning & Phục Hồi Thuộc Server (F03, F08)
- **Quy tắc:** Chỉ có backend verifier (sau khi cam kết vào storage) mới được quyền cấp phát và tăng `snapshotVersion`.
- **Bất biến phía Client:** Android client tuyệt đối không tự ý tăng `snapshotVersion` (không tự mint version).
- **Empty Play Catalog vs Server Revocation:** Khi danh mục Google Play trên thiết bị rỗng (`queryPurchasesAsync` trả về empty), client chỉ ghi nhận thiết bị không có receipt local. Client không được suy diễn rằng toàn bộ quyền server của `ownerAppUserId` bị thu hồi. Server restore (`/api/v1/billing/restore`) là nguồn thẩm quyền xác định quyền của app account.

### 1.3. Snapshot Có Chủ Sở Hữu và Thế Hệ Phiên (Ownership & Session Generation) (F02, F10)
- **Quy tắc:** Mọi entitlement snapshot đều gắn chặt với `ownerAppUserId` và thế hệ phiên xác thực (`sessionGeneration`).
- **Bất biến Worker:** Các background worker (như `GoogleDriveBackupWorker`) phải kiểm tra lại quyền VIP, thế hệ phiên và trạng thái hủy (`coroutineContext.ensureActive()`) ngay trước các thao tác gửi request mạng (remote mutation) và trước khi commit metadata cục bộ.

### 1.4. Kết Quả UI Dựa Trên Trạng Thái Đã Cam Kết Bền Vững (F07)
- **Quy tắc:** Kết quả trả về cho UI (`onComplete: (Boolean) -> Unit`) phải phản ánh trạng thái thực sự sau khi commit vào storage bền vững và profile projection:
  - Nếu kết quả là `isCurrentlyActive() == true` (bao gồm `VERIFIED_ACTIVE`, `CANCELED_ACTIVE`, `IN_GRACE_PERIOD`), trả về `success = true`.
  - Nếu bản ghi active bị bỏ qua do stale (ví dụ server snapshot trên máy là `REVOKED v9`, nhận về `ACTIVE v1`), kết quả trả về là `success = false` (hoặc `StaleIgnored`).
  - Giao dịch phải là một lần duy nhất (single durable commit); không commit store 2 lần phân tán gây phân rã trạng thái giữa Store và AppAuthManager.

### 1.5. Xử Lý Authoritative Rejection & Lưu Trữ Tombstone (F05)
- **Quy tắc:** Khi backend phản hồi `REJECTED` với các lý do có thẩm quyền (authoritative reasons: `PURCHASE_REVOKED`, `PURCHASE_EXPIRED`), payload phải mang theo authoritative entitlement snapshot (tombstone).
- **Bất biến Client:** Client chuyển tiếp tombstone này tới store để thu hồi quyền active đã lưu trong cache. Lỗi mạng (transient errors), conflict quyền sở hữu chưa xác định, hoặc lỗi JWT tuyệt đối không biến thành lệnh thu hồi local.

### 1.6. Nguyên Tử Giữa Cấp Quyền & Outbox Acknowledgement (F09, F11)
- **Quy tắc:** Lưu trữ entitlement và ghi nhận công việc acknowledge vào hàng đợi outbox phải diễn ra trong cùng một transaction cơ sở dữ liệu bền vững.
- **Bao phủ trạng thái:** Mọi giao dịch đã thanh toán cần acknowledge (kể cả gói `CANCELED_ACTIVE` còn hạn nhưng `acknowledgementState == 0`) đều phải được đưa vào outbox.
- **Chịu lỗi & Bền vững:** Production bắt buộc dùng đường dẫn lưu trữ bền vững (SQLite file/Postgres). Nghiêm cấm ngầm định fallback sang `:memory:` khi chạy production.

### 1.7. Xác Thực Google Identity & Chữ Ký Nghiêm Ngặt (F01, F02, F06)
- **Quy tắc:** Production verifier bắt buộc kiểm tra Google ID Token qua RSA JWKS key rotation, xác minh đầy đủ `iss`, `aud`, `exp`, chữ ký, và email_verified.
- **Cấm Dev Secret:** Loại bỏ dev secret / HMAC fallback khỏi nhánh production Google Auth.
- **Schema Strict:** Toàn bộ payload Google Play API và backend API phải được validate schema nghiêm ngặt: cấm HTTP 200 `{}` tự động suy diễn thành active lifetime; cấm unknown state trở thành VERIFIED_ACTIVE; subscription bắt buộc có expiry time hữu hạn và hợp lệ.

---

## 2. Ánh Xạ Lỗi F01–F12 và Danh Sách Permanent Regression Tests

| Mã | Mức | Mô tả | Test Probe Android | Test Probe Backend | Ghi chú bằng chứng |
|---|:---:|---|---|---|---|
| **F01** | P1 | Auth production dùng HMAC/dev secret, bỏ qua Google identity | — | `auth must reject development-key JWT without required identity claims`<br>`auth must reject unsupported algorithm even when HMAC matches` | Backend probes |
| **F02** | P1 | Adapter Android chưa nối vào bootstrap; readiness chỉ kiểm URL | `httpEndpointMustNotBeReady` | — | Code verification: thiếu wiring trong Application, config HTTPS strict |
| **F03** | P1 | Device catalog rỗng tự thu hồi quyền server và mint version | `emptyDeviceCatalogMustPreserveServerEntitlement` | — | Android probe |
| **F04** | P1 | Đổi ID entitlement khi hết hạn, giữ song song quyền cũ | `expiredServerIdMustReplaceActiveToken` | `entitlement ID must remain stable after authoritative expiry` | Android + Backend probes |
| **F05** | P1 | Android bỏ thông tin thu hồi authoritative trong Rejection | `rejectionMustPersistAuthoritativeRevocation` | — | Android probe + Backend tombstone schema |
| **F06** | P1 | Parse thiếu/sai dữ liệu theo hướng tự cấp quyền VIP | `malformedStateMustNotBecomeActive` | `empty Play JSON must not grant lifetime VIP` | Android + Backend probes |
| **F07** | P1/P2 | Kết quả mua/restore không phản ánh đúng state đã commit | `canceledPaidPeriodMustRestoreSuccessfully`<br>`staleActiveSnapshotMustNotReportPurchaseSuccess`<br>`secondStoreCommitFailureMustNotReportSuccess` | — | 3 Android probes |
| **F08** | P1 | Race RTDN/Verify ghi đè trạng thái mới bằng dữ liệu cũ | — | `out-of-order RTDN completion must not override newer event`<br>`late expired verification cannot overwrite newer renewal` | 2 Backend concurrency probes |
| **F09** | P1 | Acknowledge bỏ sót CANCELED_ACTIVE và tách rời transaction | — | `canceled active unacknowledged receipt must enter outbox` | Backend outbox probe + crash gap code evidence |
| **F10** | P2 | GoogleDriveBackupWorker không recheck VIP sau khi chờ token | `driveWorkerMustRecheckVipAfterTokenWait` | — | Android worker probe |
| **F11** | P1 | Backend mặc định `:memory:` khi thiếu config storage | — | — | Code evidence: store.ts mặc định memory, thiếu storage readiness gate |
| **F12** | P2 | Báo cáo hoàn tất vượt bằng chứng kiểm toán | — | — | Rectification qua toàn bộ suite test và báo cáo M11 |
