# BÁO CÁO CÔNG VIỆC AGENT B (AGENT 2)
**Kế hoạch:** `PLAN_FILES_VIP_TWO_AGENTS_2026-09-24.md`  
**Gói công việc thực hiện:** B1 → B4  
**Ngày hoàn thành:** 24/09/2026  
**Trạng thái:** HOÀN THÀNH — SẴN SÀNG CHO BƯỚC KIỂM THỬ TÍCH HỢP  

---

## 1. Danh sách các file đã chỉnh sửa

Agent B tuân thủ nghiêm ngặt quy tắc chỉ sửa các file được phân quyền, không can thiệp vào các file của Agent A hoặc các file hệ thống khác:

| STT | File | Mục đích thay đổi |
|---|---|---|
| 1 | `app/src/main/res/layout/fragment_home.xml` | Chuyển `btn_vip_home` thành `FrameLayout` chứa 2 trạng thái: `iv_vip_home_badge` (icon VIP, mặc định GONE) và `tv_free_home_badge` (nhãn FREE, mặc định VISIBLE). |
| 2 | `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt` | Thêm hàm `renderVipStatus()`, tích hợp kiểm tra `isVipActive`, tự động lên lịch coroutine cập nhật khi hết hạn gói VIP, gọi tại `onViewCreated()`, `onResume()`, observer `currentUser`, và dọn dẹp job tại `onDestroyView()`. |
| 3 | `app/src/main/res/layout/dialog_vip_upgrade.xml` | Đặt `android:visibility="gone"` cho tiêu đề roadmap (`vip_advanced_roadmap_title`), card `card_tier_vip_pro` và `card_tier_vip_promax`; giữ nguyên ID của các card. |
| 4 | `app/src/main/res/values/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active`. |
| 5 | `app/src/main/res/values-vi/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Việt). |
| 6 | `app/src/main/res/values-es/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Tây Ban Nha). |
| 7 | `app/src/main/res/values-pt/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Bồ Đào Nha). |
| 8 | `app/src/main/res/values-fr/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Pháp). |
| 9 | `app/src/main/res/values-in/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Indonesia). |
| 10 | `app/src/main/res/values-de/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Đức). |
| 11 | `app/src/main/res/values-ja/strings.xml` | Cập nhật `vip_perk_4`; thêm `vip_badge_free`, `vip_home_desc_free`, `vip_home_desc_active` (tiếng Nhật). |

*(Ghi chú: File `VipUpgradeDialog.kt` được giữ nguyên không chỉnh sửa vì các ID card được giữ nguyên và ẩn an toàn bằng `android:visibility="gone"` trong XML, giúp bảo toàn toàn vẹn luồng Auth và Google Drive).*

---

## 2. Chi tiết thực hiện theo từng gói công việc

### B1. Tạo hai trạng thái tại Trang chủ (`fragment_home.xml`)
- Chuyển `btn_vip_home` từ `ImageView` thành container `FrameLayout`:
  - `layout_width="wrap_content"`, `minWidth="48dp"`, `layout_height="40dp"`, `layout_marginStart="10dp"`.
  - `clickable="true"`, `focusable="true"`, `background="?attr/selectableItemBackgroundBorderless"`.
  - `contentDescription="@string/vip_home_desc_free"` (mặc định cho Free).
- Bên trong chứa 2 View con loại trừ nhau:
  1. `ImageView` id `@+id/iv_vip_home_badge`: icon `@drawable/ic_vip`, 40x40dp, padding 6dp, `layout_gravity="center"`, `importantForAccessibility="no"`, `visibility="gone"`.
  2. `TextView` id `@+id/tv_free_home_badge`: hiển thị chữ "FREE" qua `@string/vip_badge_free`, height 28dp, paddingHorizontal 10dp, `gravity="center"`, `layout_gravity="center"`, `background="@drawable/bg_card_rounded"`, `textColor="@color/primary_teal"`, `textSize="12sp"`, `textStyle="bold"`, `importantForAccessibility="no"`, `visibility="visible"`.
- Trạng thái khởi tạo XML đảm bảo không bao giờ bị "nháy" icon VIP trước khi dữ liệu tài khoản được nạp.

### B2. Cập nhật theo trạng thái thật trong `HomeFragment.kt`
- Thêm trường `private var vipExpiryJob: Job? = null` để quản lý coroutine hẹn giờ hết hạn VIP.
- Cài đặt hàm `renderVipStatus()`:
  - Kiểm tra an toàn `_binding` và `isAdded`.
  - Lấy `val currentUser = AppAuthManager.getCurrentUser()`.
  - Kiểm tra `val isVip = currentUser?.isVipActive == true` (dựa trên thuộc tính `isVipActive` đã hỗ trợ đầy đủ các tier VIP / PRO / PRO MAX còn hạn).
  - Luôn hủy job hẹn giờ cũ trước khi xử lý trạng thái mới (`vipExpiryJob?.cancel()`, `vipExpiryJob = null`).
  - Nếu `isVip == true`:
    - `iv_vip_home_badge` -> VISIBLE, `tv_free_home_badge` -> GONE.
    - `btn_vip_home.contentDescription = getString(R.string.vip_home_desc_active)`.
    - Nếu có `vipExpiresAt`, tính độ trễ `delayMs = expiresAt - System.currentTimeMillis() + 100L`. Nếu `delayMs > 0`, khởi tạo coroutine job trên `viewLifecycleOwner.lifecycleScope` để tự động kích hoạt lại `renderVipStatus()` khi hết hạn VIP ngay cả khi màn hình Home vẫn đang mở.
  - Nếu `isVip == false` (Free, khách chưa đăng nhập, hoặc VIP đã hết hạn):
    - `iv_vip_home_badge` -> GONE, `tv_free_home_badge` -> VISIBLE.
    - `btn_vip_home.contentDescription = getString(R.string.vip_home_desc_free)`.
- Điểm gọi hàm `renderVipStatus()`:
  1. `onViewCreated()`: hiển thị đúng ngay khi view được tạo.
  2. `onResume()`: kiểm tra lại mỗi khi người dùng quay lại tab Trang chủ hoặc ứng dụng từ nền.
  3. Observer `AppAuthManager.currentUser`: cập nhật tức thì ngay khi đăng nhập, đăng xuất, hoặc hoàn tất nâng cấp gói VIP trong dialog mà không cần đổi tab.
- Xử lý vòng đời (`onDestroyView()`):
  - Hủy coroutine job `vipExpiryJob?.cancel()`, gán `vipExpiryJob = null`.
  - Gán `_binding = null` theo đúng quy chuẩn tránh rò rỉ bộ nhớ.
- Giữ nguyên listener mở `VipUpgradeDialog`:
  - `binding.btnVipHome.setOnClickListener { VipUpgradeDialog(requireContext()).show() }`.

### B3. Ẩn gói và sửa mô tả
1. **Trong `dialog_vip_upgrade.xml`:**
   - Ẩn tiêu đề lộ trình roadmap: `android:visibility="gone"` cho TextView `@string/vip_advanced_roadmap_title`.
   - Ẩn 2 card: `android:visibility="gone"` cho `card_tier_vip_pro` và `card_tier_vip_promax`.
   - Giữ nguyên ID của 2 card để `DialogVipUpgradeBinding` và các listener trong `VipUpgradeDialog.kt` không phát sinh lỗi tham chiếu.
   - Khoảng cách giữa nút nâng cấp và nút Đóng được co gọn sạch sẽ, không để lại khoảng trắng thừa.
2. **Cập nhật nội dung chuỗi ký tự trên cả 8 ngôn ngữ:**
   - `vip_perk_4`:
     - values: `✓ Remove T-Scanner watermark when exporting files`
     - values-vi: `✓ Xóa đóng dấu T-Scanner trên văn bản khi xuất file`
     - values-es: `✓ Eliminar marca de agua de T-Scanner al exportar archivos`
     - values-pt: `✓ Remover marca d\'água do T-Scanner ao exportar arquivos`
     - values-fr: `✓ Supprimer le filigrane T-Scanner lors de l\'exportation de fichiers`
     - values-in: `✓ Hapus tanda air T-Scanner pada dokumen saat mengekspor file`
     - values-de: `✓ T-Scanner-Wasserzeichen beim Exportieren von Dateien entfernen`
     - values-ja: `✓ ファイル出力時にT-Scannerの透かしを削除`
   - `vip_badge_free`: giá trị `FREE` đồng nhất trên cả 8 locale (không dịch tên nhãn gói).
   - `vip_home_desc_free` và `vip_home_desc_active`: mô tả hỗ trợ tiếp cận (TalkBack) chuẩn hóa cho 8 ngôn ngữ, phân biệt rõ ràng trạng thái tài khoản Miễn phí và thành viên VIP.

### B4. Kiểm tra và bàn giao
- Kiểm tra toàn bộ git diff của Agent B đảm bảo tính tối giản, không ảnh hưởng đến các thay đổi sẵn có của workspace.
- Đảm bảo tuân thủ tuyệt đối các quy tắc:
  - Không chạy lệnh Gradle (để người điều phối thực hiện kiểm thử tích hợp).
  - Không chạy git reset, restore, clean, stash.
  - Không sửa các file ngoài danh mục được phân quyền (không sửa MoreFragment, FilesFragment, v.v.).

---

## 3. Khuyến nghị cho Người điều phối (Bước 5 kiểm thử tích hợp)
Sau khi Agent A hoàn tất các gói A1 → A3, người điều phối có thể tiến hành chạy kiểm thử theo quy trình:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipManagerTest" --tests "com.tscanner.app.VipWatermarkPolicyTest" --tests "com.tscanner.app.MainActivityNavigationTest" --offline
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```
