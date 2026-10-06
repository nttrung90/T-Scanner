# Tiến độ VIP Round 6 — Tự động chạy W00 → W10

| Gói | Mục tiêu | Trạng thái | Ghi chú / Test counts |
|---|---|---|---|
| **W00** | Baseline & regression bền vững | **DONE** | Android R6: 8 FAIL / 1 PASS; Backend R6: 2 FAIL / 1 PASS. Tổng R6: 10 FAIL / 2 PASS. R5 gốc: 16/16 PASS. |
| **W01** | Lifecycle canceled pending & linked receipt (R07) | **DONE** | B02 PASS; B03 PASS; SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED & linked token resolved. |
| **W02** | Restore backend giữ fidelity cho unresolved/per-token (R02 backend) | **DONE** | B01 PASS; B03 PASS; 99/99 backend tests PASS. Status PARTIAL on unresolved known cache. |
| **W03** | Android parser ràng buộc type/source theo catalog (R06) | **DONE** | Probes A07, A08 PASS; R5 9/9 PASS. Enforce catalog productType & provider source. |
| **W04** | Restore/sync thuộc scope và operation có thể hủy (R03) | **DONE** | Probe A04 PASS; managed scope/job trong BillingManager, hủy khi destroy. Suite: 4 PASS / 5 FAIL. |
| **W05** | Hợp nhất toàn bộ account receipts cho mọi nhánh Play (R01) | **DONE** | Probes A01, A02 PASS. Hợp nhất device catalog & server receipts, không skip restore khi pending/nonempty. |
| **W06** | Một commit và propagation lỗi profile (R04) | **DONE** | Probe A05 PASS. Single durable commit qua applySnapshotTyped, check kết quả projectSnapshotToProfile. |
| **W07** | Partial/unresolved đi tới UI đúng nghĩa (R02 Android) | **DONE** | Probe A03 PASS. Giữ metadata thất bại với failedCount > 0 cho RestoreResult.Partial. |
| **W08** | Missing/expired credential vào cùng auth recovery (R05) | **DONE** | Probe A06 PASS. Suite R6: 9/9 Android PASS, 3/3 Backend PASS. Tổng 12/12 PASS. |
| **W09** | Xác minh host độc lập & ma trận acceptance | **DONE** | 12/12 R6 PASS, 16/16 R5 PASS, 946/946 Android unit PASS, 99/99 Backend PASS, lint 0 errors, assemble OK. |
| **W10** | Gate external và bàn giao cuối một lần | **DONE** | Lập REPORT_VIP_R6_FINAL.md & docs/billing/ROUND6_ACCEPTANCE.md. Hoàn thành 100% W00-W10. |
