# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q06

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q06:** Thực thi Ownership Store bền vững và Version qua Restart (Defect R08 — Durable Storage & Monotonic Sequence Versioning).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp kỹ thuật

### Nguyên nhân gốc (Defect R08)
1. **Lưu trữ hoàn toàn trong RAM (In-Memory `Map`)**:
   - `backend/billing-verifier/src/store.ts` trước đây sử dụng hai cấu trúc dữ liệu `Map` trong bộ nhớ RAM (`recordsByToken` và `userEntitlements`).
   - Khi dịch vụ backend khởi động lại, crash, triển khai phiên bản mới, hoặc scale nhiều worker process:
     - Toàn bộ dữ liệu sở hữu `purchaseToken` và entitlement bị biến mất.
     - Các process khác nhau không chia sẻ được trạng thái, có thể bind cùng một receipt cho hai tài khoản khác nhau khi không có ràng buộc DB duy nhất.
2. **Snapshot Version bị reset về 1 sau restart**:
   - Sau khi restart, `snapshotVersion` của người dùng bị quay lại giá trị 1. Phía client Android vốn dĩ đã có monotonic version cao hơn (ví dụ version 2 hoặc 3) sẽ coi snapshot mới là lỗi thời và bỏ qua cập nhật gia hạn hoặc thu hồi.

### Giải pháp kỹ thuật trong Q06
1. **Kiến trúc Lưu trữ Bền vững (Durable Storage Layer)**:
   - Xây dựng module lưu trữ tại `backend/billing-verifier/src/storage/`:
     - `types.ts`: Giao diện `StorageDriver`, `TokenOwnerRecord`, `BindResult`, `AckRetryItem`.
     - `schema.ts`: DDL chuẩn hóa với các bảng:
       - `token_records`: Khóa chính `purchase_token TEXT PRIMARY KEY` (ràng buộc duy nhất tuyệt đối ở cấp độ cơ sở dữ liệu), `owner_app_user_id`, `product_id`, `first_bound_at`, `latest_snapshot_version`, `entitlement_json`, và chỉ mục `idx_token_records_owner`.
       - `user_version_sequences`: Theo dõi phiên bản snapshot tăng đơn điệu (`owner_app_user_id PRIMARY KEY`, `latest_version`).
       - `ack_retry_queue`: Hàng đợi durable chuẩn bị năng lực outbox/retry cho Q08/Q09.
2. **Trình điều khiển SQLite (`SqliteStorageDriver`) dựa trên `node:sqlite` (Node.js 24 Built-in)**:
   - Tận dụng `DatabaseSync` tích hợp sẵn trong Node.js 24 (`v24.14.0`), **không yêu cầu thêm bất kỳ thư viện npm bên ngoài nào**.
   - Hỗ trợ cả file database thực tế trên đĩa (`billing.db` hoặc đường dẫn từ `DATABASE_URL` / `SQLITE_PATH`) lẫn `:memory:` phục vụ kiểm thử nhanh.
   - Toàn bộ thao tác bind/update được đóng gói trong giao dịch nguyên tử (`BEGIN IMMEDIATE`, `COMMIT`, `ROLLBACK`), chống tranh chấp ghi đồng thời.
3. **Bảo tồn Phiên bản Đơn điệu qua Restart (Monotonic Version CAS)**:
   - Khi lưu trữ token mới hoặc cập nhật token cũ, driver tra cứu chuỗi phiên bản từ `user_version_sequences`.
   - Phiên bản mới luôn được tính toán: `nextVersion = Math.max(currentVersion + 1, incomingVersion)`.
   - Khi tiến trình restart hoặc crash recovery, chuỗi số phiên bản được nạp lại nguyên vẹn từ đĩa, đảm bảo không bao giờ reset về 1.
4. **Tương thích ngược 100% với `EntitlementStore`**:
   - `EntitlementStore` trong `store.ts` chuyển giao toàn bộ lệnh tới `StorageDriver`.
   - Giữ nguyên toàn bộ chữ ký hàm `bindOrUpdate()`, `getEntitlementsByOwner()`, `getRecordByToken()`, `clear()`, bổ sung `getUserVersion()` và `close()`.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q06)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `backend/billing-verifier/src/storage/types.ts` | Tạo mới | Định nghĩa interface `StorageDriver`, `TokenOwnerRecord`, `AckRetryItem`. |
| 2 | `backend/billing-verifier/src/storage/schema.ts` | Tạo mới | Schema SQLite DDL: `token_records`, `user_version_sequences`, `ack_retry_queue`. |
| 3 | `backend/billing-verifier/src/storage/sqliteDriver.ts` | Tạo mới | Driver SQLite thực tế sử dụng `node:sqlite` với transaction an toàn và atomic version sequence. |
| 4 | `backend/billing-verifier/src/store.ts` | Chỉnh sửa | Chuyển giao `EntitlementStore` sang `StorageDriver`, hỗ trợ đường dẫn DB từ biến môi trường hoặc constructor. |
| 5 | `backend/billing-verifier/test/storage-integration.test.ts` | Tạo mới | Bộ kiểm thử tích hợp 6 kịch bản sử dụng file SQLite thực tế trên đĩa kiểm tra durability qua restart, xung đột 2 process, rollback, version monotonicity. |
| 6 | `REPORT_VIP_BILLING_ROUND2_Q06.md` | Tạo mới | Báo cáo kiểm định gói Q06. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Bộ kiểm thử tích hợp DB thật `test/storage-integration.test.ts` (6/6 PASS)

Lệnh thực thi:
```powershell
node --experimental-strip-types --test test/storage-integration.test.ts
```

Danh sách ca kiểm thử:
1. `Storage Integration: Token ownership and snapshot version survive process restart`: **PASS** (Ghi vào file đĩa -> đóng instance -> tạo instance mới trỏ cùng file -> xác nhận owner, token, entitlement JSON và snapshotVersion v3 tồn tại nguyên vẹn).
2. `Storage Integration: Two independent connections enforce unique token ownership`: **PASS** (Hai kết nối độc lập tới cùng file DB: Alice bind token thành công; Bob cố bind token trùng bị DB từ chối với conflictOwner="usr_alice").
3. `Storage Integration: Monotonic version sequence does not reset across updates and restarts`: **PASS** (Cập nhật liên tục v1 -> v2 -> v3 -> restart -> cập nhật tiếp tục lên v4, không bao giờ reset về 1).
4. `Storage Integration: Transaction rollbacks on write failure without corrupting state`: **PASS** (Lỗi SQL giữa chừng kích hoạt ROLLBACK, không để lại dữ liệu rác trong bảng).
5. `Storage Integration: Multiple entitlements under same owner are aggregated`: **PASS** (Nhiều receipt của cùng một người dùng được truy vấn và tổng hợp đầy đủ).
6. `Storage Integration: Ack retry queue persists and retrieves pending retries`: **PASS** (Hàng đợi ack retry lưu trên đĩa, sống sót qua restart và sẵn sàng cho worker).

### 3.2. Toàn bộ kiểm thử Backend Verifier (39 PASS, 2 FAIL chờ Q07-Q08)

Lệnh thực thi:
```powershell
npm test
```

Bảng tổng hợp kết quả backend:
| File kiểm thử | Số ca test | Đạt (PASS) | Thất bại (FAIL) | Trạng thái |
|---|---|---|---|---|
| `test/storage-integration.test.ts` | 6 | 6 | 0 | **100% PASS** |
| `test/http-auth.test.ts` | 10 | 10 | 0 | **100% PASS** |
| `test/verifier.test.ts` | 15 | 15 | 0 | **100% PASS** |
| `test/rtdn_and_lifecycle.test.ts` | 6 | 6 | 0 | **100% PASS** |
| `test/round2-regression.test.ts` | 4 | 2 | 2 (Q07, Q08) | **Đạt mục tiêu hiện tại** |
| **TỔNG CỘNG** | **41** | **39** | **2** | **95.1% PASS** |

### 3.3. Kiểm tra hồi quy toàn diện Android Billing (70/70 PASS)

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

## 4. Bàn giao gói tiếp theo (Handoff sang Q07)

- **Mục tiêu Q07:** Server Restore không trả cache đã bị phủ định (Defect R10 — Authoritative Revocation & State Reconciliation on Server Restore).
- **Phạm vi tệp whitelist Q07:**
  - `backend/billing-verifier/src/verifier.ts`
  - `backend/billing-verifier/src/types.ts`
  - `backend/billing-verifier/src/store.ts` (nếu cần bổ sung API cập nhật trạng thái)
  - `backend/billing-verifier/test/restore-revocation.test.ts` (mới)
  - `REPORT_VIP_BILLING_ROUND2_Q07.md`
- **Mục tiêu cốt lõi:**
  - Khi client gọi `/restore`, server phải xác minh các candidate purchases với Google Play Developer API.
  - Nếu Google Play báo hết hạn (`PURCHASE_EXPIRED`) hoặc thu hồi (`PURCHASE_REVOKED`), server phải cập nhật trạng thái receipt trong store thành `EXPIRED` hoặc `REVOKED` và **tuyệt đối không trả về entitlement VERIFIED_ACTIVE cũ**.
  - Xanh hóa probe: `probe authoritative expired verification must not restore stale active receipt` trong `test/round2-regression.test.ts`.
