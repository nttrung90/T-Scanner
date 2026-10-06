# Báo Cáo Triển Khai VIP Vòng 4 — Gói T03: Owner/Hash Kiểm Trước Mọi Mutation (R05)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R05, đảm bảo kiểm tra mã hash chủ sở hữu Google Play (`obfuscatedExternalAccountId`) ngay lập tức khi nhận phản hồi từ Google Play API và trước khi thực hiện bất kỳ thao tác lưu trữ/gắn kết (`bindOrUpdate`) nào trong cơ sở dữ liệu, ngăn chặn việc kẻ xấu mạo danh gắn kết các receipt đã hết hạn hoặc bị hoàn tiền của người khác vào tài khoản của mình.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/verifier.ts`:
  - Trong nhánh Subscription (`isSub`): di chuyển đoạn mã kiểm tra `expectedHash = computeObfuscatedAccountId(req.ownerAppUserId)` lên ngay sau khi nhận `subResult`, đứng trước nhánh kiểm tra hết hạn `if (Date.now() >= subResult.expiryTimeMillis)`. Nếu `externalAccountIdFromPlay` không khớp, từ chối ngay lập tức với lý do `ACCOUNT_HASH_MISMATCH` mà không thực hiện bất kỳ mutation nào vào store.
  - Trong nhánh In-App (`!isSub`): kiểm tra `expectedHash` ngay sau khi nhận `inAppResult`, đứng trước nhánh thu hồi `if (inAppResult.purchaseState === 1)`. Đồng thời bổ sung `{ expectedVersion }` cho lời gọi `bindOrUpdate` của nhánh revoked.
- `REPORT_VIP_R4_T03.md`: Báo cáo nội bộ gói T03.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ T00 Chuyển Xanh
- Test: `expired receipt owned by another Play hash must not bind to caller`
  - Trước (T00): **FAIL** (Receipt hết hạn của user A bị bind nhầm cho user B, bản ghi trong store khác `undefined`)
  - Sau (T03): **PASS** (Phát hiện mismatch hash của A, từ chối `ACCOUNT_HASH_MISMATCH`, không bind vào B, store giữ nguyên `undefined`)

### 2.2. Kiểm Tra Focused Verifier Suites
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/verifier.test.ts backend/billing-verifier/test/restore-revocation.test.ts`
- Kết quả: **23 / 23 PASS (100% GREEN)**, thời gian: ~198ms.

---

## 3. Handoff Cho Gói Sau (T04)

- Kiểm tra hash và chủ sở hữu trước mutation đã an toàn tuyệt đối.
- Gói T04 tiếp nhận: Sửa R04 (CAS tổng quát và concurrency mọi state):
  - File: `backend/billing-verifier/src/storage/{types,schema,sqliteDriver}.ts`, `store.ts`, `verifier.ts`, `rtdnHandler.ts`.
  - Khắc phục probe đỏ: `late ACTIVE verification must not resurrect newer EXPIRED snapshot`.
  - Thay thế guard đặc thù 1 chiều (chỉ chặn ACTIVE->EXPIRED) bằng cơ chế CAS tổng quát dựa trên `expectedVersion`.
  - Đảm bảo khi một callback ACTIVE cũ đến sau một commit EXPIRED mới hơn thì callback cũ bị từ chối/bỏ qua, bảo vệ trạng thái mới nhất trong cơ sở dữ liệu.
- Chuyển tiếp tự động sang T04.
