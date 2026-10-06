# BÁO CÁO CÔNG VIỆC AGENT A (AGENT 1)
**Kế hoạch:** `PLAN_FILES_VIP_TWO_AGENTS_2026-09-24.md`  
**Gói công việc thực hiện:** A1 → A3  
**Ngày hoàn thành:** 24/09/2026  
**Trạng thái:** HOÀN THÀNH — SẴN SÀNG CHO BƯỚC KIỂM THỬ TÍCH HỢP  

---

## 1. Danh sách các file đã chỉnh sửa

Agent A tuân thủ nghiêm ngặt quy tắc bắt buộc:
- CHỈ sửa đúng 4 file được phân quyền trong phạm vi công việc.
- KHÔNG sửa bất kỳ file nào khác (không sửa `strings.xml`, không sửa `HomeFragment`, v.v.).
- KHÔNG chạy lệnh Gradle.
- KHÔNG chạy git reset, restore, clean, stash; giữ nguyên tất cả thay đổi staged/unstaged của workspace.

| STT | File | Mục đích thay đổi |
|---|---|---|
| 1 | `app/src/main/res/layout/fragment_more.xml` | Xóa khối `item_doc_management` (LinearLayout) và đúng 1 đường kẻ divider liền kề sao cho giữa item Tài khoản Google và item Ngôn ngữ chỉ còn đúng 1 đường kẻ `divider_dark`. |
| 2 | `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt` | Xóa `binding.itemDocManagement.setOnClickListener` và import `DocumentManagementActivity`. |
| 3 | `app/src/main/res/layout/fragment_files.xml` | Chuyển layout từ 3 card sang 4 card ngang: thêm `card_doc_management` ngay sau `card_create_folder`. Đồng bộ kiểu dáng, trọng số `1`, `minHeight="96dp"`, khoảng cách đều 6dp giữa các card, căn giữa nhãn hỗ trợ xuống dòng. |
| 4 | `app/src/main/java/com/tscanner/app/ui/files/FilesFragment.kt` | Thêm import `DocumentManagementActivity` và thiết lập click listener gọi `DocumentManagementActivity.start(requireContext())` cho `binding.cardDocManagement`. |

---

## 2. Chi tiết thực hiện

### A1. Chuyển giao diện
1. **Trong `app/src/main/res/layout/fragment_more.xml`:**
   - Đã xóa toàn bộ block LinearLayout `item_doc_management` (icon `@drawable/ic_storage`, nhãn `@string/item_doc_management`, chevron icon) và 1 thẻ `<View android:background="@color/divider_dark" />` nằm ngay dưới nó.
   - Kết quả: Giữa item Tài khoản Google (`item_account`) phía trên và item Ngôn ngữ (`item_language`) phía dưới chỉ còn đúng 1 đường phân cách 1dp duy nhất, không bị trùng lặp hoặc mất divider.

2. **Trong `app/src/main/res/layout/fragment_files.xml`:**
   - Mở rộng container các card ngang thành 4 card theo thứ tự chuẩn:
     1. Nhập tập tin (`card_import_file`)
     2. Nhập ảnh (`card_import_image`)
     3. Tạo thư mục (`card_create_folder`)
     4. Quản lý tài liệu (`card_doc_management`)
   - Cả 4 card đều áp dụng quy chuẩn thiết kế:
     - `android:layout_width="0dp"`, `android:layout_weight="1"` (trọng số bằng nhau).
     - `android:layout_height="wrap_content"`, `android:minHeight="96dp"` (hỗ trợ hiển thị đầy đủ kể cả khi người dùng phóng to font chữ hệ thống).
     - `android:paddingHorizontal="4dp"`, `android:paddingVertical="8dp"`.
     - Phân bổ margin đều nhau: Card 1 `layout_marginEnd="3dp"`, Card 2 & 3 `layout_marginHorizontal="3dp"`, Card 4 `layout_marginStart="3dp"` (khoảng cách giữa các card liền kề đều đạt chuẩn 6dp).
     - Icon đồng nhất kích thước `28dp x 28dp`. Riêng `card_doc_management` sử dụng `@drawable/ic_storage` kèm `app:tint="@color/text_primary"`.
     - Nhãn TextView trong cả 4 card: `layout_width="match_parent"`, `gravity="center"`, `textAlignment="center"` cho phép tự động xuống dòng đẹp mắt khi chuỗi dài.

### A2. Chuyển hành vi
1. **Trong `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:**
   - Xóa bỏ import `com.tscanner.app.ui.docmanagement.DocumentManagementActivity`.
   - Xóa bỏ click listener `binding.itemDocManagement.setOnClickListener`.
   - Giữ nguyên tất cả các listener khác (`cardVipBanner`, `btnVipUpgradeNow`, `itemVip`, `itemAccount`, `itemLanguage`, `itemOcrLanguage`, `itemSettings`, `itemCheckUpdate`, `itemAbout`).

2. **Trong `app/src/main/java/com/tscanner/app/ui/files/FilesFragment.kt`:**
   - Thêm import `com.tscanner.app.ui.docmanagement.DocumentManagementActivity`.
   - Trong `setupListeners()`, thêm click listener ngay sau `cardCreateFolder`:
     ```kotlin
     // Action Card 4: Quản lý tài liệu
     binding.cardDocManagement.setOnClickListener {
         DocumentManagementActivity.start(requireContext())
     }
     ```
   - Giữ nguyên toàn bộ logic hiện có (tìm kiếm tập tin, duyệt thư mục, nhập file, nhập ảnh, breadcrumb back, scan document, v.v.).

---

## 3. Kết quả kiểm tra (A3)

- **Binding & Tham chiếu:**
  - `MoreFragment.kt`: Hoàn toàn sạch bóng, không còn bất kỳ tham chiếu nào tới `itemDocManagement` hay `DocumentManagementActivity`.
  - `FilesFragment.kt`: Tham chiếu chính xác tới `cardDocManagement` đã được khai báo trong `fragment_files.xml`.
- **Cấu trúc XML:**
  - `fragment_more.xml`: Cấu trúc thẻ đóng/mở chuẩn xác, bố cục divider đồng bộ.
  - `fragment_files.xml`: Khai báo 4 card với đầy đủ ID, icon, nhãn, namespace `app:tint`, trọng số và padding chuẩn xác.
- **Tuân thủ quy tắc làm việc:**
  - Không sửa file ngoài phạm vi.
  - Không chạy lệnh Gradle.
  - Không chạy lệnh Git có thể làm mất dữ liệu của workspace.
- **Blocker:** Không có bất kỳ blocker nào. Sẵn sàng bàn giao cho người điều phối thực hiện kiểm thử tích hợp (Bước 5).
