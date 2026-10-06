# Báo Cáo Kiểm Thử & Bàn Giao Gói U03 (K03: Navigation Guest Phải Cùng Session)

**Ngày thực hiện:** 05/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** U03 (Theo `PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khắc phục khiếm khuyết **K03**: Điều hướng bắt đầu ở guest chưa kiểm tra generation (`MoreFragment.kt:692` chỉ so generation khi `originOwnerId != null`). Khi guest gốc generation 1 đăng nhập rồi đăng xuất chuyển sang guest generation 3, request cũ vẫn bị coi là Accept thay vì Discard.
  - So sánh generation cho cả guest và tài khoản đã đăng nhập (`originGeneration != -1L && originGeneration != currentGeneration` -> Discard).
  - Loại bỏ request guest cũ nếu người dùng đã đăng nhập thành tài khoản A trước khi tiêu thụ (`originOwnerId == null && currentOwnerId != null` -> Discard).
  - Phân biệt rõ thời điểm: thẩm tra envelope điều hướng (`VipNavigationValidator`) diễn ra trước khi bắt đầu login; hoàn toàn độc lập với `VipLoginContinuationHandler` (nơi xử lý chuyển phiên hợp lệ G -> G+1 sau khi attempt đăng nhập thành công).

---

## 2. Thay Đổi Mã Nguồn Sản Xuất

1. **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` (`VipNavigationValidator`):**
   - Bổ sung quy tắc loại bỏ khi request khởi tạo từ guest nhưng hiện tại đã có tài khoản:
     ```kotlin
     if (originOwnerId == null && currentOwnerId != null) {
         return NavigationDecision.Discard("Request originated for guest but current user is authenticated ($currentOwnerId)")
     }
     ```
   - Áp dụng kiểm tra generation cho tất cả trường hợp (cả guest lẫn authenticated user):
     ```kotlin
     if (originGeneration != -1L && originGeneration != currentGeneration) {
         return NavigationDecision.Discard("Session generation mismatch (origin=$originGeneration, current=$currentGeneration)")
     }
     ```

---

## 3. Bằng Chứng Kiểm Thử (Evidence)

- **Lệnh thực thi:**
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'; $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound4ProbeTest.P05*' --tests 'com.tscanner.app.VipSessionRound4ProbeTest.C02*' --console=plain
  ```
- **Kết quả: 2/2 PASSED (BUILD SUCCESSFUL)**:
  - `P05_oldGuestNavigationMustNotRunInNewGuestSession`: **PASSED** (Guest generation 1 -> login -> logout sang guest generation 3 trả về `NavigationDecision.Discard`).
  - `C02_matchingGuestNavigationIsAccepted`: **PASSED** (Guest cùng generation và cùng epoch được chấp nhận: `NavigationDecision.Accept`).
- **Tổng 7 Probes Vòng 4 (`VipSessionRound4ProbeTest`): 7/7 PASSED (100%)**.

---

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí gói U03: **ĐẠT (PASS)**.
- Tự động chuyển sang gói **U04** để thực hiện tổng kiểm chứng, hồi quy toàn diện, lint, build và đánh giá device gate.
