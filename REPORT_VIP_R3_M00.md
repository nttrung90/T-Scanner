# Báo Cáo Triển Khai VIP Vòng 3 — Gói M00: Giữ Bằng Chứng & Chốt Contract

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Thiết lập các permanent regression tests độc lập (Android 9 probes, Backend 7 probes), chốt hợp đồng kiến trúc `ROUND3_CONTRACT.md`, tái hiện chính xác 16 lỗi đỏ trước khi bắt đầu sửa code production.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/test/java/com/tscanner/app/VipRound3RegressionTest.kt`: Permanent test suite Android chứa 9 probe độc lập tái hiện F02, F03, F04, F05, F06, F07, F10.
- `backend/billing-verifier/test/round3-regression.test.ts`: Permanent test suite Node.js chứa 7 probe độc lập tái hiện F01, F04, F06, F08, F09.
- `docs/billing/ROUND3_CONTRACT.md`: Văn bản chốt hợp đồng kiến trúc Round 3 (token identity, server versioning authority, single durable commit, atomic outbox, strict schema & auth).
- `REPORT_VIP_R3_M00.md`: Báo cáo nội bộ gói M00.

---

## 2. Bằng Chứng Thực Thi & Kết Quả Tái Hiện (Red Probes Evidence)

### 2.1. Backend Probes (7/7 FAIL theo đúng kỳ vọng lỗi)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/round3-regression.test.ts`
- Kết quả: **7/7 FAIL**, thời gian: 278ms.
  1. `auth must reject development-key JWT without required identity claims` -> FAIL (F01: thiếu exception khi dùng dev secret)
  2. `auth must reject unsupported algorithm even when HMAC matches` -> FAIL (F01: cho phép RS256 nhưng ký HMAC)
  3. `empty Play JSON must not grant lifetime VIP` -> FAIL (F06: `{}` trả về SUCCESS lifetime)
  4. `entitlement ID must remain stable after authoritative expiry` -> FAIL (F04: ID đổi từ `GOOGLE_PLAY_SUBSCRIPTION_...` sang bare token)
  5. `canceled active unacknowledged receipt must enter outbox` -> FAIL (F09: CANCELED_ACTIVE còn hạn chưa ack có 0 item trong outbox)
  6. `out-of-order RTDN completion must not override newer event` -> FAIL (F08: RTDN cũ ghi đè trạng thái REVOKED thành VERIFIED_ACTIVE)
  7. `late expired verification cannot overwrite newer renewal` -> FAIL (F08: verify expired cũ ghi đè renewal mới)

### 2.2. Android Probes (9/9 FAIL theo đúng kỳ vọng lỗi)
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound3RegressionTest --offline --console=plain`
- Kết quả: **9/9 FAIL**, biên dịch thành công không cần init gradle script độc lập.
  1. `secondStoreCommitFailureMustNotReportSuccess` -> FAIL (F07: profile projection fail nhưng billing vẫn báo true)
  2. `expiredServerIdMustReplaceActiveToken` -> FAIL (F04: cùng token có id expired mới nhưng snapshot vẫn active do id cũ)
  3. `staleActiveSnapshotMustNotReportPurchaseSuccess` -> FAIL (F07: store REVOKED v9, nhận ACTIVE v1 bị drop nhưng callback báo true)
  4. `emptyDeviceCatalogMustPreserveServerEntitlement` -> FAIL (F03: query Play rỗng tự động thu hồi entitlement server hợp lệ)
  5. `driveWorkerMustRecheckVipAfterTokenWait` -> FAIL (F10: upload Drive vẫn thực hiện 1 lần dù VIP đã bị thu hồi trong khi chờ token)
  6. `malformedStateMustNotBecomeActive` -> FAIL (F06: UNKNOWN state không hạn biến thành lifetime active)
  7. `canceledPaidPeriodMustRestoreSuccessfully` -> FAIL (F07: CANCELED_ACTIVE còn hạn trả về success=false)
  8. `rejectionMustPersistAuthoritativeRevocation` -> FAIL (F05: PURCHASE_REVOKED bị bỏ qua, store vẫn VIP)
  9. `httpEndpointMustNotBeReady` -> FAIL (F02: URL http:// vẫn được coi là isConfigured() = true)

---

## 3. Danh Sách Lỗi & Handoff Cho Gói Sau (M01)

- F01–F12 đã được map đầy đủ vào contract và test harness.
- Gói M01 tiếp nhận: Sửa `backend/billing-verifier/src/auth.ts`, `types.ts`, `index.ts`, `test/http-auth.test.ts` để chặn triệt để HMAC/dev secret cho Google identity, chuyển test helper ra ngoài production path, và làm xanh 2 auth probe đỏ đầu tiên mà không phá vỡ 64 baseline backend tests.
- Trạng thái: M00 hoàn tất. Chuyển thẳng sang M01.
