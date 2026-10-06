# Báo cáo Kiểm chứng VIP Vòng 8 — Gói Y04 (Backend Một Kết Quả Duy Nhất Cho Mỗi Receipt — G04 Backend)

**Ngày thực hiện:** 01/10/2026  
**Trạng thái:** HOÀN THÀNH (DONE) — Tự động chuyển sang Y05  
**Lỗi giải quyết:** G04 Backend (P2: restorePurchases push trùng token khi một token vừa là candidate vừa xuất hiện qua linked resolution)

---

## 1. Mục tiêu & Nguyên nhân gốc (Root Cause)

- **Hiện tượng lỗi (Pre-fix):**
  - Trong `BillingVerifierService.restorePurchases()`, khi xử lý danh sách candidates (gồm receipts đã lưu trong store và purchases gửi lên từ client), kết quả được push trực tiếp vào mảng `results: Array<{ purchaseToken, status, reason }>`.
  - Khi một candidate (ví dụ: `new-canceled`) có liên kết tới một token khác (ví dụ: `old-linked`), phương thức `verifyPurchase` thực hiện linked resolution và push `old-linked` vào mảng `results`.
  - Vòng lặp duyệt tiếp theo của `candidateMap` lại gặp `old-linked` (vì nó cũng là candidate trong store), và tiếp tục push `old-linked` vào `results` lần thứ hai!
  - Hậu quả: Mảng `results` chứa 2 bản ghi trùng lặp cho cùng một `purchaseToken` (`tokens.length > new Set(tokens).size`), làm tăng sai lệch số lượng resolved receipt đếm được ở client UI và vi phạm tính duy nhất của receipt outcome.
- **Giải pháp (Fix):**
  - Thay thế mảng kết quả bằng `resultMap: Map<string, RestoreItemResult>` khóa theo `purchaseToken`.
  - Xây dựng helper `recordResult` với quy tắc thứ tự ưu tiên (precedence) rõ ràng:
    - Authoritative outcomes (`SUCCESS`, `EXPIRED`, `REVOKED`) luôn ưu tiên hơn transient/pending (`TRANSIENT_ERROR`, `PENDING`).
    - Definitive `REJECTED` không bị ghi đè bởi `TRANSIENT_ERROR` hay `PENDING`.
    - Khi có nhiều kết quả authoritative, trạng thái authoritative mới nhất được ghi nhận (latest authoritative state wins).
  - Tối ưu truy vấn mạng: Nếu một candidate trong `candidateMap` đã được linked resolution trước đó giải quyết dứt điểm (`isAuthoritative(existing.status)`), vòng lặp bỏ qua (`continue`), không chạy lại truy vấn Play API thừa.
  - Chuyển `resultMap.values()` thành mảng `results` trả về cuối cùng, đảm bảo `tokens.length === new Set(tokens).size`.

---

## 2. File thay đổi

- `backend/billing-verifier/src/verifier.ts`: Cập nhật `restorePurchases()` sử dụng `resultMap`, `recordResult` và kiểm tra trùng lặp candidate với authoritative outcome.

---

## 3. Kết quả kiểm tra Regression & Acceptance

| Bộ kiểm tra / Test Case | Kết quả trước sửa | Kết quả sau sửa | Ghi chú |
|---|---|---|---|
| `B806`: Restore known canceled new then known linked old | **FAIL** (tokens.length = 3, expected 2; duplicates found) | **PASS** (tokens.length = 2, new Set(tokens).size = 2) | Đúng 1 outcome duy nhất cho mỗi token |
| Full backend baseline (14 suites, 108 tests) | 108 PASS | **108 PASS** | Không regression |
| Original Round 7 backend probes (9 tests) | 9 PASS | **9 PASS** | B701–B711 giữ vững |
| Round 8 backend probes (17 tests) | 15 PASS / 2 FAIL | **16 PASS / 1 FAIL** | Chỉ còn B812 (thuộc gói Y11) |

---

## 4. Bước kế tiếp

Tự động chuyển sang gói **Y05** (Typed Pending/Unresolved/No-Active trong Android — G05).
