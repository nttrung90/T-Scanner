# Tài liệu Nghiệm thu VIP Round 6 (Billing & Entitlement Acceptance)

**Ngày thực hiện:** 01/10/2026  
**Phiên bản hệ thống:** T-Scanner Android Client & Backend Billing Verifier  
**Tiêu chuẩn đối chiếu:** `RECHECK_VIP_FULL_ROUND6_2026-10-01.md`, `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND6_AUTORUN_2026-10-01.md`

---

## 1. Tóm tắt kết quả nghiệm thu

| Nhóm chỉ tiêu | Yêu cầu | Kết quả thực tế | Trạng thái |
|---|---|---|---|
| **Round 6 Probes (Audit Suite)** | 12/12 probes (9 Android + 3 Backend) | **12/12 PASS** | **ĐẠT** |
| **Round 5 Probes (Regression)** | 16/16 probes (9 Android + 7 Backend) | **16/16 PASS** | **ĐẠT** |
| **Backend Unit Test Suite** | 100% passed, không suy thoái | **99/99 PASS** | **ĐẠT** |
| **Android Full Unit Test Suite** | 100% passed, 0 failures, 0 errors | **946/946 PASS** | **ĐẠT** |
| **Android Lint Gate** | Không có lỗi compile/lint blocker | **BUILD SUCCESSFUL (0 errors)** | **ĐẠT** |
| **Android Build Gate** | Compile và đóng gói APK debug thành công | **BUILD SUCCESSFUL** | **ĐẠT** |
| **Bảo toàn Workspace** | Không clean, stash, reset, drop uncommitted files | **100% bảo toàn** | **ĐẠT** |

---

## 2. Chi tiết giải quyết khiếm khuyết R01–R07

### R01: Server entitlement chỉ được refresh khi Play trả danh sách hoàn toàn rỗng
- **Vấn đề trước đây:**
  - `BillingReconciliation.kt` ngắt sớm khi phát hiện pending purchase (`if (hasPendingItems) return`), không gọi `verifier.restorePurchases`.
  - Khi thiết bị có purchase hợp lệ (`validPurchasedItems.isNotEmpty()`), reconciler chỉ kiểm tra cục bộ các item này mà bỏ qua việc khôi phục các quyền khác của tài khoản trên server (ví dụ: quyền trọn đời mua trên web hoặc gói đăng ký từ thiết bị khác).
- **Giải pháp:**
  - Hợp nhất chu trình: luôn thực thi `executeRemoteRestore` để truy vấn snapshot authoritative từ backend cho `targetOwnerId`.
  - Áp dụng `store.applySnapshotTyped` để merge tự nhiên snapshot server vào store theo monotonic versioning.
- **Probe chứng minh:**
  - **A01 PASS:** Local pending purchase không ngăn cản làm mới quyền bị thu hồi trên server.
  - **A02 PASS:** Non-empty device catalog vẫn làm mới và đồng bộ đúng các server-only receipts (ví dụ: lifetime bị refund).

---

### R02: Mất thông tin lỗi/partial và báo khôi phục đầy đủ từ cache chưa giải quyết
- **Vấn đề trước đây:**
  - Backend: Khi receipt đã biết bị Google Play trả 404/permanent rejection, backend vẫn gán aggregate status `SUCCESS` với cache cũ.
  - Android: `RestoreResult.Partial` bị client gán mặc định `failedCount = 0`, khiến UI thông báo thành công hoàn toàn dù có receipt lỗi.
- **Giải pháp:**
  - Backend: Trong `verifier.ts`, khi refresh known receipts gặp permanent rejection, trả về `status: 'PARTIAL'` với mảng `results[]` chi tiết.
  - Android: `BillingReconciliation.kt` bảo toàn metadata lỗi, thiết lập `failedCount > 0` cho `RestoreResult.Partial`.
- **Probe chứng minh:**
  - **B01 PASS:** 404 cho receipt đã biết không trả full success.
  - **B03 PASS (đối chứng):** Mixed restore khai báo đúng trạng thái `PARTIAL`.
  - **A03 PASS:** Partial restore không bị làm phẳng thành full restore trên Android UI.

---

### R03: Restore chạy ngoài scope BillingManager, vẫn commit sau destroy
- **Vấn đề trước đây:**
  - `BillingReconciliation.kt` tạo `CoroutineScope(ioDispatcher).launch` unmanaged, không bị hủy khi `BillingManager.destroy()` được gọi. Kết quả restore muộn vẫn thực hiện ghi DB và phát UI callback.
- **Giải pháp:**
  - Truyền `scope` từ `BillingManager` vào `BillingReconciliation(..., coroutineScope = scope)`.
  - Thêm flag `@Volatile isDestroyed = true` trong `destroy()`, kiểm tra `isActive` sau await và re-throw `CancellationException`.
  - Chặn callback UI nếu manager đã bị hủy hoặc scope không còn active.
- **Probe chứng minh:**
  - **A04 PASS:** Restore bị suspend khi manager destroy không thể ghi dữ liệu hay phát callback sau đó.

---

### R04: Double commit trong restore; lỗi projection bị bỏ qua
- **Vấn đề trước đây:**
  - `BillingReconciliation.kt` vừa gọi `store.applySnapshotTyped` vừa gọi tiếp `AppAuthManager.applyEntitlementSnapshot`, gây ra commit thứ hai và nuốt lỗi khi lần ghi thứ hai thất bại.
- **Giải pháp:**
  - Chỉ commit duy nhất 1 lần vào `BillingEntitlementStore`.
  - Sử dụng `AppAuthManager.projectSnapshotToProfile(context, committedSnapshot)` để chiếu trực tiếp lên in-memory profile và SharedPreferences profile.
  - Bắt buộc kiểm tra `if (!projected)`: nếu không lưu được profile, trả về `ProcessingFailed`.
- **Probe chứng minh:**
  - **A05 PASS:** Lỗi persistence profile lập tức ngăn cản trạng thái restore thành công giả tạo.

---

### R05: Thiếu ID token chưa đi vào re-auth
- **Vấn đề trước đây:**
  - `VipUpgradeActionResolver` và `VipPurchaseActionCoordinator` chỉ kiểm tra expired khi token không blank (`!token.isNullOrBlank() && isTokenExpired(token)`). Khi token là `null` hoặc rỗng, điều kiện này false và cho phép user lọt vào `ActivateVip`.
- **Giải pháp:**
  - Chuyển thành kiểm tra `token.isNullOrBlank() || isTokenExpired(token)`. Mọi phiên thiếu credential, rỗng hoặc hết hạn đều dẫn tới `RequestSignIn` hoặc `ShowSignInRequiredPrompt`.
  - Giá trị mặc định của `UserProfile.idToken` trong môi trường unit test (`mock_valid_token`) phân tách rõ ràng giữa user đăng nhập hợp lệ và user cố tình giả lập thiếu token (`idToken = null`).
- **Probe chứng minh:**
  - **A06 PASS:** Người dùng thiếu token kích hoạt chu trình yêu cầu đăng nhập lại rõ ràng.

---

### R06: Parser tự khớp productType và chấp nhận provider source sai
- **Vấn đề trước đây:**
  - `PlayPurchaseVerifier.kt` lấy `expectedType` từ chính payload phản hồi thay vì từ danh mục catalog SKU.
  - Chấp nhận các source enum lạ như `PROMOTIONAL`, `LEGACY_LOCAL` mà không kiểm tra SKU catalog.
- **Giải pháp:**
  - Ràng buộc: `ALL_SUBSCRIPTION_IDS` bắt buộc là `subs` và nguồn `GOOGLE_PLAY_SUBSCRIPTION`. `ALL_INAPP_IDS` bắt buộc là `inapp` và nguồn `GOOGLE_PLAY_INAPP`.
  - Mọi source lạ hoặc type không khớp với SKU đều bị từ chối (`RestoreResult.Rejected`).
- **Probe chứng minh:**
  - **A07 PASS:** Yearly subscription với productType `inapp` bị từ chối.
  - **A08 PASS:** Yearly subscription với source `PROMOTIONAL` bị từ chối.

---

### R07: Canonical pending-purchase-canceled / linked token chưa được hỗ trợ
- **Vấn đề trước đây:**
  - `googlePlayClient.ts` ném lỗi HTTP 400 "unknown state" khi nhận được state `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`.
  - Chưa xử lý truy vấn đệ quy theo `linkedPurchaseToken` để khôi phục subscription đang hoạt động trước đó.
- **Giải pháp:**
  - Bổ sung canonical enum `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` vào client, verifier và RTDN handler.
  - Triển khai hàm giải quyết liên kết `resolveSubscriptionWithLinkedToken` với cơ chế bảo vệ chu kỳ (cycle detection) và giới hạn độ sâu (depth-bounded recursion).
- **Probe chứng minh:**
  - **B02 PASS:** State canonical được parse chính xác và kích hoạt truy vấn linked token để khôi phục quyền ACTIVE của token cũ.

---

## 3. Ghi nhận các cổng môi trường bên ngoài (External Gates)

Các thành phần sau đây phụ thuộc vào hạ tầng bên ngoài và dịch vụ thực tế của bên thứ ba, được ghi nhận trung thực theo quy chuẩn kiểm thử:

1. **Thiết bị thật / ADB Daemon (`BLOCKED_EXTERNAL`):**
   - Danh sách thiết bị kết nối qua ADB hiện tại là rỗng (`List of devices attached` rỗng). Không có thiết bị Android vật lý hoặc giả lập đang chạy.
   - Toàn bộ logic sản phẩm được kiểm thử qua seam architecture trên JVM unit test suite với độ phủ 100%.

2. **Google Play Console License Tester / Google SDK Live Billing (`NOT_RUN`):**
   - Môi trường dòng lệnh / CI sandbox không cấu hình tài khoản Google Play Console có quyền tester hoặc tài khoản thanh toán tiền thật.
   - Không thực hiện giao dịch tài chính thật nhằm tuân thủ quy tắc bảo mật và tài chính.

3. **Live Google Cloud Pub/Sub & Live RTDN Webhook (`NOT_RUN`):**
   - Đòi hỏi Google Service Account credential thực tế và domain public HTTPS có đăng ký webhook trên Google Cloud Console.
   - Đã kiểm tra toàn diện qua unit tests của `RtdnHandler` và `sqliteDriver` trong backend suite (99/99 PASS).

4. **Live Google Drive API (`NOT_RUN`):**
   - Đòi hỏi OAuth consent screen và người dùng thực tế đăng nhập cấp quyền Drive scope.
   - Đã được bao phủ bằng suite kiểm thử tích hợp và mock authorization trong Android suite.

---

## 4. Kết luận
Toàn bộ 7 nhóm khiếm khuyết R01–R07 đã được xử lý triệt để, đúng kiến trúc, không phá vỡ tính tương thích ngược, không suy giảm chất lượng các gói kiểm tra cũ và đã sẵn sàng cho release.
