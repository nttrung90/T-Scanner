# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y01 (Durable Linked RTDN Completion — G01)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y02  
**Lỗi giải quyết:** G01 (P1: RTDN linked lỗi rồi gửi lại cùng event bị consumed)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  - Khi RTDN notification nhận một purchase có `linkedPurchaseToken` (ví dụ: upgrade/downgrade hoặc canceled upgrade), handler gọi `bindOrUpdate` cho token chính trước, qua đó ghi `last_event_time_millis = eventTime` vào SQLite và cập nhật watermark `lastProcessedEventTimes` trong bộ nhớ.
  - Khi bước refresh token liên kết (`resolveLinkedSubscriptionToken`) gặp lỗi tạm thời (Google Play 503), handler trả `ERROR` (route map thành HTTP 503).
  - Khi Pub/Sub redeliver **chính xác eventTime/payload** đó để retry:
    - Trong cùng process: Memory watermark chặn với `SKIPPED_STALE`.
    - Sau restart hoặc trên worker/connection SQLite thứ hai: SQLite driver chặn với `staleIgnored: true` (`options.eventTimeMillis <= lastEventTime`).
    - Kết quả: Webhook trả 200 (coi như xong), nhưng token liên kết cũ (`old-linked`) vẫn vĩnh viễn ở trạng thái `VERIFIED_ACTIVE` thay vì `EXPIRED`.
- **Giải pháp (Fix):**
  - Sắp xếp lại thứ tự hoàn tất side effects: Chuyển bước giải quyết authoritative linked token (`this.verifier.resolveLinkedSubscriptionToken`) lên **trước** bước commit entitlement và advance `eventTime` watermark của token chính trong `rtdnHandler.ts`.
  - Nếu bước linked token gặp lỗi tạm thời (`UNRESOLVED_TRANSIENT`), handler trả `ERROR` ngay lập tức mà **chưa** ghi nhận `last_event_time_millis` vào store và **chưa** ghi nhận watermark vào memory.
  - Khi Pub/Sub gửi lại cùng event, bước linked token được tái thực hiện đầy đủ cho đến khi thành công, sau đó mới commit token chính kèm event watermark.
  - Đảm bảo tính idempotent: Event đã hoàn tất hoàn toàn thì lần gửi lại trùng lặp không bị cấp quyền/ack hai lần; các event thực sự cũ hơn vẫn bị chặn đúng stale guard.

---

## 2. File thay đổi

- `backend/billing-verifier/src/rtdnHandler.ts`: Sắp xếp lại luồng hoàn tất linked work trước khi bind main entitlement và cập nhật event watermark.
- `backend/billing-verifier/test/round8-regression.test.ts`: Bổ sung 2 variant tests:
  - `B801-variant completed identical RTDN redelivery is idempotent and safely ignored`: Kiểm tra duplicate redelivery sau khi hoàn tất.
  - `B801-variant newer event completed earlier blocks older out-of-order event`: Kiểm tra event cũ thực sự vẫn bị stale guard chặn.

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Bộ kiểm tra / Test Case | Kết quả trước sửa | Kết quả sau sửa | Ghi chú |
|---|---|---|---|
| `B801`: Identical RTDN retry cùng process | **FAIL** (assert `EXPIRED` got `SKIPPED_STALE`) | **PASS** | Hoàn tất linked work thành `EXPIRED` |
| `B802`: Identical retry sau close/reopen SQLite | **FAIL** (assert `EXPIRED` got `SKIPPED_STALE`) | **PASS** | Sống sót qua restart |
| `B809`: Identical retry trên connection thứ 2 | **FAIL** (assert `EXPIRED` got `SKIPPED_STALE`) | **PASS** | Hai kết nối vật lý độc lập |
| `B807` (Control): Unknown canceled query 503 | **PASS** | **PASS** | Không ảnh hưởng luồng unknown |
| `B801-variant`: Duplicate completed event | N/A | **PASS** | Idempotent, trả `SKIPPED_STALE`, không đổi state |
| `B801-variant`: Older event arrives after newer | N/A | **PASS** | Bị stale guard chặn chuẩn xác |
| Full backend baseline (14 suites, 108 tests) | 108 PASS | **108 PASS** | Không gây bất kỳ regression nào |
| Original Round 7 backend probes (9 tests) | 9 PASS | **9 PASS** | B701–B711 giữ vững |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y02** (Unknown linked new receipt theo authority state và ack outbox — G02).
