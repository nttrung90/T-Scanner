# Kế hoạch chuyển Quản lý tài liệu và điều chỉnh Free/VIP — 2 agent

Ngày nghiên cứu: 24/09/2026. Trạng thái: chỉ lập kế hoạch, chưa triển khai mã nguồn.

## 1. Phạm vi và quyết định giao diện

- Tab Tập tin: thứ tự thao tác là **Nhập tập tin → Nhập ảnh → Tạo thư mục → Quản lý tài liệu**. Mục cuối mở đúng DocumentManagementActivity hiện có.
- Tab Mở rộng: bỏ điểm mở Quản lý tài liệu và đường phân cách dư; giữ các mục khác.
- Trang chủ: tài khoản có `isVipActive == true` hiển thị biểu tượng VIP; Free, khách chưa đăng nhập và VIP hết hạn hiển thị chữ **FREE** ở cùng vị trí. Cả hai trạng thái vẫn bấm mở hộp thoại đặc quyền như hiện tại.
- Hộp thoại Đặc quyền VIP T-Scanner: tạm ẩn toàn bộ phần VIP PRO/VIP PRO MAX, cả tiêu đề lộ trình, nhãn sắp ra mắt và mô tả. Không xóa các tier khỏi mô hình tài khoản.
- Mô tả quyền VIP dùng câu **“✓ Xóa đóng dấu T-Scanner trên văn bản khi xuất file”**, cập nhật dòng watermark hiện có để không lặp hai quyền cùng nghĩa. Đây là bỏ dấu do ứng dụng thêm, không phải tính năng xóa con dấu có sẵn trong ảnh/tài liệu.
- Không đổi giá, thời hạn dùng thử, quy trình đăng nhập/cấp quyền Drive, chính sách watermark hoặc quyền lợi thực tế.

## 2. Bằng chứng từ checkout hiện tại

Các đường dẫn dưới đây tương đối với gốc repository; số dòng là mốc nghiên cứu và có thể đổi khi triển khai.

| Vị trí | Hiện trạng | Hệ quả triển khai |
|---|---|---|
| `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt:327` | `itemDocManagement` gọi `DocumentManagementActivity.start(requireContext())` | Chuyển điểm gọi; không cần viết lại Activity |
| `app/src/main/res/layout/fragment_more.xml:202` | Mục quản lý nằm giữa tài khoản và ngôn ngữ | Xóa đúng block và một separator dư |
| `app/src/main/res/layout/fragment_files.xml:55` và `:121` | Một hàng ngang 3 card, Tạo thư mục đứng cuối | Thêm thao tác thứ tư; kiểm tra chiều rộng và nhãn dài |
| `app/src/main/java/com/tscanner/app/ui/files/FilesFragment.kt:132` | Listener tạo thư mục đã độc lập | Thêm listener kế tiếp, giữ nguyên import/tạo thư mục |
| `app/src/main/res/layout/fragment_home.xml:59` | `btn_vip_home` luôn là ImageView chứa `ic_vip` | Cần hai trạng thái hiển thị loại trừ nhau |
| `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt:130,267,290` | onResume kiểm tra draft; nút mở VipUpgradeDialog; observer tài khoản chỉ refresh tài liệu | Bổ sung render trạng thái vào vòng đời và observer hiện có |
| `app/src/main/java/com/tscanner/app/data/model/UserProfile.kt` | `isVipActive` kiểm tra isVip, tier và hạn dùng | Dùng thuộc tính này; không chỉ so enum hoặc cờ isVip |
| `app/src/main/res/layout/dialog_vip_upgrade.xml:168,175,222` | Tiêu đề roadmap và hai card PRO đang hiển thị | Ẩn trọn phần, dùng GONE tránh khoảng trống |
| `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt:80` | Hai card PRO chỉ có listener báo sắp ra mắt | Có thể giữ ID và listener khi ẩn, tránh thay đổi không cần thiết |
| `app/src/main/res/values-vi/strings.xml:157` | `vip_perk_4` đã nói không có watermark khi xuất | Làm rõ câu chữ, không thêm quyền trùng |
| `app/src/main/java/com/tscanner/app/utils/WatermarkHelper.kt:23` | Chính sách bỏ dấu đã có cho VIP | Chỉ sửa mô tả, không can thiệp export |

Đây là kết luận qua đọc mã nguồn, chưa phải kết quả chạy trên thiết bị. Workspace có nhiều thay đổi staged/unstaged, bao gồm những file trong phạm vi này; phải dựa trên nội dung hiện tại, không khôi phục về HEAD.

## 3. Quyền sở hữu file — không giao nhau

### Agent 1 — A: Di chuyển mục Quản lý tài liệu

Chỉ được sửa 4 file:

1. `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`
2. `app/src/main/res/layout/fragment_more.xml`
3. `app/src/main/java/com/tscanner/app/ui/files/FilesFragment.kt`
4. `app/src/main/res/layout/fragment_files.xml`

Báo cáo riêng: `REPORT_FILES_VIP_AGENT_A.md`.

### Agent 2 — B: Trang chủ Free/VIP và hộp thoại đặc quyền

Chỉ được sửa:

1. `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`
2. `app/src/main/res/layout/fragment_home.xml`
3. `app/src/main/res/layout/dialog_vip_upgrade.xml`
4. `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt` — chỉ nếu cần cho phần ẩn; không sửa logic kích hoạt/Drive.
5. Các file `strings.xml` đang tồn tại tại `app/src/main/res/values/`, `values-vi/`, `values-es/`, `values-pt/`, `values-fr/`, `values-in/`, `values-de/`, `values-ja/`.

Báo cáo riêng: `REPORT_FILES_VIP_AGENT_B.md`.

**Quy tắc chung:** Agent A tái sử dụng `item_doc_management` và `ic_storage`, không sửa strings. Agent B không sửa MoreFragment dù file này cũng có giao diện VIP. Không agent nào sửa MainActivity, AppAuthManager, UserProfile, VipTier, repository, manifest, Gradle, watermark/export, tài liệu kế hoạch này hoặc file báo cáo của agent kia. Không chạy format toàn repo, git reset/restore/clean, stash hay commit gom toàn bộ. Nếu cần file ngoài danh sách, ghi blocker cho người điều phối, không tự mở rộng quyền sở hữu.

## 4. Trình tự thực hiện

### Bước 0 — Người điều phối, trước khi chạy song song

1. Chỉ bắt đầu triển khai sau khi người dùng duyệt kế hoạch.
2. Ghi nhận git status và diff hiện tại của các file liên quan, gồm cả staged/unstaged. Không ghi đè thay đổi có sẵn.
3. Đọc AGENTS.md áp dụng nếu có tại thời điểm thực hiện. Kiểm tra lại ID/resource trong kế hoạch còn khớp.
4. Giao danh sách file trên cho hai agent. Cùng workspace thì không chạy hai Gradle đồng thời: build output/cache dùng chung. Hai agent sửa và đọc kiểm tra độc lập; người điều phối chạy kiểm thử sau khi cả hai dừng ghi.

### Agent A — thực hiện tuần tự A1 → A3

**A1. Chuyển giao diện**

- Bỏ block `item_doc_management` trong Mở rộng và một separator tương ứng, không tạo hai đường kẻ liền nhau.
- Thêm card `card_doc_management` ngay sau `card_create_folder`, nhãn `@string/item_doc_management`, icon `@drawable/ic_storage`.
- Giữ phong cách card hiện tại. Dùng bốn card cùng trọng số và khoảng cách đều; nhãn được xuống dòng, căn giữa, chiều cao đủ cho nhãn ở font lớn. Không thu nhỏ chữ chỉ để ép vừa hàng.
- Nếu kiểm tra 320dp/font lớn không đạt: chuyển cả nhóm sang lưới 2×2, thứ tự đọc vẫn Nhập tập tin, Nhập ảnh, Tạo thư mục, Quản lý tài liệu. Chỉ đổi bố cục trong fragment_files.xml; ghi lý do trong báo cáo.

**A2. Chuyển hành vi**

- Bỏ listener `itemDocManagement` và import Activity không còn dùng khỏi MoreFragment.
- Thêm import và listener card mới trong FilesFragment gọi nguyên API `DocumentManagementActivity.start(requireContext())`.
- Không chuyển sang DocumentManagementDialog và không tạo Activity mới. Không thay đổi currentFolderId, bộ lọc, dữ liệu hoặc cơ chế refresh.

**A3. Kiểm tra và bàn giao**

- Tìm tham chiếu `itemDocManagement` để chắc không còn binding cũ trong MoreFragment.
- Kiểm tra XML, resource tồn tại và phạm vi diff chỉ nằm trong 4 file được cấp.
- Ghi báo cáo những gì đã sửa và những kiểm tra chưa chạy; dừng ghi trước khi điều phối build.

Tiêu chí nghiệm thu A: Mở rộng không còn mục; Tập tin có đúng một mục nằm sau Tạo thư mục; bấm mở đúng màn hình cũ; Back về Tập tin; ba thao tác cũ còn chạy; dữ liệu/tìm kiếm/thư mục không thay đổi bất ngờ; không tràn nhãn hoặc chồng card.

### Agent B — thực hiện tuần tự B1 → B4

**B1. Tạo hai trạng thái tại Trang chủ**

- Giữ `btn_vip_home` làm vùng bấm chung bằng container chứa icon VIP và TextView FREE; kiểm tra các tham chiếu để tránh giả định binding vẫn là ImageView. Giữ listener mở VipUpgradeDialog hiện tại.
- Dùng resource nhãn FREE (không dịch tên gói); thêm mô tả accessibility cho Free/VIP, vùng chạm tối thiểu 48dp nếu bố cục cho phép. Tránh TalkBack đọc trùng container và con.
- Mặc định XML hiển thị FREE, icon GONE để không lóe VIP khi chưa tải tài khoản. Khi render chỉ một phần tử hiện, phần còn lại GONE.

**B2. Cập nhật theo trạng thái thật**

- Tạo một hàm render nhỏ trong HomeFragment, lấy `AppAuthManager.getCurrentUser()?.isVipActive == true`.
- Gọi từ observer `currentUser` hiện có và onResume; giữ nguyên refreshRecentDocs/checkActiveDraft.
- Xử lý nâng cấp ngay khi dialog còn ở Home qua observer; không phụ thuộc chuyển tab mới cập nhật. Dùng viewLifecycleOwner, không giữ binding sau onDestroyView.
- Trường hợp để Home mở xuyên qua thời điểm hết hạn: nếu có hạn dùng, lên lịch render lại sau mốc hết hạn bằng job gắn vòng đời view, hủy/lập lại khi tài khoản đổi; khi resume luôn tính lại. Chú ý điều kiện hiện tại là `now <= vipExpiresAt`, tránh lặp job ngay tại mốc bằng nhau. Không tạo vòng polling liên tục, không sửa auth chỉ để cập nhật nhãn.
- VIP PRO/PRO MAX đang còn hiệu lực vẫn hiển thị icon VIP theo isVipActive. Việc ẩn gói mua không được hạ quyền tài khoản hiện hữu.

**B3. Ẩn gói và sửa mô tả**

- Đặt GONE cho hai card PRO và tiêu đề roadmap, hoặc bọc cả ba trong container GONE. Giữ ID hai card để binding/listener hiện có không lỗi; không dùng INVISIBLE.
- Giữ nguyên nút nâng cấp VIP và nút đóng, bảo đảm cuộn tới được trên màn hình nhỏ.
- Sửa `vip_perk_4` thành câu đã chốt ở mục 1; cập nhật bản dịch tương ứng cho 8 ngôn ngữ đang hỗ trợ. English dùng values mặc định, Indonesia dùng values-in. Không hồi sinh các locale đã xóa/đưa vào locale_archive.
- Nếu thêm string mới cho nhãn/accessibility, chỉ Agent B sở hữu các file strings. Không xóa string PRO còn được nơi khác tham chiếu.

**B4. Kiểm tra và bàn giao**

- Kiểm tra tất cả đường cập nhật: tài khoản null, Free, VIP còn hạn, hết hạn, đăng xuất, đổi tài khoản, nâng cấp tại Home, quay về từ nền, tái tạo view.
- Kiểm tra GONE không để hở tiêu đề/mô tả/khoảng trắng; không ảnh hưởng kích hoạt VIP và quyền Drive.
- Ghi báo cáo riêng, dừng ghi để điều phối build.

Tiêu chí nghiệm thu B: trạng thái FREE/VIP đúng và không cùng xuất hiện; cập nhật không cần khởi động lại; gói PRO/PRO MAX cùng toàn bộ thông tin không còn nhìn thấy hoặc focus được trong hộp thoại; dòng xóa đóng dấu xuất hiện một lần và dịch nhất quán; không thay đổi quyền export thực tế.

## 5. Kiểm tra tích hợp — sau khi hai agent hoàn tất

Người điều phối kiểm tra diff theo baseline bước 0, chỉ đánh giá phần mới; không lấy toàn bộ git diff làm thay đổi của hai agent.

Chạy từ gốc repository, tuần tự:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipManagerTest" --tests "com.tscanner.app.VipWatermarkPolicyTest" --tests "com.tscanner.app.MainActivityNavigationTest" --offline
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

Kiểm tra tên test còn tồn tại trước khi chạy. Nếu wrapper/cache khóa hoặc Access denied, xác định GRADLE_USER_HOME và quyền ghi; không kết luận lỗi mã app khi Gradle chưa chạy. Không xóa cache chung hoặc chạy build song song để xử lý.

Các test sẵn có bảo vệ auth/watermark/navigation, không chứng minh giao diện đã đúng. Không tạo test đọc chuỗi mã nguồn chỉ để xác nhận XML. Thay đổi UI nhỏ này ưu tiên build/lint và kiểm thử hành vi thực tế sau:

| Tình huống | Kết quả mong đợi |
|---|---|
| Khởi động chưa đăng nhập / tài khoản Free | Home hiện FREE, bấm vẫn mở đặc quyền |
| VIP còn hạn / PRO hoặc PRO MAX còn hạn | Home hiện icon VIP |
| Nâng cấp tại Home | FREE đổi sang icon sau khi trạng thái tài khoản được phát |
| Đăng xuất hoặc đổi VIP sang Free tại Mở rộng rồi về Home | FREE, không giữ icon cũ |
| VIP hết hạn khi app ở nền hoặc Home đang mở | Chuyển FREE, không phải mở lại app |
| Xoay màn hình / tái tạo view / chuyển tab nhiều lần | Không crash binding, không nhân observer/job |
| Mở hộp thoại từ Home và Mở rộng | Chỉ phần VIP hiện tại; không PRO, PRO MAX hay tiêu đề roadmap |
| Tab Tập tin rỗng và có dữ liệu; đang ở thư mục con | Mục quản lý mở đúng Activity; Back và 3 thao tác cũ hoạt động |
| Màn hình 320dp và 360dp, font 1.0 và 1.3–1.5, tiếng Việt/Đức/Nhật | Nhãn đọc đủ, không chồng, nút đóng truy cập được |
| Xuất tài liệu ở Free/VIP theo lựa chọn bỏ dấu hiện hữu | Chính sách giữ/bỏ dấu không bị thay đổi bởi chỉnh UI |

Lưu ảnh trước/sau các màn hình và ghi rõ thiết bị/cấu hình đã kiểm tra. Không có thiết bị/emulator thì ghi “chưa nghiệm thu runtime”, không tuyên bố hoàn tất toàn bộ dựa trên build.

## 6. Prompt bàn giao cho mô hình nhỏ

### Prompt Agent A

> Đọc PLAN_FILES_VIP_TWO_AGENTS_2026-09-24.md, thực hiện gói A1–A3 sau khi kế hoạch được người dùng duyệt. Chỉ sửa 4 file được cấp cho Agent A. Chuyển Quản lý tài liệu từ Mở rộng sang sau Tạo thư mục tại Tập tin, giữ nguyên Activity và hành vi quản lý. Tái sử dụng string/icon; không sửa bất kỳ file strings hoặc file của Agent B. Giữ thay đổi có sẵn trong working tree. Không chạy Gradle trong khi Agent B đang ghi file. Bàn giao REPORT_FILES_VIP_AGENT_A.md gồm file đã sửa, kiểm tra đã làm, thiếu sót và blocker. Cần file ngoài phạm vi thì dừng phần đó và báo điều phối; không tự sửa.

### Prompt Agent B

> Đọc PLAN_FILES_VIP_TWO_AGENTS_2026-09-24.md, thực hiện gói B1–B4 sau khi kế hoạch được người dùng duyệt. Chỉ sửa file thuộc Agent B. Home hiện icon cho isVipActive, còn lại hiện FREE, cập nhật đúng vòng đời/tài khoản/hết hạn. Ẩn trọn phần PRO/PRO MAX của hộp thoại, làm rõ vip_perk_4 về xóa đóng dấu T-Scanner và cập nhật 8 ngôn ngữ hiện hữu. Không sửa MoreFragment/FilesFragment, auth, tier, Drive, chính sách watermark, giá hoặc kích hoạt. Giữ thay đổi có sẵn. Không chạy Gradle trong khi Agent A đang ghi. Bàn giao REPORT_FILES_VIP_AGENT_B.md và báo điều phối khi cần file ngoài phạm vi.

## 7. Trạng thái nghiên cứu

Đã đọc mã giao diện, listener, nguồn trạng thái tài khoản, policy watermark và cấu hình locale. Chưa sửa mã nguồn, chưa chạy build/test/lint hoặc kiểm thử thiết bị trong lượt lập kế hoạch này. Chỉ tạo tài liệu kế hoạch; hai agent triển khai chưa được khởi chạy.
