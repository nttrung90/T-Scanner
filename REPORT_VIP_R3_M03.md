# Báo Cáo Triển Khai VIP Vòng 3 — Gói M03: Identity Ổn Định & Tombstone Backend (F04, F05 Server)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F04 và F05 phía backend, đảm bảo mỗi purchase token giữ nguyên một `entitlement.id` chuẩn duy nhất (`GOOGLE_PLAY_SUBSCRIPTION_${token}` hoặc `GOOGLE_PLAY_INAPP_${token}`) xuyên suốt toàn bộ lifecycle kể cả khi hết hạn (`EXPIRED`) hay bị hoàn tiền/hủy (`REVOKED`); phản hồi `REJECTED` mang theo snapshot/tombstone có thẩm quyền đã thực sự cam kết vào storage.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/verifier.ts`:
  - Sửa nhánh `EXPIRED` (`isSub`): sử dụng canonical identity `id: GOOGLE_PLAY_SUBSCRIPTION_${purchaseToken}` (thay vì bare token), kiểm tra kết quả `bindResult.success`, sử dụng `committedEntitlement` từ bản ghi thực tế đã commit trong database.
  - Sửa nhánh `REVOKED` (in-app): sử dụng canonical identity `id: GOOGLE_PLAY_INAPP_${purchaseToken}` (thay vì bare token), kiểm tra kết quả `bindResult.success`, sử dụng `committedEntitlement` từ bản ghi thực tế đã commit trong database.
  - Xử lý xung đột chủ sở hữu (`OWNERSHIP_CONFLICT`) triệt để ngay cả trong nhánh hết hạn/thu hồi, không trả về snapshot giả định chưa qua xác thực và lưu trữ.
- `backend/billing-verifier/test/restore-revocation.test.ts`:
  - Bổ sung 2 test case cấp cao xác minh toàn diện:
    1. `M03: Active -> Expired -> Renewal lifecycle maintains stable canonical ID and monotonic versions`: quy trình 3 bước mua mới -> hết hạn -> gia hạn, đảm bảo cùng 1 ID, trạng thái chuyển đổi chính xác và version tăng đơn điệu (1 -> 2 -> 3).
    2. `M03: Lifetime active -> Revoked maintains stable canonical ID and rejects unauthorized owner`: mua trọn đời -> Google hoàn tiền -> chặn người dùng khác chiếm đoạt token đã thu hồi.
- `REPORT_VIP_R3_M03.md`: Báo cáo nội bộ gói M03.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Probe Đỏ M00 Chuyển Xanh
- Test: `entitlement ID must remain stable after authoritative expiry`
  - Trước (M00): **FAIL** (`actual: 'synthetic-audit-token', expected: 'GOOGLE_PLAY_SUBSCRIPTION_synthetic-audit-token'`)
  - Sau (M03): **PASS** (ID giữ nguyên canonical format sau khi hết hạn thẩm quyền)

### 2.2. Kiểm Tra Focused Restore/Revocation Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/restore-revocation.test.ts`
- Kết quả: **8/8 PASS**, 0 fail, thời gian: ~273ms.

### 2.3. Kiểm Tra Toàn Bộ Backend Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **72/75 PASS** (3 fail còn lại thuộc về các gói M04 và M05). Không phát sinh regression trong baseline.

---

## 3. Handoff Cho Gói Sau (M04)

- Entitlement Identity và Authoritative Tombstone đã ổn định và cam kết chính xác vào database.
- Gói M04 tiếp nhận: Xử lý F08 (Serialize/CAS trạng thái và RTDN bền vững).
  - Khắc phục 2 probe đỏ: `out-of-order RTDN completion must not override newer event` và `late expired verification cannot overwrite newer renewal`.
  - Triển khai cơ chế transaction/CAS theo token (per-token serialization/version check), bền vững qua process/restart (không chỉ in-memory Map).
  - So sánh thứ tự nghiệp vụ (Play event time / state timestamp) trước khi commit; ngăn chặn callback chậm ghi đè trạng thái mới hơn.
- Chuyển tiếp tự động sang M04.
