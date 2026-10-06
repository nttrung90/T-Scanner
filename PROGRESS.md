# Tiến Độ Sửa Mua VIP Sau Khi Phiên Xác Thực Hết Hạn

**Bắt đầu:** 04/10/2026  
**Môi trường:** `E:\DU AN AI\T-Scanner` | Windows pwsh | OpenJDK 21 | Gradle offline

## Vòng 6 — Khép Admission & Các Entry Point VIP (W00 → W03)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **W00** | Baseline & Sửa hợp đồng test bị lệch (26 PASS, 1 FAIL; P01 FAIL; migrate fixture Round 3) | **PASS** | [REPORT_W00.md](docs/vip-session-round6-fix/REPORT_W00.md) |
| **W01** | Một nguồn quản lý recovery, dispatch không tiêu lượt (S01, authoritative ledger, commit-on-accept) | **PASS** | [REPORT_W01.md](docs/vip-session-round6-fix/REPORT_W01.md) |
| **W02** | Khép mọi adapter recovery, bỏ fallback confirm trước callback (S02, Home/AccountDetail/CreatePdf) | **PASS** | [REPORT_W02.md](docs/vip-session-round6-fix/REPORT_W02.md) |
| **W03** | Tổng kiểm chứng và bàn giao (Full tests, lint, assemble, device gate) | **PASS** | [REPORT_W03.md](docs/vip-session-round6-fix/REPORT_W03.md), [REPORT_FINAL.md](docs/vip-session-round6-fix/REPORT_FINAL.md) |

---

## Vòng 5 — Khép U01 Còn Thiếu (V00 → V03)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **V00** | Khóa baseline và test đỏ (3 FAIL probes, 22 PASS, call-site wiring map) | **PASS** | [REPORT_V00.md](docs/vip-session-round5-fix/REPORT_V00.md) |
| **V01** | Quyền nhận recovery và vòng đời reservation (State machine atomic claim, budget release/commit) | **PASS** | [REPORT_V01.md](docs/vip-session-round5-fix/REPORT_V01.md) |
| **V02** | Nối nhận/từ chối và continuation vào host thật (Dialog/Fragment handshake, restore wiring, 0 extra launch) | **PASS** | [REPORT_V02.md](docs/vip-session-round5-fix/REPORT_V02.md) |
| **V03** | Tổng kiểm chứng và bàn giao (Full tests, lint, assemble, device gate) | **PASS** | [REPORT_V03.md](docs/vip-session-round5-fix/REPORT_V03.md), [REPORT_FINAL.md](docs/vip-session-round5-fix/REPORT_FINAL.md) |

---

## Vòng 4 — Khắc Phục Triệt Để K01–K03 (U00 → U04)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **U00** | Baseline và khóa đúng đường consumer để test (5 FAIL probes, 14 regressions PASS, consumer seam) | **PASS** | [REPORT_U00.md](docs/vip-session-round4-fix/REPORT_U00.md) |
| **U01** | Recovery budget theo user operation và lúc thực sự được nhận (K01, operation-bound budget, no silent spend) | **PASS** | [REPORT_U01.md](docs/vip-session-round4-fix/REPORT_U01.md) |
| **U02** | Validate event tại thời điểm tiêu thụ (K02, drain suppression, consumer validation) | **PASS** | [REPORT_U02.md](docs/vip-session-round4-fix/REPORT_U02.md) |
| **U03** | Navigation guest phải cùng session trước khi login bắt đầu (K03, validate generation cho guest) | **PASS** | [REPORT_U03.md](docs/vip-session-round4-fix/REPORT_U03.md) |
| **U04** | Tổng kiểm chứng và bàn giao (Full tests, lint, assemble, device gate) | **PASS** | [REPORT_U04.md](docs/vip-session-round4-fix/REPORT_U04.md), [REPORT_FINAL.md](docs/vip-session-round4-fix/REPORT_FINAL.md) |

---

## Vòng 3 — Xử Lý Triệt Để H01–H04 (T00 → T05)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **T00** | Khóa bằng chứng và hợp đồng trước khi sửa (4 FAIL probes, 7 Round 2 PASS, 3 integration contracts) | **PASS** | [REPORT_T00.md](docs/vip-session-round3-fix/REPORT_T00.md) |
| **T01** | Bắt buộc identity của attempt khi consume continuation (H02, `originatingRequestId`, ignore unrelated) | **PASS** | [REPORT_T01.md](docs/vip-session-round3-fix/REPORT_T01.md) |
| **T02** | Kết thúc đúng continuation khi fallback không mở được (H03, catch block giải phóng pending) | **PASS** | [REPORT_T02.md](docs/vip-session-round3-fix/REPORT_T02.md) |
| **T03** | Khép auth recovery của giao dịch đã có receipt (H01, verify credential guards, receipt recovery event) | **PASS** | [REPORT_T03.md](docs/vip-session-round3-fix/REPORT_T03.md) |
| **T04** | Điều hướng auth có origin, tiêu thụ một lần (H04, navigation envelope, validate origin sau logout) | **PASS** | [REPORT_T04.md](docs/vip-session-round3-fix/REPORT_T04.md) |
| **T05** | Tổng kiểm chứng, báo cáo và device gate (Full tests, lint, assemble, final review) | **PASS** | [REPORT_T05.md](docs/vip-session-round3-fix/REPORT_T05.md), [REPORT_FINAL.md](docs/vip-session-round3-fix/REPORT_FINAL.md) |

---

## Vòng 2 — Sửa Triệt Để Các Khoảng Hở VIP Session (S00 → S07)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **S00** | Khóa reproduction và sửa chất lượng kiểm thử (G05, 8 probes, real dialog seams) | **PASS** | [REPORT_S00.md](docs/vip-session-round2-fix/REPORT_S00.md) |
| **S01** | Continuation nhận đúng chuyển phiên của attempt (G01, guest commit $null \to A$) | **PASS** | [REPORT_S01.md](docs/vip-session-round2-fix/REPORT_S01.md) |
| **S02** | Áp dụng owner/action contract cho PDF và ID-card (G02, `expectedOwnerId`, RESTORE) | **PASS** | [REPORT_S02.md](docs/vip-session-round2-fix/REPORT_S02.md) |
| **S03** | Giữ lý do auth-required qua điều hướng/restore (G03, nhánh restore, backend 401) | **PASS** | [REPORT_S03.md](docs/vip-session-round2-fix/REPORT_S03.md) |
| **S04** | Auth recovery sau receipt, không coi 401 là mạng (G03, nhánh verify, zero extra launch) | **PASS** | [REPORT_S04.md](docs/vip-session-round2-fix/REPORT_S04.md) |
| **S05** | Khóa terminal theo từng thao tác mua (G04, per-operation identity/terminal guard) | **PASS** | [REPORT_S05.md](docs/vip-session-round2-fix/REPORT_S05.md) |
| **S06** | Chuỗi reauth đúng locale (G06, values English default, values-vi, all locales) | **PASS** | [REPORT_S06.md](docs/vip-session-round2-fix/REPORT_S06.md) |
| **S07** | Kiểm chứng và bàn giao cuối (Toàn bộ test suites, lint, offline assemble, gate review) | **PASS** | [REPORT_FINAL.md](docs/vip-session-round2-fix/REPORT_FINAL.md) |

---

## Vòng 1 — Lịch Sử Thực Hiện (E00 → E06)

| Gói | Nội dung & Mục tiêu | Trạng thái | Báo cáo chi tiết |
|---|---|---|---|
| **E00** | Chụp baseline và tạo regression thực sự (F02, F04, F05 đỏ; baseline 28/28 xanh) | **PASS** | [REPORT_E00.md](docs/vip-session-20261004/REPORT_E00.md) |
| **E01** | Xác thực lại an toàn cho tài khoản đang đăng nhập (`AppAuthManager`, `GoogleLoginAttempt`, auth tests) | **PASS** | [REPORT_E01.md](docs/vip-session-20261004/REPORT_E01.md) |
| **E02** | Giữ đúng continuation qua xác thực lại (`VipLoginContinuationHandler`, `MoreFragment`) | **PASS** | [REPORT_E02.md](docs/vip-session-20261004/REPORT_E02.md) |
| **E03** | Nối Chi tiết tài khoản và phân biệt lời nhắc (`AccountDetailDialog`, `VipUpgradeDialog`, strings) | **PASS** | [REPORT_E03.md](docs/vip-session-20261004/REPORT_E03.md) |
| **E04** | Khép các khoảng hở khi đang tải/mua/khôi phục (`VipPurchaseActionCoordinator`, `BillingManager`) | **PASS** | [REPORT_E04.md](docs/vip-session-20261004/REPORT_E04.md) |
| **E05** | Kiểm thử tổng hợp và rà soát độc lập (Toàn bộ suites, lint, audit diff không log sensitive) | **PASS** | [REPORT_E05.md](docs/vip-session-20261004/REPORT_E05.md) |
| **E06** | Nghiệm thu trên thiết bị/bản Play & Checklist bàn giao cuối cùng | **DONE** (Host PASS / Device Checklist ready) | [REPORT_VIP_SESSION_FINAL.md](docs/vip-session-20261004/REPORT_VIP_SESSION_FINAL.md) |

---

## Invariants & Design Decisions Bắt Buộc (Mục 3)
1. Giữ nguyên `isLoggedIn() = currentUser != null`.
2. Không gọi `signOut()`, không xóa profile/dữ liệu/entitlement khi bắt đầu hoặc thất bại xác thực lại.
3. Ràng buộc `expectedOwnerId` và session generation vào `GoogleLoginAttempt`. Nếu trả về account B khác expected owner A, từ chối TRƯỚC `processSignedInAccountInternal` (trước commit/migrate/claim side effects).
4. Giữ nguyên pipeline sign-in Google (Credential Manager + fallback Intent), single in-flight lock, logout serialization.
5. Sau thành công đúng account: UPGRADE trở về xác nhận gói, RESTORE chạy khôi phục đúng 1 lần; không tự launch mua nếu chưa có xác nhận từ người dùng.
