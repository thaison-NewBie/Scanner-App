# Chạy thử Scanner trên Windows

Profile `nas` hiện dùng cổng **18080**, chỉ lắng nghe **127.0.0.1**. Tên profile được giữ từ bản thử NAS; cấu hình hiện tại cho phép chọn thư mục trên máy hoặc NAS theo quyền của tài khoản chạy app.

## Hành vi hiện tại

- Có thể chọn thư mục ảnh mặt trước/mặt sau và nơi lưu Word trên ổ đĩa cục bộ hoặc share NAS truy cập được.
- `scanner.storage.restricted=false`: không bắt buộc chọn bên trong các gốc NAS. `images-root` và `reports-root` trong profile chỉ là cấu hình gợi ý cũ.
- Nếu chọn nơi lưu, Word được ghi ở máy chạy backend. Nếu không chọn nơi lưu, trình duyệt tải Word về.
- Trùng tên Word: hiện thông báo, không ghi đè file cũ và giữ thông tin trên form.
- Excel và nơi lưu thuộc từng phiên trình duyệt. Tải lại trang bắt đầu phiên làm việc trống.
- Ảnh chụp từ camera được tự lưu vào `C:/ScannerData/Captures` (cấu hình `scanner.storage.captures-root`) để lần sau chọn lại; tên file gồm thời điểm, slot và SN nếu đã có.
- Ô 1/2 nhớ chung thư mục chọn ảnh trong phiên, gợi ý tên ảnh tồn tại kế tiếp. Ô 3/4 nhớ chung vị trí thư mục riêng.
- Truy cập bằng `localhost` trên Windows dùng hộp thoại Windows; truy cập từ điện thoại dùng hộp thoại duyệt thư mục trong web.
- Excel PO chọn trực tiếp trên ổ đĩa của máy chủ qua hộp thoại duyệt trong web (duyệt ổ/thư mục, dán được đường dẫn UNC); vẫn có nút tải Excel từ điện thoại. Khi `restricted=true`, chỉ duyệt được trong thư mục báo cáo đã cấu hình.
- Chụp qua web chưa tự lưu ảnh vào Photos của iPhone. Khi cần bản gốc dự phòng, chụp bằng Camera rồi chọn từ thư viện.

## Chuẩn bị và khởi động

Cần Java 21, Maven để build, và môi trường Python theo [README-OCR.md](README-OCR.md). Chạy các lệnh từ thư mục `scanner-app`.

```powershell
mvn -B -ntp '-Dscanner.build.directory=target-nas' package
```

Chỉ khởi động sau khi build thành công:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-nas.ps1
```

Script dùng `target-nas/scanner-app-1.0.0.jar`, đặt `SCANNER_PYTHON` tới `.venv/Scripts/python.exe` (hoặc đường dẫn truyền qua `-PythonExe` khi dùng lại venv của bản cài cũ), chạy nền và ghi log tại thư mục project. Mặc định chỉ nghe `127.0.0.1`; thêm `-BindAddress 0.0.0.0` khi cần điện thoại truy cập. Máy phát triển này có Maven tại `D:\WebApp\workspace\maven\apache-maven-3.9.9\bin\mvn.cmd` nếu chưa có `mvn` trên PATH.

Mở `http://localhost:18080/`. Dừng đúng tiến trình do script tạo bằng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\stop-nas.ps1
```

## Kết nối điện thoại và NAS

Cấu hình mặc định chỉ lắng nghe `127.0.0.1`. Để điện thoại cùng Wi-Fi truy cập, chạy app với `-BindAddress 0.0.0.0`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-nas.ps1 -BindAddress 0.0.0.0
```

Lần đầu mở LAN cần thêm rule firewall (PowerShell Run as Administrator), giới hạn trong dải mạng nội bộ:

```powershell
New-NetFirewallRule -DisplayName "Scanner App 18080" -Direction Inbound -Protocol TCP -LocalPort 18080 -Action Allow -Profile Private,Public -RemoteAddress 192.168.1.0/24
```

Lấy IP của máy chạy bằng `ipconfig`, rồi mở `http://<IP>:18080/` trên điện thoại. IP của NAS không phải IP của app.

NAS được truy cập bằng tài khoản chạy backend. Tài khoản đó phải đọc được thư mục ảnh và có quyền ghi ở nơi lưu báo cáo; không gửi mật khẩu NAS xuống điện thoại. Dùng đường dẫn UNC đầy đủ trên Windows. Trên Linux cần mount share SMB rồi dùng đường dẫn mount.

Thử trước với ảnh/Excel mẫu và thư mục báo cáo riêng; kiểm tra cả tạo Word, trùng tên và mất quyền truy cập. App chưa có đăng nhập/HTTPS; khi mở LAN, các thiết bị truy cập được app có thể dùng chức năng đọc ảnh và ghi báo cáo theo quyền của backend. Không mở cổng app ra Internet trong cấu hình này.

Script chưa tự chạy lại khi Windows khởi động hoặc khi app lỗi. Triển khai 24/24 cần cấu hình dịch vụ và quyền tài khoản phù hợp. Xem [README.md](README.md) về thứ tự triển khai.
