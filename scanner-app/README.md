# Scanner App

Web nội bộ để chụp ảnh tổng quan và SN trên điện thoại, lấy ngẫu nhiên hai ảnh mặt trước/mặt sau có sẵn, đối chiếu SN với Excel PO và tạo Word. Điện thoại gửi ảnh; máy chạy backend xử lý OCR và đọc/ghi dữ liệu.

## Các thành phần

- Frontend HTML/CSS/JavaScript nằm trong `src/main/resources/static`.
- Backend Spring Boot yêu cầu Java 21. Maven đóng cả frontend và backend vào một file `scanner-app-1.0.0.jar`.
- Backend gọi `ocr.py` bằng Python trong cùng máy hoặc container. Đặt `ocr.py` tại thư mục làm việc của app và cấu hình `SCANNER_PYTHON` nếu cần.
- Tạo lại môi trường Python từ `requirements-ocr.txt` trên máy đích; không chép `.venv` giữa Windows và Linux. Model riêng trong `models/` là tùy chọn; nếu không có thì dùng model đi kèm RapidOCR.
- Bản hiện tại không cần database hoặc Microsoft Office.

Hướng dẫn chạy Windows: [README-NAS.md](README-NAS.md). Cài OCR: [README-OCR.md](README-OCR.md). Chạy Docker trên Ubuntu: [README-DOCKER.md](README-DOCKER.md).

## Thứ tự triển khai thử lên IBM x3250 M3

1. Commit/push mã nguồn lên GitHub. Đây là lưu phiên bản, chưa triển khai app.
2. Kiểm tra bản hệ điều hành, CPU, RAM, ổ đĩa và IP máy IBM. Với Linux, dùng `cat /etc/os-release`, `lscpu`, `free -h`, `df -h`, `ip -br -4 addr`. Chưa cài lại hệ điều hành hoặc thay đổi RAID khi chưa kiểm tra dữ liệu.
3. Nếu Ubuntu đã có Docker Engine và Docker Compose, dùng [README-DOCKER.md](README-DOCKER.md). Dockerfile chuẩn bị Java/Python trong image, không cần cài chúng riêng trên Ubuntu. Có thể chạy trực tiếp nếu không dùng Docker.
4. Clone repository rồi build trên IBM theo hướng dẫn Docker; thử OCR trên CPU thật. Nếu chạy trực tiếp, build JAR, cài Java 21 và Python cùng thư viện OCR, đặt `ocr.py` cạnh JAR trong thư mục làm việc.
5. Cấu hình cổng 18080, địa chỉ lắng nghe LAN và firewall cho nhóm thiết bị thử. Điện thoại vào IP của IBM, không vào `localhost` hoặc IP của NAS. Chỉ mở LAN sau khi kiểm tra quyền truy cập của app.
6. Thử bằng ảnh/Excel mẫu và thư mục báo cáo riêng. Kiểm tra ảnh ngang/dọc, SN đúng/sai, gợi ý Excel, Word và báo trùng tên.
7. Khi thử cục bộ đạt, kết nối NAS. Linux dùng mount SMB; Windows dùng UNC. Tài khoản backend đọc ảnh và ghi báo cáo vào phạm vi được cấp quyền. Không lưu mật khẩu NAS trong Git hoặc JavaScript.
8. Cấu hình tự khởi động và phục hồi: systemd trên Linux, Windows Service trên Windows hoặc chính sách restart nếu dùng container. Kiểm tra app sau khi reboot.
9. Trước khi chuyển sang server chính, kiểm tra hệ điều hành/runtime đích, phân quyền/đăng nhập, thư mục dữ liệu và cổng riêng. Thử lại luồng từ điện thoại trên server chính.

## Docker nằm ở đâu?

GitHub lưu source code. Docker Engine chạy trên máy chủ và khởi động container từ image. Image có thể build ngay trên IBM hoặc tải từ registry như GHCR/Docker Hub; không bắt buộc đẩy image lên registry khi chỉ thử một máy.

Với app này, nếu dùng Docker, một container có thể chứa JAR (đã gồm frontend), Python, `ocr.py` và thư viện OCR. Backend hiện gọi Python bằng tiến trình cục bộ nên chưa cần tách OCR thành dịch vụ/container riêng.

Ảnh và Word phải nằm ở thư mục được mount từ máy chủ/NAS vào container, để không mất khi thay container. Container Linux dùng đường dẫn Linux; không sử dụng trực tiếp đường dẫn ổ C/D hoặc hộp thoại Windows. Chạy container Linux trên server Windows cần môi trường Linux tương thích (ví dụ VM Linux), không chỉ sao chép image rồi chạy như ứng dụng Windows.

Repository có Dockerfile và Docker Compose cho bản thử Ubuntu. Làm theo [README-DOCKER.md](README-DOCKER.md) để build và kiểm tra trên máy IBM; cấu hình này chưa được xác nhận chạy thực tế trên IBM. Push GitHub không tự triển khai container.

## Kiểm tra trước khi phát hành

Từ thư mục `scanner-app`:

```text
mvn test
node tests/test_export.cjs
node tests/test_nas.cjs
```

Test Python: dùng Python trong `.venv` chạy `-m unittest discover -s tests -v`. Test tự động không thay thế thử camera iPhone, hiệu năng OCR trên IBM hoặc quyền truy cập NAS thực tế.

Không commit `.venv`, thư mục `target*`, log, thông tin đăng nhập, Excel PO hoặc ảnh/báo cáo công ty. App hiện tải một số thư viện/font từ CDN; triển khai hoàn toàn không Internet cần đóng gói các tài nguyên này tại máy chủ.
