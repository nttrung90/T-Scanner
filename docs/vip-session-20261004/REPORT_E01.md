# Báo cáo Bàn giao Gói E01 — Xác thực lại an toàn cho tài khoản đang đăng nhập

**Thời điểm:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói phụ trách:** E01 (Xác thực lại an toàn cho tài khoản đang đăng nhập)  
**Trạng thái:** HOÀN THÀNH (PASS)

---

## 1. Mục tiêu và Phạm vi gói E01

Thêm khả năng xác thực lại an toàn ngay trong `AppAuthManager` và `GoogleLoginAttempt` khi credential của tài khoản hiện tại hết hạn hoặc bị thiếu, mà **không** yêu cầu người dùng phải đăng xuất (`signOut`), không xóa dữ liệu người dùng/tài liệu/entitlement, và bảo đảm an toàn dữ liệu tuyệt đối:
- **Tài khoản đang đăng nhập (A):** Chỉ chấp nhận credential mới nếu thuộc về đúng chủ sở hữu A (`expectedOwnerId`).
- **Từ chối trước side-effect (A -> B):** Nếu người dùng chọn tài khoản B từ provider, kết quả bị từ chối **trước khi gọi `processSignedInAccountInternal`**, không migrate hay ghi đè profile của A, giữ nguyên phiên của A và thông báo rõ ràng cho người dùng.
- **Credential hợp lệ:** Phải có ID token không rỗng và chưa hết hạn (`!PlayPurchaseVerifier.isTokenExpired`). Nếu token mới hết hạn/thiếu, từ chối trước commit.
- **Tính nhất quán khi lưu trữ:** `saveUser` được kiểm tra kết quả lưu trữ; nếu persistence thất bại thì ném ngoại lệ rõ ràng, không coi là đăng nhập thành công.
- **Bảo toàn cơ chế concurrency:** Giữ vững debouncing (single in-flight sign-in), guard session generation, logout serialization, epoch check và Intent fallback.

---

## 2. Chi tiết thay đổi code production

### 2.1 `app/src/main/java/com/tscanner/app/utils/GoogleLoginAttempt.kt`
- Bổ sung trường `expectedOwnerId: String? = null` vào data class `GoogleLoginAttempt`.
- Hỗ trợ lưu/khôi phục từ `Bundle` (`KEY_EXPECTED_OWNER_ID = "expected_owner_id"`).
- Cập nhật hàm factory `fromValues` và `fromBundle` đảm bảo tương thích ngược 100%.

### 2.2 `app/src/main/java/com/tscanner/app/utils/AppAuthManager.kt`
- **Định nghĩa các ngoại lệ nghiệp vụ:**
  - `AuthReauthenticationException`: Exception cơ sở cho các lỗi reauth.
  - `AccountMismatchException`: Ném ra khi tài khoản trả về không khớp với chủ sở hữu dự kiến.
  - `ExpiredCredentialException`: Ném ra khi credential trả về bị thiếu hoặc đã hết hạn.
- **Thêm API `reauthenticateWithGoogle`:**
  - Nhận `expectedOwnerId: String`.
  - Không gọi `signOut`, không xóa profile, không xóa token cũ trước khi có kết quả mới.
  - Đưa `expectedOwnerId` vào `GoogleLoginAttempt`.
- **Cập nhật `signInWithGoogle`:**
  - Bổ sung tham số tùy chọn `expectedOwnerId: String? = null` ở cuối danh sách tham số (giữ nguyên thứ tự tham số positional cho các caller cũ).
- **Kiểm tra chặt chẽ trước `processSignedInAccountInternal` trong `commitSignedInAccount`:**
  - Kiểm tra token không rỗng và chưa hết hạn (`!PlayPurchaseVerifier.isTokenExpired`).
  - Nếu `attempt.expectedOwnerId` khác null: so sánh với `account.id`, `canonicalId` (từ sub của idToken) và `account.email`. Nếu không khớp, từ chối ngay lập tức và ném `AccountMismatchException` trước khi bất kỳ thao tác migrate/claim tài liệu nào xảy ra.
- **Kiểm tra tính bền vững của lưu trữ:**
  - Trong `processSignedInAccountInternal`, kiểm tra giá trị trả về của `saveUser(context, profile)`. Nếu `false`, ném `IllegalStateException` ngăn chặn trạng thái session không nhất quán.
- **Hỗ trợ Test:**
  - Thêm `createReauthAttemptForTesting(expectedOwnerId: String)` và `postLoginHook` để cô lập I/O trong unit test.

---

## 3. Bộ kiểm thử mới: `AppAuthReauthenticationTest.kt`

Đã tạo bộ kiểm thử unit gồm 11 kịch bản toàn diện trong `app/src/test/java/com/tscanner/app/AppAuthReauthenticationTest.kt`:

| STT | Tên Test Case | Mục đích xác nhận | Kết quả |
|---|---|---|---|
| 1 | `testReauth_sameOwner_success_updatesCredentialAndPreservesVip` | Reauth A -> A với token mới thành công, cập nhật credential và giữ nguyên VIP/profile. | **PASS** |
| 2 | `testReauth_differentOwner_rejectedBeforeSideEffects` | Reauth A -> B bị từ chối trước commit, profile và VIP của A được bảo toàn nguyên vẹn. | **PASS** |
| 3 | `testReauth_expiredIncomingToken_rejectedBeforeCommit` | Token từ provider vẫn bị hết hạn (`exp` đã qua) bị từ chối trước commit. | **PASS** |
| 4 | `testReauth_missingIncomingToken_rejectedBeforeCommit` | Token từ provider là null bị từ chối trước commit. | **PASS** |
| 5 | `testReauth_saveFailure_keepsConsistentUser` | Lỗi ghi SharedPreferences thất bại ném IllegalStateException, không báo thành công sai. | **PASS** |
| 6 | `testReauth_logoutWhileWaiting_staleAttemptRejected` | Session generation thay đổi trong lúc chờ provider làm attempt cũ bị hủy an toàn. | **PASS** |
| 7 | `testReauth_doubleTap_debounced` | Bấm reauth liên tiếp 2 lần bị debounce, lần thứ 2 trả về false không xung đột. | **PASS** |
| 8 | `testReauth_userCancellation_preservesUser` | Người dùng bấm hủy Credential Manager gọi `onCancelled`, giữ nguyên tài khoản A. | **PASS** |
| 9 | `testIntentFallback_sameOwner_success` | Luồng fallback sang classic Intent với cùng chủ sở hữu A cập nhật credential thành công. | **PASS** |
| 10 | `testIntentFallback_differentOwner_rejected` | Luồng fallback Intent chọn tài khoản B bị từ chối, bảo vệ chủ sở hữu A. | **PASS** |
| 11 | `testGuestLogin_withoutExpectedOwner_commitsNormally` | Đăng nhập Guest không truyền `expectedOwnerId` hoạt động bình thường, không hồi quy. | **PASS** |

---

## 4. Kết quả thực thi kiểm thử

Lệnh chạy:
```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests com.tscanner.app.AppAuthReauthenticationTest --console=plain
```
**Kết quả:** 11/11 tests completed, 0 failed, 0 errors. **BUILD SUCCESSFUL**.

Kiểm tra đối chứng không hồi quy:
```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests com.tscanner.app.VipPurchaseActionCoordinatorTest --tests com.tscanner.app.VipRound8RegressionTest --tests com.tscanner.app.AppAuthReauthenticationTest --console=plain
```
**Kết quả:** 39/39 tests completed, 0 failed, 0 errors. **BUILD SUCCESSFUL**.

Kiểm tra regression các vòng trước:
```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests com.tscanner.app.VipLoginRound2RegressionTest --tests com.tscanner.app.VipLoginRound4RegressionTest --console=plain
```
**Kết quả:** Hoàn thành, 0 lỗi. **BUILD SUCCESSFUL**.

---

## 5. Kết luận và Bàn giao sang Gói E02

- Gói E01 đã hoàn thành đạt 100% các tiêu chí an toàn đăng nhập lại.
- Sẵn sàng chuyển giao sang **Gói E02** (Xử lý Continuation Callback sau reauth, bảo toàn hành động `RESTORE` và `UPGRADE`, sửa F05).
