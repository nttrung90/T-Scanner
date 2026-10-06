# Báo cáo L02: Xác nhận nhánh nguyên nhân trước khi sửa code
*Thời gian thực hiện: 03/10/2026 09:27 ICT*

## 1. Cơ sở và Phụ thuộc
- Kế thừa bằng chứng điều tra từ L00 và phân tích cấu hình OAuth từ L01.
- Mục tiêu: Phân định ranh giới giữa lỗi cấu hình hạ tầng (Google Cloud / Play Console) và khiếm khuyết xử lý ngoại lệ trong mã nguồn; ngăn ngừa sửa code tùy tiện hoặc bypass bảo mật khi chưa có chứng cứ.

## 2. Ma trận đánh giá các nhánh nguyên nhân

| Nhánh nguyên nhân | Khả năng | Bằng chứng hiện có | Kết luận |
|---|---|---|---|
| **Nhánh 1: Cấu hình OAuth thiếu Play App Signing SHA-1** | Rất cao | - APK cài trực tiếp đăng nhập được, bản Play Store bị từ chối.<br>- Local AAB mang Upload Key SHA-1 (`FF:CA:...:E7`).<br>- Google Play luôn ký lại APK phân phối bằng Play App Signing Key.<br>- Google Play Services từ chối cấp token nếu SHA-1 runtime không khớp Android OAuth Client trong Cloud Project `284912111014`. | **UNCONFIRMED (Chờ đối chiếu Console bên ngoài)**.<br>Đây là nguyên nhân khởi phát việc Google Play Services đóng flow đăng nhập. |
| **Nhánh 2: Nuốt ngoại lệ và im lặng tại tầng Client (AppAuthManager + MoreFragment)** | Đã xác nhận trong code | - Tại `AppAuthManager.kt:895-905`: Mọi `GetCredentialCancellationException` đều bị gán nhãn cứng `Log.d` là "User cancelled". Không ghi nhận `e.type` hay `e.message` (kể cả dạng sanitized).<br>- Tại `MoreFragment.kt:344-347`: `onCancelled` chỉ xóa cờ pending mà không có bất kỳ phản hồi nào trên giao diện (không toast, không dialog).<br>- Triệu chứng "chọn Gmail xong im lặng" khớp 100% với việc nhảy vào nhánh này. | **CONFIRMED (Khiếm khuyết UX/Diagnostics trong code)**.<br>Cần cải thiện chẩn đoán và quản lý trạng thái kết thúc ở L03. |
| **Nhánh 3: Thất bại tại bước Parse Credential hoặc Commit dữ liệu** | Không phù hợp | - Nếu parse lỗi hoặc ném ngoại lệ ngoài `CancellationException`, `AppAuthManager` sẽ kích hoạt fallback hoặc gọi `onError`.<br>- Nếu commit dữ liệu local lỗi, dòng 939 gọi `onError(commitError)` và hiển thị dialog báo lỗi.<br>- Người dùng không thấy dialog lỗi nào xuất hiện. | **NOT_PRIMARY_FOR_SILENCE (Bác bỏ nguyên nhân gây im lặng)**. |
| **Nhánh 4: R8 / ProGuard loại bỏ class SDK Credential Manager** | Rất thấp | - Các thư viện `credentials-play-services-auth:1.3.0` và `googleid:1.1.1` đều có consumer proguard rules nhúng sẵn trong AAR.<br>- Nếu ném `ClassNotFoundException` trong provider, `catch (e: Exception)` tại dòng 912 sẽ bắt và kích hoạt legacy fallback. Không có legacy fallback nào xuất hiện. | **UNCONFIRMED (Không có bằng chứng R8 làm mất class)**. |
| **Nhánh 5: Hủy do Activity Lifecycle hoặc Stale generation** | Rất thấp | - Quá trình chọn Gmail bình thường không tiêu hủy `MainActivity`.<br>- Nếu bị stale generation, code có log rõ ràng `Discarding stale Credential Manager result`. | **UNCONFIRMED**. |

## 3. Quyết định định hướng cho các gói kế tiếp

1. **Khôi phục dịch vụ production:**
   - Phụ thuộc vào việc quản trị viên cấu hình đúng Android OAuth Client với Play App Signing SHA-1 trên Google Cloud Console (theo hướng dẫn tại L01).
   - Nếu việc sửa cấu hình giải quyết được trên bản Play hiện hành, đánh dấu `CONFIG_ONLY_RESOLVED`.

2. **Cải thiện mã nguồn (Gói L03 & L04):**
   - **Tuyệt đối không:** Thay Web client ID bằng Android client ID; không nới lỏng session/owner check; không tự ý auto-retry/auto-fallback khi SDK trả về cancellation (tuân thủ chỉ dẫn của Google Identity).
   - **Cần thực hiện tại L03:**
     - Bổ sung ghi log có cấu trúc (stage, attempt, exception type, sanitized message) đọc được trên cả bản release.
     - Không mặc định mọi `GetCredentialCancellationException` đều là "người dùng chủ động hủy".
     - Xử lý trạng thái kết thúc rõ ràng, giải phóng pending state an toàn, cho phép người dùng retry thủ công ngay lập tức mà không bị kẹt.
     - Giữ nguyên tắc: Cancellation thực sự không tự động fallback mở lại chooser lần 2.

## 4. Kết luận nghiệm thu L02
- Phân định rõ ràng 2 nhánh:
  - Nhánh cấu hình ngoài: `CONFIG_MISMATCH_PLAY_SIGNING` (Chờ quản trị viên).
  - Nhánh nội tại: `PROVIDER_CANCELLATION_SWALLOWING` (Tiến hành triển khai chẩn đoán an toàn tại L03).
- Chuyển sang gói L03.
