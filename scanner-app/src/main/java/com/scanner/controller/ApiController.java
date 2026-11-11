package com.scanner.controller;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.scanner.service.ExcelService;
import com.scanner.service.WordService;
import com.scanner.service.StorageService;
import com.scanner.service.OcrService;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.annotation.SessionScope;
import jakarta.servlet.http.HttpServletRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;


@RestController
@RequestMapping("/api")
@SessionScope
public class ApiController {

    @Autowired private ExcelService excelService;
    @Autowired private WordService wordService;
    @Autowired private StorageService storageService;
    @Autowired private OcrService ocrService;

    // Nơi lưu thuộc phiên trình duyệt hiện tại, không dùng chung giữa các máy.
    // tsc/ssc dùng cho chế độ TSC+SSC; single dùng cho Cháy Nổ và "Không sửa được".
    private String tscSavePath = "";
    private String sscSavePath = "";
    private String singleSavePath = "";

    @GetMapping("/storage/config")
    public Map<String, Object> storageConfig() {
        return storageService.config();
    }

    // ── Status ────────────────────────────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "status", "OK",
            "snLoaded", excelService.getLoadedCount(),
            "savePath", singleSavePath,
            "tscSavePath", tscSavePath,
            "sscSavePath", sscSavePath
        ));
    }

    @PostMapping("/session/reset")
    public ResponseEntity<Map<String, Object>> resetSession() {
        excelService.clear();
        tscSavePath = "";
        sscSavePath = "";
        singleSavePath = "";
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã reset phiên làm việc"));
    }

    // ── Browse Folders ────────────────────────────────────────────
    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browse(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "images") String purpose) {
        try {
            return ResponseEntity.ok(storageService.browse(path, purpose));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "Không liệt kê được thư mục. Kiểm tra kết nối/quyền truy cập, hoặc dán đường dẫn đầy đủ của thư mục PO vào ô bên trên: " + e.getMessage()));
        }
    }

    // ── Set Save Path ─────────────────────────────────────────────
    // type: "tsc" | "ssc" | "single" (mặc định "single" để tương thích cũ)
    @PostMapping("/config/save-path")
    public ResponseEntity<Map<String, Object>> setSavePath(
            @RequestBody Map<String, String> body) {
        String path = body.getOrDefault("path", "").trim();
        String type = body.getOrDefault("type", "single").trim();
        if (path.isEmpty()) {
            assignSavePath(type, "");
            return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa nơi lưu file"));
        }
        try {
            Path p = storageService.saveDirectory(path);
            assignSavePath(type, p.toString());
            return ResponseEntity.ok(Map.of("success", true,
                "message", "Đã chọn: " + path,
                "path", p.toString(), "type", type));
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "Đường dẫn không hợp lệ: " + e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "Không chọn được nơi lưu: " + e.getMessage()));
        }
    }

    private void assignSavePath(String type, String value) {
        switch (type) {
            case "tsc" -> tscSavePath = value;
            case "ssc" -> sscSavePath = value;
            default -> singleSavePath = value;
        }
    }

    // ── Native Folder Picker — Modern Windows Explorer (DLL cached) ──
    // DLL được compile 1 lần, các lần sau chỉ load → nhanh hơn đáng kể
    private static volatile String cachedDllPath = null;
    private static final Object DLL_LOCK = new Object();
    private static final String DLL_VER  = "v4";

    private static final String CS_CODE =
        "using System;\n" +
        "using System.Runtime.InteropServices;\n" +
        "[ComImport,Guid(\"D57C7288-D4AD-4768-BE02-9D969532D960\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
        "interface IFileOpenDialog {\n" +
        "  [PreserveSig] int Show(IntPtr h);\n" +
        "  int SetFileTypes(uint n,IntPtr p); int SetFileTypeIndex(uint i); int GetFileTypeIndex(out uint i);\n" +
        "  int Advise(IntPtr p,out uint c); int Unadvise(uint c);\n" +
        "  int SetOptions(uint f); int GetOptions(out uint f);\n" +
        "  int SetDefaultFolder(IntPtr p); int SetFolder(IntPtr p); int GetFolder(out IntPtr p);\n" +
        "  int GetCurrentSelection(out IntPtr p);\n" +
        "  int SetFileName([MarshalAs(UnmanagedType.LPWStr)] string n);\n" +
        "  int GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string n);\n" +
        "  int SetTitle([MarshalAs(UnmanagedType.LPWStr)] string t);\n" +
        "  int SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string l);\n" +
        "  int SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string l);\n" +
        "  int GetResult(out IntPtr p); int AddPlace(IntPtr p,int f);\n" +
        "  int SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string e);\n" +
        "  int Close(int hr); int SetClientGuid([In] ref Guid g); int ClearClientData();\n" +
        "  int SetFilter(IntPtr p); int GetResults(out IntPtr p); int GetSelectedItems(out IntPtr p);\n" +
        "}\n" +
        "[ComImport,Guid(\"43826D1E-E718-42EE-BC55-A1E261C37BFE\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
        "interface IShellItem {\n" +
        "  int BindToHandler(IntPtr bc,[In] ref Guid b,[In] ref Guid r,out IntPtr v);\n" +
        "  int GetParent(out IntPtr p);\n" +
        "  int GetDisplayName(uint n,[MarshalAs(UnmanagedType.LPWStr)] out string s);\n" +
        "  int GetAttributes(uint m,out uint a); int Compare(IntPtr si,uint h,out int o);\n" +
        "}\n" +
        "public class FP {\n" +
        "  [DllImport(\"ole32.dll\")] static extern int CoCreateInstance(ref Guid c,IntPtr u,uint x,ref Guid i,out IntPtr p);\n" +
        "  [DllImport(\"shell32.dll\",CharSet=CharSet.Unicode)] static extern int SHCreateItemFromParsingName(string p,IntPtr b,ref Guid r,out IntPtr v);\n" +
        "  [DllImport(\"user32.dll\")] static extern bool SetProcessDPIAware();\n" +
        "  public static string Pick(string title,string init,string suggested,bool isFolder) {\n" +
        "    try {\n" +
        "      try { SetProcessDPIAware(); } catch {} \n" +
        "      var clsid=new Guid(\"DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7\");\n" + // CLSID_FileOpenDialog
        "      var iid=new Guid(\"D57C7288-D4AD-4768-BE02-9D969532D960\");\n" +   // IID_IFileOpenDialog
        "      IntPtr pDlg; int createHr=CoCreateInstance(ref clsid,IntPtr.Zero,1,ref iid,out pDlg); if(createHr!=0) return \"__ERROR__:Không tạo được hộp thoại Windows (0x\" + createHr.ToString(\"X8\") + \")\";\n" +
        "      var dlg=(IFileOpenDialog)Marshal.GetObjectForIUnknown(pDlg);\n" +
        "      dlg.SetTitle(title);\n" +
        "      uint opt=0x00000008 | 0x00000002; // FOS_FORCEFILESYSTEM | FOS_PICKFOLDERS\n" +
        "      if(isFolder) dlg.SetOptions(opt | 0x20);\n" + 
        "      else dlg.SetOptions(0x00000008 | 0x00001000); // FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST\n" +
        "      if(!string.IsNullOrEmpty(suggested)) dlg.SetFileName(suggested);\n" +
        "      if(!string.IsNullOrEmpty(init)){\n" +
        "        var siid=new Guid(\"43826D1E-E718-42EE-BC55-A1E261C37BFE\");\n" +
        "        IntPtr pi; if(SHCreateItemFromParsingName(init,IntPtr.Zero,ref siid,out pi)==0){dlg.SetFolder(pi); Marshal.Release(pi);}\n" +
        "      }\n" +
        "      int showHr=dlg.Show(IntPtr.Zero);\n" +
        "      if(showHr!=0){ Marshal.Release(pDlg); if(showHr==unchecked((int)0x800704C7)) return null; return \"__ERROR__:Windows từ chối mở hộp thoại (0x\" + showHr.ToString(\"X8\") + \")\"; }\n" +
        "      IntPtr pRes; dlg.GetResult(out pRes);\n" +
        "      var item=(IShellItem)Marshal.GetObjectForIUnknown(pRes);\n" +
        "      string path; item.GetDisplayName(0x80028000,out path);\n" +
        "      Marshal.Release(pRes); Marshal.Release(pDlg); return path;\n" +
                "    } catch(Exception e) { return \"__ERROR__:\" + e.Message; }\n" +
        "  }\n" +
        "}\n";

    private String getPickerDll() throws Exception {
        String dllPath = System.getProperty("java.io.tmpdir") + "ScannerFP_v8.dll";
        if (cachedDllPath != null && Files.exists(Paths.get(cachedDllPath)) && Files.size(Paths.get(cachedDllPath)) > 0) return cachedDllPath;
        synchronized (DLL_LOCK) {
            if (!Files.exists(Paths.get(dllPath))) {
                // Lần đầu: compile C# → lưu DLL
                Path csFile = Files.createTempFile("ScannerFP", ".cs");
                Files.writeString(csFile, CS_CODE, StandardCharsets.UTF_8);
                String compilePs =
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n" +
                    "$code = [IO.File]::ReadAllText('" + csFile.toString().replace("'","''") + "')\r\n" +
                    "Add-Type -TypeDefinition $code -OutputAssembly '" + dllPath.replace("'","''") + "'\r\n";
                Path compilePsFile = Files.createTempFile("compile", ".ps1");
                Files.writeString(compilePsFile, compilePs, StandardCharsets.UTF_8);
                Process compile = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", compilePsFile.toString())
                    .redirectErrorStream(true).start();
                String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int compileExit = compile.waitFor();
                Files.deleteIfExists(csFile);
                Files.deleteIfExists(compilePsFile);
                if (compileExit != 0 || !Files.exists(Paths.get(dllPath))) {
                    throw new IOException("Không biên dịch được hộp thoại Windows: " + compileOutput);
                }
            }
            cachedDllPath = dllPath;
        }
        return dllPath;
    }

    @GetMapping("/native-pick-folder")
    public ResponseEntity<Map<String, Object>> nativePickFolder(
            @RequestParam(defaultValue = "") String initial,
            HttpServletRequest request) {
        if (!isLocalRequest(request)) return localPickerOnly();
        return runNativePicker("Chọn thư mục", initial, "", true);
    }

    @GetMapping("/native-pick-file")
    public ResponseEntity<Map<String, Object>> nativePickFile(
            @RequestParam(defaultValue = "") String initial,
            @RequestParam(defaultValue = "") String suggested,
            HttpServletRequest request) {
        if (!isLocalRequest(request)) return localPickerOnly();
        return runNativePicker("Chọn tệp hình ảnh", initial, suggested, false);
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        try { return java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private ResponseEntity<Map<String, Object>> localPickerOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false,
            "error", "Hộp thoại Explorer chỉ mở được khi dùng trực tiếp trên máy Windows chạy app"));
    }

    private ResponseEntity<Map<String, Object>> runNativePicker(String title, String initial, String suggested, boolean isFolder) {
        Path tmpScript = null;
        try {
            String initPath = initial.isEmpty() ? "C:\\" : initial;
            String dllPath  = getPickerDll();

            String ps =
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n" +
                "$OutputEncoding = [System.Text.Encoding]::UTF8\r\n" +
                "$initPath = '" + initPath.replace("'", "''") + "'\r\n" +
                "$suggested = '" + suggested.replace("'", "''") + "'\r\n" +
                "Add-Type -Path '" + dllPath.replace("'", "''") + "'\r\n" +
                "$r = [FP]::Pick('" + title + "', $initPath, $suggested, " + (isFolder ? "$true" : "$false") + ")\r\n" +
                "if ($r) { $r } else { '__CANCELLED__' }\r\n";

            tmpScript = Files.createTempFile("fpRun", ".ps1");
            tmpScript.toFile().deleteOnExit();
            // Windows PowerShell 5 đọc UTF-8 không BOM theo ANSI: làm hỏng đường dẫn tiếng Việt.
            Files.writeString(tmpScript, "\uFEFF" + ps, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                // IFileOpenDialog là COM UI và bắt buộc phải chạy trên luồng STA.
                "powershell", "-NoProfile", "-STA", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", tmpScript.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String result = new String(proc.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            proc.waitFor();

            if (result.equals("__CANCELLED__") || result.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "cancelled", true));
            }
            if (result.startsWith("__ERROR__:")) {
                return ResponseEntity.status(500).body(Map.of("success", false,
                    "error", "Windows không mở được hộp thoại chọn thư mục: " + result.substring("__ERROR__:".length())));
            }
            return ResponseEntity.ok(Map.of("success", true, "path", result));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        } finally {
            try { if (tmpScript != null) Files.deleteIfExists(tmpScript); } catch (Exception ignored) {}
        }
    }

    // ── Upload Excel ──────────────────────────────────────────────
    @PostMapping("/excel/upload")
    public ResponseEntity<Map<String, Object>> uploadExcel(
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = excelService.loadExcel(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Không đọc được file Excel: " + e.getMessage()
            ));
        }
    }

    // ── Excel trên máy chủ ───────────────────────────────────────
    // Điện thoại duyệt ổ đĩa của MÁY CHỦ qua giao diện web, không duyệt ổ đĩa iPhone.
    @GetMapping("/excel/browse")
    public ResponseEntity<Map<String, Object>> browseServerExcel(
            @RequestParam(defaultValue = "") String path) {
        try {
            Map<String, Object> result = new HashMap<>(storageService.browse(path, "reports"));
            List<Map<String, Object>> files = new ArrayList<>();
            if (!path.isBlank()) {
                Path folder = storageService.directory(path, "reports");
                try (var stream = Files.list(folder)) {
                    files = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isExcelFile)
                        .sorted(Comparator.comparing(this::modifiedTime).reversed()
                            .thenComparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .map(item -> Map.<String, Object>of(
                            "name", item.getFileName().toString(),
                            "path", item.toString(),
                            "size", fileSize(item),
                            "modified", modifiedTime(item).toMillis()))
                        .toList();
                }
            }
            result.put("files", files);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                "error", "Không liệt kê được thư mục Excel: " + e.getMessage()));
        }
    }

    @PostMapping("/excel/load-file")
    public ResponseEntity<Map<String, Object>> loadServerExcel(@RequestBody Map<String, String> body) {
        try {
            String path = body.getOrDefault("path", "").trim();
            if (path.isEmpty()) throw new IllegalArgumentException("Chưa chọn file Excel");
            Path file = storageService.readableFile(path, "reports");
            if (!isExcelFile(file)) throw new IllegalArgumentException("Chỉ hỗ trợ file .xlsx hoặc .xls");
            return ResponseEntity.ok(excelService.loadExcel(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                "error", "Không đọc được file Excel trên máy chủ: " + e.getMessage()));
        }
    }

    private boolean isExcelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || name.endsWith(".xls");
    }

    private long fileSize(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0L; }
    }

    private java.nio.file.attribute.FileTime modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException ignored) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    // ── Check SN ─────────────────────────────────────────────────
    @PostMapping("/excel/check-sn")
    public ResponseEntity<Map<String, Object>> checkSN(
            @RequestBody Map<String, String> body) {
        String sn = body.getOrDefault("sn", "").trim();
        if (sn.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "SN trống"
            ));
        }
        boolean found = excelService.checkSN(sn);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "found", found,
            "sn", sn
        ));
    }

    // ── List SNs for comparison dialog ────────────────────────────
    @GetMapping("/excel/list-sn")
    public ResponseEntity<Map<String, Object>> listSN(
            @RequestParam(defaultValue = "") String filter) {
        List<String> list = excelService.getAllSN(filter);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "sns", list,
            "total", list.size()
        ));
    }

    // ── List images in a folder ───────────────────────────────────
    @GetMapping("/folder-images")
    public ResponseEntity<Map<String, Object>> listFolderImages(
            @RequestParam String path) {
        try {
            List<String> images = storageService.images(path).stream().map(Path::toString).toList();
            return ResponseEntity.ok(Map.of(
                "success", true, "images", images, "count", images.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }

    // ── Tên ảnh tồn tại kế tiếp trong cùng folder ─────────────────
    @GetMapping("/next-image")
    public ResponseEntity<Map<String, Object>> nextImage(
            @RequestParam String folder,
            @RequestParam String after) {
        try {
            Comparator<Path> byName = Comparator.comparing(
                path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
            Path next = storageService.images(folder).stream()
                .filter(path -> byName.compare(path,
                    Paths.get(after).getFileName()) > 0)
                .min(byName)
                .orElse(null);
            return ResponseEntity.ok(next == null
                ? Map.of("success", true, "name", "")
                : Map.of("success", true, "name", next.getFileName().toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }

    // ── Random image from folder ──────────────────────────────────
    @GetMapping("/random-image")
    public ResponseEntity<Map<String, Object>> randomImage(
            @RequestParam String path) {
        try {
            List<Path> images = storageService.images(path);
            if (images.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "success", false, "error", "Không có ảnh trong thư mục"));
            }
            Path chosen = images.get(new Random().nextInt(images.size()));
            return ResponseEntity.ok(Map.of(
                "success", true,
                "path", chosen.toAbsolutePath().toString(),
                "name", chosen.getFileName().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }

    // ── Serve image file (proxy for local paths) ──────────────────
    @GetMapping("/image")
    public ResponseEntity<byte[]> serveImage(@RequestParam String path) {
        try {
            Path p = storageService.image(path);
            byte[] data = Files.readAllBytes(p);
            String name = p.getFileName().toString().toLowerCase();
            MediaType mt = name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
            return ResponseEntity.ok().contentType(mt).body(data);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Generate Word ──────────────────────────────────────────────
    // reportType:
    //   "TSC_SSC"  → 1 nút tạo 2 file (TrcSC vào thư mục TSC, SauSC vào thư mục SSC)
    //   "CHAY_NO"  → 1 file ChayNo (nơi lưu đơn)
    //   "SAU_SCC"  → "Không sửa được": 1 file SauSC (nơi lưu đơn)
    //   "TRUOC_SCC"→ giữ lại cho tương thích/test (1 file TrcSC, nơi lưu đơn)
    @PostMapping("/report/generate")
    public ResponseEntity<?> generateReport(
            @RequestParam("deviceName")   String deviceName,
            @RequestParam("productCode")  String productCode,
            @RequestParam("poNumber")     String poNumber,
            @RequestParam("reportType")   String reportType,
            @RequestParam(value = "repairNotes", defaultValue = "") String repairNotes,
            @RequestParam("serialNumber") String serialNumber,
            @RequestParam(value = "image1", required = false) MultipartFile image1,
            @RequestParam(value = "image2", required = false) MultipartFile image2,
            @RequestParam(value = "image3", required = false) MultipartFile image3,
            @RequestParam(value = "image4", required = false) MultipartFile image4,
            @RequestParam(value = "image5", required = false) MultipartFile image5,
            @RequestParam(value = "image6", required = false) MultipartFile image6) {
        try {
            boolean pair = "TSC_SSC".equals(reportType);
            String safeSN = serialNumber.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            boolean snVerified = excelService.checkSN(serialNumber);

            List<byte[]> rawImages = new ArrayList<>();
            for (MultipartFile mf : new MultipartFile[]{image1, image2, image3, image4, image5, image6}) {
                rawImages.add((mf != null && !mf.isEmpty()) ? mf.getBytes() : null);
            }

            // ── Chế độ cặp: 1 nút → 2 file (TSC + SSC); ảnh chỉ xử lý 1 lần ──
            if (pair) {
                if (tscSavePath.isBlank() || sscSavePath.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Cần chọn đủ 2 nơi lưu: TSC và SSC"));
                }
                String tscName = "TrcSC_" + safeSN + ".docx";
                String sscName = "SauSC_" + safeSN + ".docx";
                Path destTsc = storageService.destination(tscSavePath, tscName);
                Path destSsc = storageService.destination(sscSavePath, sscName);
                if (Files.exists(destTsc, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(destSsc, LinkOption.NOFOLLOW_LINKS)) {
                    return duplicatePair(destTsc, destSsc);
                }

                List<byte[]> prepared = wordService.prepareImages(rawImages);
                // TSC = ảnh 1,2,3,4; SSC = ảnh 1,2,5,6 (ảnh xử lý 1 lần, dùng chung)
                List<byte[]> tscImages = pickImages(prepared, 0, 1, 2, 3);
                List<byte[]> sscImages = pickImages(prepared, 0, 1, 4, 5);
                java.util.concurrent.CompletableFuture<byte[]> tscFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        buildQuietly("TRUOC_SCC", deviceName, productCode, poNumber, "",
                            serialNumber, snVerified, tscImages));
                java.util.concurrent.CompletableFuture<byte[]> sscFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        buildQuietly("SAU_SCC", deviceName, productCode, poNumber, repairNotes,
                            serialNumber, snVerified, sscImages));
                byte[] tscBytes = tscFuture.join();
                byte[] sscBytes = sscFuture.join();
                storageService.saveNew(destTsc, tscBytes);
                storageService.saveNew(destSsc, sscBytes);
                return ResponseEntity.ok(Map.of(
                    "success", true, "saved", true, "pair", true,
                    "files", List.of(
                        Map.of("name", tscName, "path", destTsc.toString()),
                        Map.of("name", sscName, "path", destSsc.toString()))));
            }

            // ── Chế độ một file ───────────────────────────────────────
            String wordType = switch (reportType) {
                case "CHAY_NO" -> "CHAY_NO";
                case "TRUOC_SCC" -> "TRUOC_SCC";
                default -> "SAU_SCC";   // SAU_SCC = "Không sửa được"
            };
            String prefix = switch (wordType) {
                case "CHAY_NO" -> "ChayNo";
                case "TRUOC_SCC" -> "TrcSC";
                default -> "SauSC";
            };
            String rawName = prefix + "_" + safeSN + ".docx";
            Path dest = singleSavePath.isBlank() ? null
                : storageService.destination(singleSavePath, rawName);
            // Trả thông báo trước khi xử lý ảnh nếu tên báo cáo đã tồn tại.
            if (dest != null && Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) return duplicateReport(dest);

            List<byte[]> prepared = wordService.prepareImages(rawImages);
            // Cháy Nổ/TSC đơn dùng ảnh 1-4; SSC đơn ("Không sửa được") dùng 1,2,5,6.
            List<byte[]> chosen = ("CHAY_NO".equals(wordType) || "TRUOC_SCC".equals(wordType))
                ? pickImages(prepared, 0, 1, 2, 3)
                : pickImages(prepared, 0, 1, 4, 5);
            byte[] wordBytes = wordService.buildReport(
                wordType, deviceName, productCode, poNumber,
                repairNotes, serialNumber, snVerified, chosen);

            // ── Đã cấu hình nơi lưu: CHỈ LƯU server-side, không download ──
            if (dest != null) {
                try {
                    storageService.saveNew(dest, wordBytes);
                    return ResponseEntity.ok(Map.of(
                        "success",   true,
                        "saved",     true,
                        "filename",  rawName,
                        "savedPath", dest.toString(),
                        "message",   "Đã lưu: " + dest.toString()
                    ));
                } catch (FileAlreadyExistsException e) {
                    return duplicateReport(dest);
                } catch (IOException e) {
                    System.err.println("[SavePath] Lỗi lưu file: " + e.getMessage());
                    return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "error",   "Lỗi lưu file: " + e.getMessage()
                    ));
                }
            }

            // ── Chưa có nơi lưu: trả file để download ──
            String encodedName = URLEncoder.encode(rawName, StandardCharsets.UTF_8)
                .replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedName);
            return new ResponseEntity<>(wordBytes, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }

    private List<byte[]> pickImages(List<byte[]> all, int... indexes) {
        List<byte[]> result = new ArrayList<>();
        for (int index : indexes) {
            result.add(index < all.size() ? all.get(index) : null);
        }
        return result;
    }

    private byte[] buildQuietly(String reportType, String deviceName, String productCode,
            String poNumber, String notes, String serialNumber, boolean snVerified,
            List<byte[]> images) {
        try {
            return wordService.buildReport(reportType, deviceName, productCode, poNumber,
                notes, serialNumber, snVerified, images);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<Map<String, Object>> duplicatePair(Path tsc, Path ssc) {
        List<Map<String, String>> existing = new ArrayList<>();
        if (Files.exists(tsc, LinkOption.NOFOLLOW_LINKS)) {
            existing.add(Map.of("name", tsc.getFileName().toString(), "path", tsc.toString()));
        }
        if (Files.exists(ssc, LinkOption.NOFOLLOW_LINKS)) {
            existing.add(Map.of("name", ssc.getFileName().toString(), "path", ssc.toString()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "success", false, "code", "FILE_EXISTS",
            "error", "File đã tồn tại, vui lòng kiểm tra lại",
            "files", existing));
    }

    private ResponseEntity<Map<String, Object>> duplicateReport(Path path) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "success", false, "code", "FILE_EXISTS",
            "error", "File đã tồn tại, vui lòng kiểm tra lại",
            "filename", path.getFileName().toString(), "savedPath", path.toString()));
    }


    // ── Server-side OCR (ZXing trong Java + ocr_server/ocr.py qua OcrService) ──
    /**
     * /api/ocr — Chiến lược 3 bước tối ưu tốc độ:
     *   Bước 1 (Java, ~30ms): ZXing chỉ fast-exit khi khớp chính xác Excel
     *   Bước 2: OcrService gọi ocr_server.py — daemon nạp sẵn model, không
     *           phải trả phí nạp model mỗi lần quét
     *   Bước 3: daemon không dùng được thì fallback spawn ocr.py như cũ
     */
    @PostMapping("/ocr")
    public ResponseEntity<Map<String, Object>> performOcr(
            @RequestParam("image") MultipartFile image) {
        try {
            byte[] rawBytes = image.getBytes();
            List<String> knownSerials = excelService.getAllSN("");

            // ── BƯỚC 1: ZXing chỉ được phép dừng sớm khi khớp Excel ──────
            List<String> barcodeResults = knownSerials.isEmpty()
                ? List.of()
                : scanBarcodesFromBytes(rawBytes);
            if (!barcodeResults.isEmpty()
                    && barcodeResults.stream().anyMatch(knownSerials::contains)) {
                barcodeResults = barcodeResults.stream().filter(knownSerials::contains).toList();
                return ResponseEntity.ok(Map.of(
                    "success",    true,
                    "candidates", barcodeResults,
                    "method",     "barcode_zxing",
                    "rawText",    String.join(", ", barcodeResults)
                ));
            }

            // ── BƯỚC 2 + 3: Python OCR (daemon trước, CLI fallback sau) ──
            return ResponseEntity.ok(ocrService.ocr(rawBytes, knownSerials));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error",   e.getMessage()
            ));
        }
    }

    /** Chẩn đoán OCR: mở bằng trình duyệt trên máy chủ để xem daemon có chạy không. */
    @GetMapping("/ocr/diagnose")
    public Map<String, Object> ocrDiagnose() {
        return ocrService.diagnose();
    }

    /**
     * ZXing nâng cao: Quét barcode từ raw bytes không cần lưu file.
     * Chiến lược:
     *   1. HybridBinarizer (tốt cho barcode in rõ) — góc 0°, 90°, 180°, 270°
     *   2. GlobalHistogramBinarizer (tốt cho ảnh thấp tương phản / mờ)
     *   3. Đảo màu (inverted) — cho barcode trắng nền tối
     */
    private List<String> scanBarcodesFromBytes(byte[] imageBytes) {
        List<String> results = new ArrayList<>();
        try {
            BufferedImage bi = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            if (bi == null) return results;

            // Chỉ quét vùng giữa của slot 2, nơi người dùng đặt barcode/SN.
            // Giảm diện tích quét giúp barcode không làm chậm OCR khi ảnh không có mã.
            bi = cropSerialCenterForBarcode(bi);

            // Resize nếu ảnh quá lớn (ZXing nhanh hơn với ảnh nhỏ hơn)
            final int MAX_DIM = 1000;
            int w = bi.getWidth(), h = bi.getHeight();
            if (Math.max(w, h) > MAX_DIM) {
                double ratio = (double) MAX_DIM / Math.max(w, h);
                int nw = (int)(w * ratio), nh = (int)(h * ratio);
                java.awt.image.BufferedImage sc = new java.awt.image.BufferedImage(
                    nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
                sc.getGraphics().drawImage(bi, 0, 0, nw, nh, null);
                bi = sc;
            }

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,   BarcodeFormat.EAN_8,
                BarcodeFormat.QR_CODE,  BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.PDF_417,  BarcodeFormat.AZTEC
            ));
            MultiFormatReader reader = new MultiFormatReader();

            int[] rotations = {0, 90, 180, 270};

            // Chiến lược 1: HybridBinarizer (ảnh rõ, tương phản cao)
            outer:
            for (int deg : rotations) {
                try {
                    BufferedImage toScan = deg == 0 ? bi : rotateImage(bi, deg);
                    LuminanceSource src = new BufferedImageLuminanceSource(toScan);
                    Result res = reader.decode(
                        new BinaryBitmap(new HybridBinarizer(src)), hints);
                    String clean = res.getText().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                    if (clean.length() >= 6) { results.add(clean); break outer; }
                } catch (NotFoundException ignored) {}
            }

            if (!results.isEmpty()) return results;

            // Chiến lược 2: GlobalHistogramBinarizer (ảnh mờ, thấp tương phản)
            outer2:
            for (int deg : rotations) {
                try {
                    BufferedImage toScan = deg == 0 ? bi : rotateImage(bi, deg);
                    LuminanceSource src = new BufferedImageLuminanceSource(toScan);
                    Result res = reader.decode(
                        new BinaryBitmap(new com.google.zxing.common.GlobalHistogramBinarizer(src)), hints);
                    String clean = res.getText().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                    if (clean.length() >= 6) { results.add(clean); break outer2; }
                } catch (NotFoundException ignored) {}
            }

            if (!results.isEmpty()) return results;

            // Chiến lược 3: Đảo màu (barcode trắng nền tối)
            BufferedImage inverted = invertImage(bi);
            for (int deg : rotations) {
                try {
                    BufferedImage toScan = deg == 0 ? inverted : rotateImage(inverted, deg);
                    LuminanceSource src = new BufferedImageLuminanceSource(toScan);
                    Result res = reader.decode(
                        new BinaryBitmap(new HybridBinarizer(src)), hints);
                    String clean = res.getText().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                    if (clean.length() >= 6) { results.add(clean); break; }
                } catch (NotFoundException ignored) {}
            }

        } catch (Exception e) {
            System.err.println("[ZXing] Lỗi: " + e.getMessage());
        }
        return results;
    }

    private BufferedImage cropSerialCenterForBarcode(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 200 || height < 200) return source;

        int left = Math.round(width * 0.08f);
        int top = Math.round(height * 0.24f);
        int cropWidth = Math.max(1, Math.round(width * 0.84f));
        int cropHeight = Math.max(1, Math.round(height * 0.52f));
        return source.getSubimage(left, top,
            Math.min(cropWidth, width - left), Math.min(cropHeight, height - top));
    }

    /** Đảo màu BufferedImage (dùng cho barcode trắng nền tối) */
    private BufferedImage invertImage(BufferedImage src) {
        BufferedImage inv = new BufferedImage(
            src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = inv.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        for (int y = 0; y < inv.getHeight(); y++) {
            for (int x = 0; x < inv.getWidth(); x++) {
                int rgb = inv.getRGB(x, y);
                inv.setRGB(x, y,
                    ((~rgb) & 0x00FFFFFF) | (rgb & 0xFF000000));
            }
        }
        return inv;
    }

    /** Xoay BufferedImage theo số độ (90, 180, 270) */
    private BufferedImage rotateImage(BufferedImage src, int degrees) {
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = (degrees == 90 || degrees == 270);
        BufferedImage dest = new BufferedImage(
            swap ? h : w, swap ? w : h, src.getType());
        java.awt.Graphics2D g = dest.createGraphics();
        g.translate(swap ? h : w, swap ? 0 : (degrees == 180 ? h : 0));
        if (degrees == 270) g.translate(0, w);
        g.rotate(Math.toRadians(degrees));
        if (degrees == 270) g.translate(-w, 0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dest;
    }
}
