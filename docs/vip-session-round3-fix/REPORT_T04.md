# Báo cáo kiểm thử & Bàn giao Gói T04 (H04: Điều Hướng Auth Có Origin, Tiêu Thụ Một Lần)

Ngày thực hiện: 05/10/2026.
Workspace: `E:\DU AN AI/T-Scanner`

## 1. Mục tiêu và Phạm vi
- Khắc phục khiếm khuyết H04: Điều hướng auth xuyên màn hình (`HomeFragment` -> `MainActivity` -> `MoreFragment`) thiếu envelope nguồn gốc và cơ chế xác thực an toàn:
  1. Envelope tối thiểu được bổ sung: `operationId`, `action`, `originOwnerId`, `originGeneration`, `processEpoch`, `authRequiredReason`, `forceReauth`.
  2. Bắt buộc xác thực origin trước mọi thao tác UI/provider/restore tại điểm tiêu thụ (`MoreFragment`):
     - Origin A -> Current A (khớp generation, epoch, chưa consume): Nhận đúng 1 lần, đánh dấu `consumed`.
     - Origin A -> Current null (người dùng đã logout): Bỏ request, tuyệt đối không chuyển thành đăng nhập guest mới.
     - Origin A -> Current B hoặc A -> logout -> A với generation khác: Bỏ request.
     - Guest -> Guest cùng session/epoch: Nhận đăng nhập guest bình thường.
     - Replay (operation đã consume) hoặc epoch khác (sau khi app restart): Bỏ request.
  3. Thống nhất logic xác thực trong production validator `VipNavigationValidator` và cập nhật test `MainActivityNavigationTest` sử dụng validator production thay cho lambda giả lập.

## 2. Bằng chứng kiểm thử trước và sau sửa đổi
- **Trước sửa đổi:**
  - `MoreFragment:83` chỉ kiểm tra mismatch khi `user != null`. Nếu người dùng đã logout (`user == null`), request của owner A bị lọt qua và đối xử như đăng nhập guest mới.
  - `operationId` được truyền nhưng không bao giờ lưu vết tiêu thụ; replay Bundle sẽ kích hoạt lại hành động.
  - Thiếu `originGeneration` và `processEpoch`, cho phép request cũ thực thi sau khi đã đổi session hoặc restart process.
  - `MainActivityNavigationTest` dùng map và lambda cục bộ, bỏ sót case `expected A / current null`.
- **Sau sửa đổi (T04):**
  - Production validator `VipNavigationValidator` hiện thực hóa đầy đủ bảng quyết định hợp đồng (H04).
  - `MoreFragment` lưu vết `consumedOperationIds` và thực hiện kiểm tra `VipNavigationValidator.validateNavigation` trước khi bắt đầu bất kỳ hành động nào.
  - `MainActivityNavigationTest.testReauthDecisionLogic_rejectsMismatchedOwnerAndStaleLogout`: **PASS** (kiểm chứng cả 7 tình huống trong bảng hợp đồng).
  - Kiểm thử tích hợp `VipSessionRound3IntegrationTest.navigationFromOwnerARejectedAfterLogoutAndOnReplay_contractSpecification`: **PASS** (chặn sau logout, chặn replay, chặn đổi owner, chặn epoch mismatch).
  - Toàn bộ 7/7 Round 3 probes (`VipSessionRound3ProbeTest`): **PASS** (BUILD SUCCESSFUL).

## 3. Danh sách file thay đổi
- `app/src/main/java/com/tscanner/app/MainActivity.kt`:
  - Thêm các tham số `originGeneration`, `processEpoch` vào `navigateToMoreForVipSignIn` và đưa vào fragment result Bundle.
- `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`:
  - Trong `onRequestSignIn` và `onRequestSignInForAction`, thu thập snapshot `currentUser?.id`, `getSessionGeneration()`, `getProcessEpoch()`, tạo `operationId` và truyền qua `navigateToMoreForVipSignIn`.
- `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:
  - Khai báo các hằng số Bundle `EXTRA_ORIGIN_GENERATION`, `EXTRA_PROCESS_EPOCH` và tập hợp `consumedOperationIds`.
  - Định nghĩa `NavigationDecision` và `VipNavigationValidator.validateNavigation`.
  - Trong `onCreate` `setFragmentResultListener`: xác thực envelope qua `VipNavigationValidator`; nếu `Discard` thì log warning và return ngay lập tức; nếu `Accept` thì lưu vết `operationId` vào `consumedOperationIds`.
- `app/src/test/java/com/tscanner/app/MainActivityNavigationTest.kt`:
  - Cập nhật test case gọi trực tiếp production `VipNavigationValidator` phủ toàn bộ 7 tình huống.
- `app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`:
  - Triển khai test contract 3 kiểm chứng loại bỏ stale navigation sau logout và khi replay.

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí nghiệm thu của T04: **ĐẠT (PASS)**.
- Chuyển sang thực hiện T05 (Tổng kiểm chứng, báo cáo và device gate).
