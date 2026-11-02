package com.scanner.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Giới hạn vùng NAS; từng loại thư mục có thể được mở rộng theo cấu hình. */
@Service
public class StorageService {
    private final boolean restricted;
    private final boolean reportsUnrestricted;
    private final boolean imagesUnrestricted;
    private final String imagesRoot;
    private final String reportsRoot;

    public StorageService(
            @Value("${scanner.storage.restricted:false}") boolean restricted,
            @Value("${scanner.storage.reports-unrestricted:false}") boolean reportsUnrestricted,
            @Value("${scanner.storage.images-unrestricted:false}") boolean imagesUnrestricted,
            @Value("${scanner.storage.images-root:}") String imagesRoot,
            @Value("${scanner.storage.reports-root:}") String reportsRoot) {
        this.restricted = restricted;
        this.reportsUnrestricted = reportsUnrestricted;
        this.imagesUnrestricted = imagesUnrestricted;
        this.imagesRoot = imagesRoot;
        this.reportsRoot = reportsRoot;
        if (restricted && (imagesRoot.isBlank() || reportsRoot.isBlank())) {
            throw new IllegalArgumentException("Chưa cấu hình thư mục ảnh và báo cáo NAS");
        }
    }

    public boolean isRestricted() { return restricted; }

    public Map<String, Object> config() {
        return Map.of("success", true, "nasMode", restricted,
            "imagesRoot", imagesRoot, "reportsRoot", reportsRoot,
            "reportsUnrestricted", reportsUnrestricted,
            "imagesUnrestricted", imagesUnrestricted,
            "foldersUnrestricted", !restricted || (reportsUnrestricted && imagesUnrestricted));
    }

    private boolean allowsAnyLocalDirectory(String purpose) {
        return (reportsUnrestricted && "reports".equals(purpose)) ||
            (imagesUnrestricted && "images".equals(purpose));
    }

    private String configuredRoot(String purpose) {
        return switch (purpose) {
            case "images" -> imagesRoot;
            case "reports" -> reportsRoot;
            default -> throw new IllegalArgumentException("Loại thư mục không hợp lệ");
        };
    }

    private Path root(String purpose) {
        return Paths.get(configuredRoot(purpose)).toAbsolutePath().normalize();
    }

    /** True cho dạng \\192.168.1.8 hoặc \\ten-nas\. */
    private boolean isUncServerRoot(String value) {
        String normalized = value.replace('/', '\\');
        if (!normalized.startsWith("\\\\")) return false;
        String serverPart = normalized.substring(2);
        int separator = serverPart.indexOf('\\');
        return separator < 0 || separator == serverPart.length() - 1;
    }

    private String comparablePath(String value) {
        String normalized = value.replace('/', '\\');
        while (normalized.length() > 2 && normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isAtOrBelow(String candidate, String allowed) {
        String path = comparablePath(candidate);
        String root = comparablePath(allowed);
        return path.equals(root) || path.startsWith(root + "\\");
    }

    private void rejectLinksBelowUncServer(Path path, String serverRoot) throws IOException {
        Path current = path;
        while (current != null && !comparablePath(current.toString()).equals(comparablePath(serverRoot))) {
            rejectLink(current);
            current = current.getParent();
        }
    }

    private Path checked(String value, String purpose) throws IOException {
        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (!restricted || allowsAnyLocalDirectory(purpose)) return path;
        String configured = configuredRoot(purpose);

        // Ảnh có thể được đọc từ mọi share mà tài khoản Windows đã được NAS cấp quyền,
        // nhưng chỉ trong cùng NAS đã cấu hình; không cho đi sang máy chủ khác.
        if (isUncServerRoot(configured)) {
            if (!isAtOrBelow(path.toString(), configured)) {
                throw new AccessDeniedException("Chỉ được chọn bên trong NAS đã cấu hình");
            }
            rejectLinksBelowUncServer(path, configured);
            return path;
        }

        Path allowed = root(purpose);
        if (!path.startsWith(allowed)) {
            throw new AccessDeniedException("Chỉ được chọn bên trong thư mục NAS đã cấu hình");
        }
        // Một số share SMB không cho truy vấn tên chuẩn qua toRealPath().
        // Thay vì theo liên kết, từ chối mọi symlink/junction ở từng cấp đường dẫn.
        rejectLink(allowed);
        Path segmentPath = allowed;
        for (Path segment : allowed.relativize(path)) {
            segmentPath = segmentPath.resolve(segment);
            rejectLink(segmentPath);
        }
        return path;
    }

    private void rejectLink(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new AccessDeniedException("Không sử dụng liên kết/junction trong thư mục NAS");
        }
    }

    public Path directory(String value, String purpose) throws IOException {
        Path path = checked(value, purpose);
        if (!Files.isDirectory(path)) throw new IOException("Thư mục không tồn tại hoặc không truy cập được");
        return path;
    }

    /**
     * Đọc một file đã được chọn từ bộ duyệt thư mục trên web.
     * Vẫn áp dụng cùng giới hạn đường dẫn như thư mục ảnh/báo cáo,
     * nên trình duyệt không thể gửi một đường dẫn nằm ngoài phạm vi được phép.
     */
    public Path readableFile(String value, String purpose) throws IOException {
        Path path = checked(value, purpose);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("File không tồn tại hoặc không truy cập được");
        }
        return path;
    }

    public static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) &&
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
             name.endsWith(".bmp") || name.endsWith(".webp"));
    }

    public Path image(String value) throws IOException {
        Path path = checked(value, "images");
        if (!isImage(path)) throw new AccessDeniedException("Đường dẫn không phải file ảnh được hỗ trợ");
        return path;
    }

    public List<Path> images(String value) throws IOException {
        Path dir = directory(value, "images");
        List<Path> result = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!isImage(entry)) continue;
                try { result.add(image(entry.toString())); }
                catch (AccessDeniedException ignored) { /* Không trả liên kết ngoài share. */ }
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    public Map<String, Object> browse(String value, String purpose) throws IOException {
        List<Map<String, String>> entries = new ArrayList<>();
        if (value.isBlank()) {
            if (restricted && !allowsAnyLocalDirectory(purpose)) {
                Path allowed = root(purpose);
                // UNC share itself is a Windows filesystem root, so getFileName() is null.
                String label = allowed.toString().replaceAll("[\\\\/]+$", "");
                label = label.substring(Math.max(label.lastIndexOf('\\'), label.lastIndexOf('/')) + 1);
                entries.add(Map.of("name", label,
                    "path", allowed.toString(), "type", "share"));
            } else {
                for (Path drive : FileSystems.getDefault().getRootDirectories()) {
                    entries.add(Map.of("name", drive.toString(), "path", drive.toString(), "type", "drive"));
                }
            }
            return Map.of("success", true, "current", "", "parent", "", "entries", entries);
        }
        Path current = directory(value, purpose);
        String warning = "";
        // Một số NAS cho phép dùng share làm nơi lưu nhưng không cấp quyền liệt kê ngay
        // ở cấp gốc. Khi đó vẫn cho chọn chính share; thư mục con có thể được dán trực tiếp.
        try (var stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || Files.isHidden(entry)) continue;
                try { checked(entry.toString(), purpose); }
                catch (AccessDeniedException ignored) { continue; }
                entries.add(Map.of("name", entry.getFileName().toString(),
                    "path", entry.toString(), "type", "folder"));
            }
        } catch (IOException listingFailure) {
            if (!(restricted && !allowsAnyLocalDirectory(purpose) && current.equals(root(purpose)))) throw listingFailure;
            warning = "NAS không cho liệt kê thư mục con tại đây. Bạn vẫn có thể chọn thư mục này, hoặc dán đường dẫn thư mục con.";
        }
        entries.sort(Comparator.comparing(e -> e.get("name").toLowerCase(Locale.ROOT)));
        String parent = current.getParent() == null || (restricted && !allowsAnyLocalDirectory(purpose) && current.equals(root(purpose)))
            ? "" : current.getParent().toString();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("current", current.toString());
        result.put("parent", parent);
        result.put("entries", entries);
        if (!warning.isEmpty()) result.put("warning", warning);
        return result;
    }

    public Path saveDirectory(String value) throws IOException {
        if (!restricted) Files.createDirectories(Paths.get(value));
        Path path = directory(value, "reports");
        if (!Files.isWritable(path)) throw new AccessDeniedException("Không có quyền ghi vào thư mục báo cáo");
        return path;
    }

    public Path destination(String folder, String filename) throws IOException {
        if (!filename.matches("(TrcSC|SauSC|ChayNo)_[a-zA-Z0-9_-]*\\.docx")) {
            throw new IllegalArgumentException("Tên báo cáo không hợp lệ");
        }
        return directory(folder, "reports").resolve(filename);
    }

    public void saveNew(Path destination, byte[] bytes) throws IOException {
        // Xác thực lại ngay trước khi ghi. CREATE_NEW chặn cả hai yêu cầu đến cùng lúc.
        Path target = destination(destination.getParent().toString(), destination.getFileName().toString());
        OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (output) {
            output.write(bytes);
        } catch (IOException failure) {
            // Chỉ xóa file do chính request này vừa tạo nếu việc ghi thất bại.
            try { Files.deleteIfExists(target); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }
}
