# Gemini — khép phần U01 còn thiếu, vòng 5

Ngày 06/10/2026. Workspace `E:\DU AN AI\T-Scanner`.

Đọc `RECHECK_VIP_SESSION_ROUND5_2026-10-06.md` và `docs/vip-session-round5-reaudit-20261006/`. Đây là sửa tiếp hợp đồng đã yêu cầu ở U00/U01 vòng 4, không mở lại toàn bộ đăng nhập/Billing. Giữ K02/K03 và các regression cũ.

## Phạm vi và nguyên tắc

- Thực hiện tuần tự V00–V03, báo cáo mỗi gói, không hỏi lại từng gói. Giữ toàn bộ thay đổi đang có; không reset/clean/stash, commit/push/phát hành.
- Không đổi OAuth, signing, package, version, R8, dependencies, backend hay quyền Drive. Không mua thật, xóa dữ liệu hoặc log token/receipt thật.
- Chỉ sửa recovery admission và đường callback cần thiết: `BillingManager.kt`, `VipPurchaseAuthConsumer.kt`, `VipUpgradeDialog.kt`, các host hiện thực callback (More/Home và navigation/continuation nếu cần), state holder nhỏ và tests. Trước sửa liệt kê đầy đủ call sites, không sửa host không liên quan.
- Không coi tên test hoặc cờ `isRetry` là bằng chứng provider đã nhận. Phải test production coordinator được UI gọi thực, với fake provider/restore là dependency ở ranh giới bên ngoài.

## V00 — Khóa baseline và test đỏ

Chỉ tests/docs. Lưu status/hash các file sẽ sửa. Chạy nguyên bản 4 probe vòng 5 và 21 regression được chọn trong `run-probes.ps1`; không sửa assertion để hợp thức hóa lỗi. Ghi rõ P01/P02/P03 đỏ tại assertion nào, C01 xanh. Probe P03 đếm RequestReauth ở consumer, không chứng minh SDK mở hai màn hình hay tính tiền hai lần.

Lập bảng wiring thực: manager event → consumer/coordinator → host → `signInWithGoogle` → callback kết thúc → restore. Hiện callback `(action) -> Unit` làm mất kết quả nhận/từ chối và operation context. Test mới phải đi qua wiring production, không nhận event vào biến rồi tự gọi restore/cancel.

Checkpoint: `docs/vip-session-round5-fix/REPORT_V00.md` và `PROGRESS.md`.

## V01 — Quyền nhận recovery và vòng đời reservation

Depends V00. Chủ yếu BillingManager, coordinator/consumer và tests. Thay đánh dấu khi có listener bằng state machine tối thiểu, bảo đảm atomic claim:

| Trạng thái/tình huống | Hành vi bắt buộc |
|---|---|
| Event stale, background, thiếu host, UI inactive | Ignore/Defer, không reserve/tiêu lượt |
| Event hợp lệ + host sẵn sàng | Một consumer duy nhất reserve bằng token duy nhất cho recovery của operation/receipt |
| Consumer thứ hai hoặc callback lặp trong lúc Reserved/Started | Không gọi provider, không tạo reservation thứ hai |
| Provider từ chối/busy hoặc host mất trước khi nhận | Release đúng reservation, không đánh dấu đã dùng lượt; có đường retry rõ ràng |
| Provider nhận công việc | Commit Started theo đúng reservation, tối đa một accepted attempt của recovery operation |
| Callback kết thúc từ attempt cũ | Chỉ tác động reservation của nó; không clear trạng thái của attempt mới |
| Auth lỗi tiếp sau accepted recovery trong cùng operation | Stop, không tự mở provider vòng lặp |
| Người dùng bắt đầu thao tác mới | Operation mới có lượt mới dù cùng receipt; không cần restart singleton |

Không dùng `hasUiListener`, `listener.invoke` trả về Unit hoặc event.isRetry snapshot làm quyền nhận recovery. Không dùng `contains` rồi `add` như thao tác nguyên tử. Nếu provider callback chạy đồng bộ trước return, chuyển trạng thái vẫn phải đúng. Quy định cleanup/terminal states để state không tăng vô hạn.

Identity giữ immutable origin operation/owner/epoch/receipt. Phân biệt origin generation với generation hiện tại hợp lệ sau reauth; không reset lượt bằng cách tạo ID mới hoặc đổi generation trong automatic continuation. Mọi đổi session ngoài attempt được ràng buộc phải làm event cũ vô hiệu.

Checkpoint: P01–P03 và C01 vòng 5 xanh qua production coordinator, tests reserve/release/duplicate/stale completion xanh. Nếu API cần đổi để test chạy, thực hiện thay đổi signature tối thiểu cùng gói, không để project không compile.

## V02 — Nối nhận/từ chối và continuation vào host thật

Depends V01. Dialog và mọi call site liên quan phải dùng coordinator V01. Tạo callback riêng cho receipt recovery nếu cần để không phá guest UPGRADE/RESTORE thông thường. Truyền envelope/reservation, expected owner và operation context; không chỉ action.

Host trả kết quả có kiểu rõ ràng, phản ánh kết quả thực từ `signInWithGoogle`/launch và callback terminal. Không trả Accepted ngay khi enqueue navigation. Nếu phải chờ navigation, giữ Reserved và resolve/release khi host đích thực sự nhận/từ chối hoặc lifecycle hủy. Xử lý busy, inactive, fallback launch exception, user cancel và callback cũ. Không gọi lại provider vô hạn khi release.

Sau accepted reauth cùng owner: restore receipt một lần, giữ recovery operation; **0 extra launchBillingFlow**, không cấp VIP/ack trước server verification. Không dùng test tự gọi restore thay production success callback.

Tests tích hợp bắt buộc (dependency fake, logic điều phối production):

1. UI inactive → active cùng operation: acceptedStarts 0 → 1.
2. Provider busy/reject → available: acceptedStarts 0 → 1; reservation cũ đã release.
3. Hai consumer/duplicate callbacks: chỉ một provider invocation được coordinator cho phép.
4. Accepted → success: restore đúng một lần, 0 extra purchase launch; repeated 401 cùng operation không reauth nữa.
5. Cancel/launch failure → thao tác thủ công mới: retry được; callback cũ không hủy retry mới.
6. Đổi owner/gen/epoch ở queue hoặc sau reserve: không auth/restore/UI effect cho event cũ, không cleanup operation mới.
7. Guest login hợp lệ và các K02/K03/regression cũ vẫn xanh.

Giữ test production binding; nếu không chạy được Android UI instrumentation thì ghi NOT RUN, không gọi JVM coordinator test là device end-to-end.

Checkpoint: report nêu file/call site thật, lời gọi nhận/từ chối/terminal và bằng chứng context đi đến restore.

## V03 — Kiểm chứng và bàn giao

Depends V02. Chạy 25 probes chọn lọc (4 vòng 5 + 21 cũ), tests mới/permanent, full `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` offline. Chép log/XML trước lần chạy khác; báo số thực tế, không ép khớp baseline. Giữ evidence cũ bất biến. Có thể cập nhật adapter của probe nếu API đổi nhưng phải giữ tình huống/assertion, lưu diff và lý do.

Device/Play: Free A hết hạn token; đóng/mở dialog quanh auth callback; provider busy/cancel rồi retry; receipt đã mua cần restore không mở mua mới; account switch khi queue; background lâu rồi quay lại. Dùng license tester được phép, không mua thật. Thiếu device ghi BLOCKED_EXTERNAL, chưa nghiệm thu toàn bộ lỗi người dùng.

Mỗi gói lưu `REPORT_Vxx.md` + `PROGRESS.md`; cuối cùng `REPORT_FINAL.md` trong `docs/vip-session-round5-fix/`. Báo source/host/device riêng, red→green, commands, log/XML, số provider/restore/extra-launch và giới hạn chưa kiểm chứng. Dừng ở bàn giao review, không phát hành.
