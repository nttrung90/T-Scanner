# Tài Liệu Nghiệm Thu VIP Vòng 8 (Billing & Entitlement Acceptance)

**Ngày thực hiện:** 01/10/2026  
**Phiên bản hệ thống:** T-Scanner Android Client (v0.6.0) & Backend Billing Verifier  
**Tiêu chuẩn đối chiếu:** `RECHECK_VIP_FULL_ROUND8_2026-10-01.md`, `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND8_AUTORUN_2026-10-01.md`  
**Môi trường:** `E:\DU AN AI\T-Scanner` | Gradle offline | Node v22.12.0  

---

## 1. Tóm tắt Kết Quả Nghiệm Thu Ma Trận

| Nhóm chỉ tiêu | Mục tiêu / Baseline | Kết quả thực tế | Trạng thái |
|---|---|---|---|
| **Round 8 Probes (Audit Suite)** | 33 probes (20 Android + 13 Backend) | **33 / 33 PASS** | **ĐẠT (100%)** |
| **Round 8 Failures Trước Sửa** | 23 failures (14 Android + 9 Backend) | **0 FAIL / 23 FIXED** | **ĐẠT (100%)** |
| **Round 7 Probes (Regression)** | 27 probes (18 Android + 9 Backend) | **27 / 27 PASS** | **ĐẠT (100%)** |
| **Backend Full Unit Test Suite** | >= 108 tests, 0 failures | **128 / 128 PASS** | **ĐẠT (100%)** |
| **Android Full Unit Test Suite** | >= 964 tests, 0 failures | **983 / 983 PASS** | **ĐẠT (100%)** |
| **Android Lint Gate** | 0 compile/lint errors | **0 errors, 757 warnings** | **ĐẠT** |
| **Android Build Gate** | Compile & Assemble APK debug | **BUILD SUCCESSFUL** | **ĐẠT** |
| **Bảo toàn Workspace** | Không clean, stash, reset, drop | **100% bảo toàn** | **ĐẠT** |

---

## 2. Chi Tiết Giải Quyết 10 Nhóm Khiếm Khuyết G01–G10

### G01: RTDN hoàn tất linked work trước khi consumed, durable retry (Y01)
- **Vấn đề trước sửa:**
  - Watermark và durable event-time trong `rtdnHandler.ts` coi toàn bộ event đã hoàn tất ngay cả khi linked resolution hoặc lưu trữ gặp lỗi. Sự kiện gửi lại cùng timestamp bị chặn là `SKIPPED_STALE`, bỏ dở linked receipt chưa xử lý.
- **Giải pháp:**
  - Sắp xếp thứ tự thực thi: Giải quyết triệt để `resolveLinkedSubscriptionToken` trước khi ghi watermark và trước khi cập nhật main token.
  - Hỗ trợ durable retry: Nếu linked resolution thất bại tạm thời (ví dụ Play API 503), handler trả về `ERROR` (HTTP 503) mà không cập nhật watermark, cho phép PubSub gửi lại cùng event và hoàn tất bước linked còn thiếu.
- **Probes chứng minh:**
  - **B801 PASS:** RTDN linked 503 cho phép retry cùng event trong cùng process.
  - **B802 PASS:** Quá trình linked dở dang khôi phục thành công sau restart và handler mới.
  - **B809 PASS:** Redelivery trên kết nối SQLite thứ 2 hoàn thành linked work còn thiếu.

---

### G02: Unknown linked new receipt theo authority state & ack outbox (Y02)
- **Vấn đề trước sửa:**
  - Token mới chưa có trong DB nhận RTDN nhưng schema Google PubSub không chứa trường `subscriptionId`, dẫn tới SQLite ném ngoại lệ `ERR_INVALID_ARG_TYPE: parameter 4 undefined`.
  - Không trích xuất được `lineItemProductId`, map sai trạng thái entitlement hoặc không xếp hàng ack outbox.
- **Giải pháp:**
  - Cập nhật schema `DeveloperNotificationPayload`: `subscriptionId` là trường tùy chọn (`optional`).
  - Trích xuất `lineItemProductId` từ `GooglePlaySubscriptionResult.lineItems[0].productId`.
  - Xác thực catalogue và kiểm tra hash người dùng (`obfuscatedExternalAccountId`).
  - Ánh xạ đúng trạng thái quyền hạn (`VERIFIED_ACTIVE`, `REVOKED`) và tự động xếp hàng vào `store.enqueueAckRetry`.
- **Probes chứng minh:**
  - **B803 PASS:** Upgrade gói năm từ gói tháng bảo toàn quyền `VERIFIED_ACTIVE` sau khi token tháng cũ hết hạn.
  - **B804 PASS:** Token nâng cấp mới chưa ack được tự động xếp hàng ack outbox.
  - **B810 PASS:** PubSub payload không có `subscriptionId` không làm crash handler, lưu đúng `REVOKED` và product ID.
  - **B811 PASS (đối chứng):** Known receipt với payload không có `subscriptionId` hoạt động ổn định.

---

### G03: Kiểm tra kết quả bind và outbox trong unknown RTDN (Y03)
- **Vấn đề trước sửa:**
  - Handler nuốt kết quả trả về từ `store.bindOrUpdate` mà không kiểm tra `casConflict`, `conflictOwner` hay `staleIgnored`, dẫn tới trả về `PROCESSED` sai thực tế.
- **Giải pháp:**
  - Kiểm tra tường minh `bindResult.success`: Nếu có lỗi lưu trữ hoặc bị từ chối, không cập nhật watermark và trả về mã lỗi thích hợp để PubSub retry.
- **Probes chứng minh:**
  - **B805 PASS:** Unknown canceled token khi bị store từ chối không được xác nhận là `PROCESSED`.

---

### G04: Backend một final outcome cho mỗi receipt & Android fresh count (Y04, Y06)
- **Vấn đề trước sửa:**
  - Backend: Danh sách kết quả khôi phục có thể chứa nhiều bản ghi cho cùng một purchase token khi truy vấn linked chains.
  - Android: Tính toán số lượng khôi phục bị trùng lặp giữa local cache và remote server; đếm cả quyền lợi đã có sẵn từ cache vào `activeCount`.
- **Giải pháp:**
  - Backend: Dùng `Map<string, RestoreItemResult>` để đảm bảo mỗi `purchaseToken` chỉ xuất hiện đúng một lần trong `results[]`, với thứ tự ưu tiên: `SUCCESS` > `EXPIRED`/`REVOKED` > `TRANSIENT_ERROR` > `PENDING`.
  - Android: Dùng token identity sets trong `BillingReconciliation.kt` để dedup (`allFailedTokens = remotelyFailedTokens + (locallyFailedTokens - remotelySucceededTokens)`). Chỉ tính `activeCount` dựa trên các token freshly restored (`remotelySucceededTokens + locallySucceededTokens`).
- **Probes chứng minh:**
  - **B806 PASS:** Mỗi purchase token chỉ có duy nhất 1 kết quả trong response.
  - **A804 PASS:** Token identity deduplication không làm sai lệch số lượng.
  - **A805 PASS:** Remote server authoritative success ghi đè local failures.
  - **A812 PASS:** Quyền lợi từ cache không bị tính vào fresh restore count (`freshCount == 0`).

---

### G05: Typed pending / unresolved state trong Android (Y05)
- **Vấn đề trước sửa:**
  - Android relabel trạng thái `PENDING` thành `SUCCESS` hoặc xem `PENDING` là lỗi fatal, gây nhầm lẫn trên giao diện người dùng.
- **Giải pháp:**
  - Thêm `RestoreResult.NoActivePurchases` và `ReconciliationResult.PendingApproval`.
  - Tách bạch rõ: Giao dịch pending được đếm trong `itemFailureCount` / pending items để thông báo rõ ràng cho người dùng là giao dịch đang chờ xử lý từ phía ngân hàng/Google Play, không mở khóa sớm và cũng không báo thất bại vĩnh viễn.
- **Probes chứng minh:**
  - **A801 PASS:** Pending payment không bị relabel thành full success.
  - **A802 PASS:** Incomplete items được phản ánh chính xác trong reconciliation result.
  - **A803 PASS:** Candidate list được chuyển tiếp đầy đủ lên server khi restore.
  - **A806 PASS:** Pending reconciliation trả về `PendingApproval` typed result.

---

### G06: Chặn SDK/listener callbacks của manager đã dispose (Y10)
- **Vấn đề trước sửa:**
  - Khi `BillingManager` bị destroy hoặc coroutine scope bị cancel, callback từ Play SDK (`onPurchasesUpdated`), background worker (`processPurchase`) hoặc runnable đang chờ trên Main Looper vẫn gọi vào listener, gây memory leak hoặc crash.
- **Giải pháp:**
  - Trong `BillingManager.destroy()`: Gọi `purchaseCallbacks.clear()`.
  - Đặt guard `if (isDestroyed || !scope.isActive) return` ở cửa ngõ `onPurchasesUpdated`, `processPurchase`, và cả 2 lớp trong `notifyCallbacks` (trước khi post và bên trong Runnable trên Main Thread).
- **Probes chứng minh:**
  - **A807 PASS:** `destroy()` xóa toàn bộ callbacks và dập tắt async notifications.
  - **A808 PASS:** Hủy scope dập tắt triệt để late `onPurchasesUpdated`.
  - **A811 PASS:** Disposed manager không xử lý hoặc phát sinh callback mua hàng muộn.

---

### G07: Purchase operation owner & readiness trước await & launch (Y07)
- **Vấn đề trước sửa:**
  - `VipPurchaseActionCoordinator` và `BillingManager` chỉ kiểm tra session ban đầu, không xác thực lại session sau khi các tác vụ async (kết nối Play Billing, truy vấn ProductDetails) hoàn tất.
- **Giải pháp:**
  - Thêm `validateCurrentSession(initialUserId, initialGen)` kiểm tra: User ID hiện tại, generation session và tính hợp lệ của token (`!isTokenExpired(token)`) ngay trước khi gọi `launchBillingFlow`. Nếu session thay đổi hoặc token hết hạn, hủy luồng và yêu cầu đăng nhập lại.
- **Probes chứng minh:**
  - **C804 PASS:** Session user thay đổi giữa chừng hủy luồng thanh toán an toàn.
  - **C805 PASS:** Session generation thay đổi trong lúc chờ query hủy launch.
  - **C807 PASS:** Token hết hạn trong lúc chờ preflight kích hoạt `onRequestSignIn`.

---

### G08: Guard HTTPS/auth/session ở actual verify & restore transport (Y08)
- **Vấn đề trước sửa:**
  - Kẻ tấn công hoặc cấu hình sai có thể truyền plain `http://` hoặc bearer token rỗng/hết hạn/malformed qua network transport.
- **Giải pháp:**
  - Áp dụng fail-closed guard tại `PlayPurchaseVerifier.kt`:
    - `isBackendConfigured()`: Bắt buộc URL bắt đầu bằng `https://`.
    - `isAuthReady()`: Kiểm tra token JWT 3 thành phần, giải mã payload và kiểm tra claim `exp`.
    - Kiểm tra session generation trước và sau khi lấy token trong `restorePurchases`.
- **Probes chứng minh:**
  - **C801 PASS:** Expired credential bị chặn trước khi chạm vào transport (`calls == 0`).
  - **C802 PASS:** Malformed credential bị chặn trước transport (`calls == 0`).
  - **C803 PASS:** Plain `http://` bị chặn bởi HTTPS guard (`calls == 0`).
  - **C806 PASS:** Session generation lệch chặn restore transport.
  - **C808 PASS:** Owner mismatch chặn restore transport.

---

### G09: UI auth recovery thực & continuation đúng action (Y09)
- **Vấn đề trước sửa:**
  - Dialog nâng cấp VIP và khôi phục VIP không kích hoạt flow đăng nhập thực tế khi tài khoản hết hạn; sau khi đăng nhập thành công không tự động tiếp tục hành động dang dở.
- **Giải pháp:**
  - Thêm `enum class VipContinuationAction { UPGRADE, RESTORE }` trong `VipLoginContinuationHandler.kt`.
  - Dialog nâng cấp/khôi phục dispatch callback `onRequestSignInForAction`.
  - `HomeFragment`, `MoreFragment`, `MainActivity` hỗ trợ `EXTRA_VIP_ACTION`, lưu ngữ cảnh và tự động thực thi lại action sau khi đăng nhập thành công.
- **Kiểm chứng:**
  - `VipLoginContinuationTest`: 15 / 15 tests PASS.
  - Bao phủ toàn diện các luồng tiếp tục thanh toán, khôi phục và dập tắt khi session stale.

---

### G10: Xử lý voided full refund lifetime bằng RTDN (Y11)
- **Vấn đề trước sửa:**
  - Khi Google gửi `voidedPurchaseNotification` cho inapp product (lifetime VIP), `rtdnHandler.ts` bỏ qua và trả về `Non-billing notification noted`, dẫn tới quyền lợi trọn đời không bao giờ bị thu hồi sau khi hoàn tiền.
- **Giải pháp:**
  - Mở rộng schema `DeveloperNotificationPayload` hỗ trợ `voidedPurchaseNotification`.
  - Trích xuất token, kiểm tra event time, tra cứu quyền sở hữu trong SQLite store.
  - Với full refund (`refundType === 1`), xác thực với Google Play authority và chuyển trạng thái entitlement thành `REVOKED`, cập nhật snapshot version và watermark sự kiện.
- **Probes chứng minh:**
  - **B812 PASS:** Google voided full refund envelope thu hồi ngay lập tức (`REVOKED`) quyền lợi trọn đời.
  - **B813 PASS (đối chứng):** Restore tài khoản thông thường cũng phát hiện trạng thái đã hoàn tiền và thu hồi tương ứng.
  - Các biến thể duplicate (idempotent), stale event (blocked), unknown token (safe) đều đạt 100%.

---

## 3. Ma Trận Nghiệm Thu External Gates (NOT_RUN / BLOCKED_EXTERNAL)

Các hạng mục dưới đây được phân định rõ ràng giữa **Host-Proven** (đã kiểm chứng 100% tự động trên host) và **External Gates** (cần môi trường thực tế khi triển khai production):

| Hạng mục kiểm thử | Host Environment | External Production Gate | Điều kiện thực tế cần thiết |
|---|---|---|---|
| **Google Cloud PubSub Push** | Giả lập HTTP POST push payload base64: **PASS** | `NOT_RUN / BLOCKED_EXTERNAL` | Topic Google Cloud PubSub thực tế + Push Subscription trỏ tới public HTTPS webhook |
| **Google Play Billing API Auth** | Mock & Synthetic Service Account: **PASS** | `NOT_RUN / BLOCKED_EXTERNAL` | Service Account JSON thực tế có quyền `View Financial Data` trên Google Play Console |
| **In-app Purchase Real Flow** | FakeBillingClient & BillingFlowParams: **PASS** | `NOT_RUN / BLOCKED_EXTERNAL` | Thiết bị Android vật lý đăng nhập tài khoản License Tester trên Google Play Store |
| **Real Google Sign-In & Token Expiry** | Synthetic RS256 JWT & Mock Credential Manager: **PASS** | `NOT_RUN / BLOCKED_EXTERNAL` | Google Cloud OAuth Client ID thực tế + tài khoản Google thật trên thiết bị |
| **Play Voided Purchase Real RTDN** | Mock Voided Envelope & Authority cancel: **PASS** | `NOT_RUN / BLOCKED_EXTERNAL` | Thực hiện Refund thủ công một đơn hàng thật qua Google Play Console Order Management |

---

## 4. Hướng Dẫn Kiểm Thử Thủ Công Khi Có Môi Trường Thật

1. **Kiểm tra luồng Nâng cấp VIP (Purchase Flow):**
   - Đăng nhập tài khoản Google trên thiết bị. Mở paywall -> chọn gói Tháng hoặc Năm -> Hoàn tất thanh toán trên Google Play bottom sheet.
   - Kỳ vọng: App hiển thị thông báo nâng cấp thành công ngay lập tức, chuyển trạng thái VIP trên thanh tiêu đề và ẩn toàn bộ watermark/quảng cáo.
2. **Kiểm tra luồng Khôi phục khi chuyển máy (Restore Flow):**
   - Cài app trên máy thứ hai -> Đăng nhập cùng tài khoản Google -> Nhấn "Khôi phục giao dịch mua".
   - Kỳ vọng: App liên hệ server và khôi phục quyền lợi VIP đầy đủ (`Khôi phục thành công: Đã kích hoạt quyền VIP`).
3. **Kiểm tra luồng Hoàn tiền (Refund / Voided Flow):**
   - Vào Google Play Console -> Order Management -> Tìm đơn hàng Lifetime VIP vừa mua -> Bấm "Refund".
   - Chờ trong vòng 1-2 phút để Google Cloud PubSub đẩy `voidedPurchaseNotification` về backend server.
   - Kỳ vọng: Server cập nhật quyền lợi thành `REVOKED`. Lần mở app tiếp theo hoặc khi app đồng bộ ngầm, quyền VIP trọn đời bị thu hồi sạch sẽ.
4. **Kiểm tra luồng Đăng nhập lại khi Token hết hạn (Auth Recovery Flow):**
   - Để app qua thời hạn token hoặc chủ động xóa cache đăng nhập -> Nhấn Mua VIP.
   - Kỳ vọng: Dialog yêu cầu đăng nhập hiển thị -> Người dùng chọn tài khoản Google -> Sau khi đăng nhập xong, bottom sheet mua hàng Google Play tự động bật lên mà không cần người dùng bấm lại nút Mua.

---

## 5. Kết Luận Nghiệm Thu
Toàn bộ 10 nhóm khiếm khuyết **G01–G10** đã được khắc phục hoàn chỉnh, triệt để và an toàn. Hệ thống đạt 100% tiêu chí chấp nhận trên host, sẵn sàng cho giai đoạn phát hành và kiểm thử external trên thiết bị thực tế.
