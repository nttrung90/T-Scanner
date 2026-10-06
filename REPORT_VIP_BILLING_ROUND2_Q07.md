# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q07

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q07:** Server Restore không trả cache đã bị phủ định (Defect R10 — Authoritative Revocation & State Reconciliation on Server Restore).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp kỹ thuật

### Nguyên nhân gốc (Defect R10)
1. **Lỗi hết hạn hoặc thu hồi từ Play API không cập nhật bản ghi cũ trong Store**:
   - `backend/billing-verifier/src/verifier.ts` trước đây khi gọi Google Play Developer API và nhận kết quả hết hạn (`Date.now() >= subResult.expiryTimeMillis`) hoặc thu hồi (`inAppResult.purchaseState === 1`), chỉ đơn thuần trả về `{ status: 'REJECTED', reason: 'PURCHASE_EXPIRED' }` hoặc `PURCHASE_REVOKED`.
   - Bản ghi cũ lưu trong store (`recordsByToken` / `token_records`) vẫn giữ nguyên trạng thái `VERIFIED_ACTIVE` từ chu kỳ trước.
2. **Quy trình `/restore` bỏ qua kết quả verify và trả mù quáng cache trong store**:
   - Trong `BillingVerifierService.restorePurchases()`: Vòng lặp verify các candidate purchases bỏ qua giá trị trả về (`await this.verifyPurchase(...)`), sau đó gọi thẳng `store.getEntitlementsByOwner(ownerAppUserId)`.
   - Vì bản ghi cũ trong store không được cập nhật trạng thái mới, server tiếp tục trả về các entitlement `VERIFIED_ACTIVE` đã hết hạn với `expiryTimeMillis` cũ từ tương lai giả lập, dẫn đến việc người dùng đã hết hạn gói hoặc bị hoàn tiền vẫn nhận được VIP active khi khôi phục giao dịch.

### Giải pháp kỹ thuật trong Q07
1. **Lưu trữ Authoritative Trạng thái EXPIRED và REVOKED**:
   - Trong `BillingVerifierService.verifyPurchase()`:
     - Khi Google Play xác nhận gói đăng ký đã hết hạn (`Date.now() >= subResult.expiryTimeMillis`):
       - Kiểm tra quyền sở hữu token. Nếu token thuộc về caller, tạo entitlement với `state = 'EXPIRED'`, cập nhật `expiryTimeMillis`, đặt `autoRenewing = false`, tăng `snapshotVersion = existing.latestSnapshotVersion + 1`.
       - Commit ngay lập tức vào `EntitlementStore` (`store.bindOrUpdate(...)`).
       - Trả về `{ status: 'REJECTED', reason: 'PURCHASE_EXPIRED', entitlement: expiredEntitlement }`.
     - Khi Google Play xác nhận gói in-app bị hoàn tiền/hủy (`inAppResult.purchaseState === 1`):
       - Tạo entitlement với `state = 'REVOKED'`, tăng `snapshotVersion = existing.latestSnapshotVersion + 1`.
       - Commit ngay lập tức vào `EntitlementStore`.
       - Trả về `{ status: 'REJECTED', reason: 'PURCHASE_REVOKED', entitlement: revokedEntitlement }`.
2. **Tổng hợp kết quả Khôi phục và Chuẩn hóa Trạng thái Phản hồi**:
   - Cập nhật `RestoreResponse` trong `backend/billing-verifier/src/types.ts`:
     - `status: 'SUCCESS' | 'TRANSIENT_ERROR' | 'PARTIAL'`.
     - `results?: Array<{ purchaseToken: string; status: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED'; reason?: RejectionReason }>`.
   - Trong `restorePurchases()`:
     - Duyệt từng candidate purchase, lưu vết kết quả chi tiết từng token (`SUCCESS`, `EXPIRED`, `REVOKED`, `TRANSIENT_ERROR`, `REJECTED`).
     - Khi tất cả candidate gặp lỗi 503 transient error: trả `status: 'TRANSIENT_ERROR'`, giữ nguyên cache hợp lệ mà không đánh dấu hết hạn sai.
     - Khi một phần thành công/hết hạn và một phần lỗi mạng: trả `status: 'PARTIAL'`.
     - Snapshot trả về phản ánh chính xác trạng thái mới nhất từ `store.getEntitlementsByOwner()`.
3. **Bảo vệ Không rò rỉ dữ liệu khi Candidate List Rỗng hoặc Mismatch Owner**:
   - Danh sách candidate rỗng (`purchases: []`): Chỉ truy vấn quyền của chính caller, không bao giờ rò rỉ dữ liệu của user khác.
   - Candidate token thuộc quyền sở hữu của người khác: Bị từ chối với `OWNERSHIP_CONFLICT` và không được gán vào snapshot của caller.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q07)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `backend/billing-verifier/src/types.ts` | Chỉnh sửa | Mở rộng `RestoreResponse` với trạng thái `PARTIAL` và mảng kết quả itemized `results`. |
| 2 | `backend/billing-verifier/src/verifier.ts` | Chỉnh sửa | Commit `EXPIRED` và `REVOKED` vào store, tăng snapshot version; tổng hợp kết quả chi tiết trong `restorePurchases`. |
| 3 | `backend/billing-verifier/test/restore-revocation.test.ts` | Tạo mới | Bộ kiểm thử 6 kịch bản cho expired subscription, refunded in-app, 503 freshness, partial restore, ownership conflict, empty candidate. |
| 4 | `REPORT_VIP_BILLING_ROUND2_Q07.md` | Tạo mới | Báo cáo kiểm định gói Q07. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Probe Regression Vòng 2 — Chuyển từ Đỏ sang Xanh

Lệnh thực thi:
```powershell
node --experimental-strip-types --test test/round2-regression.test.ts
```

Kết quả:
- **`probe authoritative expired verification must not restore stale active receipt`**:
  - *Trước sửa:* **FAIL** (`AssertionError: Restore returned stored active receipt after Google reported expiry`).
  - *Sau sửa:* **✔ PASS (2.4582ms)** (Store cập nhật trạng thái EXPIRED v2; restore không còn chứa bất kỳ receipt VERIFIED_ACTIVE nào).

### 3.2. Bộ kiểm thử mới `test/restore-revocation.test.ts` (6/6 PASS — 100% GREEN)

Lệnh thực thi:
```powershell
node --experimental-strip-types --test test/restore-revocation.test.ts
```

Danh sách ca kiểm thử:
1. `Restore Revocation: Active cached subscription that Play reports expired is updated to EXPIRED in store and not returned active`: **PASS** (Gói hết hạn cập nhật EXPIRED v2 trong store, restore không trả active VIP).
2. `Restore Revocation: Refunded/Canceled lifetime in-app is marked REVOKED in store and response`: **PASS** (Gói Lifetime bị hoàn tiền cập nhật REVOKED v2 trong store và response).
3. `Restore Revocation: Google Play 503 transient error preserves cached state and returns TRANSIENT_ERROR status`: **PASS** (Lỗi 503 giữ nguyên cache, không sửa version, trả status TRANSIENT_ERROR).
4. `Restore Revocation: Partial failure reports PARTIAL status with itemized results`: **PASS** (1 token thành công + 1 token 503 trả status PARTIAL với danh sách kết quả từng token).
5. `Restore Revocation: Ownership conflict rejects candidate belonging to another user`: **PASS** (Eve cố restore token của Alice bị từ chối OWNERSHIP_CONFLICT, snapshot của Eve rỗng).
6. `Restore Revocation: Empty candidate list does not leak other users entitlements`: **PASS** (Candidate rỗng không rò rỉ token của người dùng khác).

### 3.3. Tổng thể kiểm thử Backend Verifier (46 PASS, 1 FAIL chờ Q08)

Lệnh thực thi:
```powershell
npm test
```

Bảng tổng hợp kết quả backend:
| File kiểm thử | Số ca test | Đạt (PASS) | Thất bại (FAIL) | Trạng thái |
|---|---|---|---|---|
| `test/restore-revocation.test.ts` | 6 | 6 | 0 | **100% PASS** |
| `test/storage-integration.test.ts` | 6 | 6 | 0 | **100% PASS** |
| `test/http-auth.test.ts` | 10 | 10 | 0 | **100% PASS** |
| `test/verifier.test.ts` | 15 | 15 | 0 | **100% PASS** |
| `test/rtdn_and_lifecycle.test.ts` | 6 | 6 | 0 | **100% PASS** |
| `test/round2-regression.test.ts` | 4 | 3 | 1 (Q08) | **Đạt mục tiêu hiện tại** |
| **TỔNG CỘNG** | **47** | **46** | **1** | **97.9% PASS** |

### 3.4. Kiểm tra hồi quy toàn diện Android Billing (70/70 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.BillingRound2RegressionTest `
  --tests com.tscanner.app.BillingSnapshotPersistenceTest `
  --tests com.tscanner.app.BillingRevocationReconciliationTest `
  --tests com.tscanner.app.BillingOperationSessionTest `
  --tests com.tscanner.app.BillingGuestOwnershipTest `
  --tests com.tscanner.app.BillingPurchaseFixtureTest `
  --tests com.tscanner.app.BillingEntitlementStoreTest `
  --tests com.tscanner.app.BillingManagerTest
```

Kết quả: **70/70 PASS** (0 failed, 0 skipped). Khẳng định tính tương thích tuyệt đối giữa client và backend store.

---

## 4. Bàn giao gói tiếp theo (Handoff sang Q08)

- **Mục tiêu Q08:** RTDN retry, dedup và snapshot hiện hành (Defect R09).
- **Phạm vi tệp whitelist Q08:**
  - `backend/billing-verifier/src/rtdnHandler.ts`
  - `backend/billing-verifier/src/index.ts` (chỉ HTTP status / enqueue RTDN)
  - `backend/billing-verifier/src/store.ts` / storage (sự kiện transaction)
  - `backend/billing-verifier/test/rtdn-recovery.test.ts` (mới)
  - `REPORT_VIP_BILLING_ROUND2_Q08.md`
- **Mục tiêu cốt lõi:**
  - Khắc phục Defect R09: Khi xử lý RTDN gặp lỗi tạm thời (Google API failure), không được đánh dấu `PROCESSED` hoặc ghi nhận timestamp khiến retry sau đó bị bỏ qua (`SKIPPED_STALE`).
  - Phải trả về mã lỗi HTTP không phải 2xx (ví dụ 500/503) để Google Cloud Pub/Sub thực hiện nack/retry.
  - Xanh hóa probe cuối cùng: `probe RTDN retries same event after temporary Google API failure` trong `test/round2-regression.test.ts`.
