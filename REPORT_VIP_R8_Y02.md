# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y02 (Unknown Linked RTDN & Google Schema — G02)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y03  
**Lỗi giải quyết:** G02 (P1: Unknown linked RTDN mất new entitlement/ack hoặc lỗi với schema Google)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  - **B803 / B804:** Khi nhận RTDN cho purchase mới nâng cấp (yearly ACTIVE chưa verify từ app) liên kết với purchase cũ (monthly đã verify), nhánh unknown trong `rtdnHandler.ts` chỉ refresh token cũ mà không xử lý bind/grant cho token mới và không enqueue acknowledgement job vào outbox. Handler mặc định trả `PROCESSED / newState: 'REVOKED'`. Hệ quả: User mất quyền VIP dù đã thanh toán và token mới không được acknowledge kịp thời.
  - **B810:** Schema `SubscriptionNotification` chuẩn của Google PubSub không chứa trường `subscriptionId`. Khi nhận envelope này, handler gán `productId = subscriptionId` (undefined), dẫn đến `sqliteDriver.ts` ném lỗi `TypeError: Provided value cannot be bound to SQLite parameter 4` (`ERR_INVALID_ARG_TYPE`).
- **Giải pháp (Fix):**
  - Cập nhật kiểu `DeveloperNotificationPayload`: Cho phép `subscriptionId` là optional theo đúng đặc tả của Google RTDN reference.
  - Tự động phân giải danh tính sản phẩm (`resolvedNewProductId`): Lấy từ `playResult.lineItemProductId` (được phân giải authoritatively từ Google Subscriptions V2 `lineItems`), fallback về `subscriptionId` hoặc `linkedRecord.productId`. Kiểm tra `ALLOWED_SUBSCRIPTION_IDS` chặt chẽ.
  - Tái sử dụng đầy đủ ma trận ánh xạ trạng thái entitlement theo Play authority (SUBSCRIPTION_STATE_ACTIVE -> VERIFIED_ACTIVE/CANCELED_ACTIVE, PENDING -> PENDING_PAYMENT, EXPIRED, REVOKED,...).
  - Tự động enqueue acknowledgement job vào durable outbox (`store.enqueueAckRetry`) nếu purchase mới là ACTIVE và chưa được acknowledge (`playResult.acknowledgementState === 0`).
  - Kiểm tra `obfuscatedExternalAccountId` hash khớp với owner của linked record trước khi cho phép bind token mới.

---

## 2. File thay đổi

- `backend/billing-verifier/src/rtdnHandler.ts`:
  - Import `ALLOWED_SUBSCRIPTION_IDS`.
  - Cập nhật payload schema: `subscriptionId?: string`.
  - Triển khai đầy đủ luồng xử lý unknown token có linked purchase: phân giải SKU từ `lineItemProductId`, xác thực hash, ánh xạ trạng thái authority, bind quyền mới và enqueue ack outbox.
  - Hỗ trợ gọi `getSubscription` an toàn khi `subscriptionId` vắng mặt.
- `backend/billing-verifier/test/round8-regression.test.ts`:
  - Thêm 2 variant tests:
    - `B804-variant unknown token account hash mismatch must reject binding to foreign user`
    - `B804-variant unknown token with disallowed product ID must not bind`

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Bộ kiểm tra / Test Case | Kết quả trước sửa | Kết quả sau sửa | Ghi chú |
|---|---|---|---|
| `B803`: Unknown paid yearly upgrade bind ACTIVE | **FAIL** (claim REVOKED, missing new entitlement) | **PASS** | New yearly lưu `VERIFIED_ACTIVE`, old monthly `EXPIRED` |
| `B804`: Unknown paid unack upgrade enqueue ack | **FAIL** (durable outbox empty) | **PASS** | Outbox có job cho `new-paid-upgrade` với `tscanner_vip_yearly` |
| `B810`: Google payload thiếu subscriptionId (type 20) | **FAIL** (`ERR_INVALID_ARG_TYPE` param 4) | **PASS** | Lưu tombstone với productId chuẩn xác |
| `B808` (Control): Normal authenticated verify | **PASS** | **PASS** | Không ảnh hưởng luồng thường |
| `B811` (Control): Known token cùng Google envelope | **PASS** | **PASS** | Không ảnh hưởng known token |
| `B804-variant`: Hash mismatch on unknown token | N/A | **PASS** | Bị từ chối với status ERROR |
| `B804-variant`: Disallowed product ID | N/A | **PASS** | Bị từ chối, không bind vào DB |
| Full backend baseline (14 suites, 108 tests) | 108 PASS | **108 PASS** | 100% PASS |
| Original Round 7 backend probes (9 tests) | 9 PASS | **9 PASS** | 100% PASS |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y03** (Check bind/outbox result trong unknown RTDN — G03).
