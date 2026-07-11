package com.scanner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.scanner.service.ExcelService;
import com.scanner.service.WordService;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;


@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private ExcelService excelService;
    @Autowired private WordService wordService;

    // Lưu save path trong bộ nhớ (test mode)
    private static String savePath = "";

    // ── Status ────────────────────────────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "status", "OK",
            "snLoaded", excelService.getLoadedCount(),
            "savePath", savePath
        ));
    }

    // ── Browse Folders ────────────────────────────────────────────
    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browse(
            @RequestParam(defaultValue = "") String path) {
        try {
            List<Map<String, String>> entries = new ArrayList<>();

            if (path.isEmpty()) {
                // Trả về danh sách ổ đĩa (C:\, D:\, ...)
                for (File root : File.listRoots()) {
                    entries.add(Map.of(
                        "name", root.getAbsolutePath(),
                        "path", root.getAbsolutePath(),
                        "type", "drive"
                    ));
                }
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "current", "",
                    "parent",  "",
                    "entries", entries
                ));
            }

            Path current = Paths.get(path);
            if (!Files.exists(current) || !Files.isDirectory(current)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Thư mục không tồn tại"));
            }

            // Lấy danh sách thư mục con (bỏ qua hidden/system folders)
            File[] files = current.toFile().listFiles();
            if (files != null) {
                for (File sub : files) {
                    try {
                        if (sub.isDirectory() && !sub.isHidden()) {
                            entries.add(Map.of(
                                "name", sub.getName(),
                                "path", sub.getAbsolutePath(),
                                "type", "folder"
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }

            entries.sort(Comparator.comparing(m -> m.get("name").toLowerCase()));

            // Parent path
            String parent = current.getParent() != null
                ? current.getParent().toString() : "";

            return ResponseEntity.ok(Map.of(
                "success", true,
                "current", current.toAbsolutePath().toString(),
                "parent",  parent,
                "entries", entries
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }

    // ── Set Save Path ─────────────────────────────────────────────
    @PostMapping("/config/save-path")
    public ResponseEntity<Map<String, Object>> setSavePath(
            @RequestBody Map<String, String> body) {
        String path = body.getOrDefault("path", "").trim();
        if (path.isEmpty()) {
            savePath = "";
            return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa nơi lưu file"));
        }
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
            if (!Files.isDirectory(p)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Đường dẫn không phải thư mục"));
            }
            if (!Files.isWritable(p)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Không có quyền ghi vào thư mục này"));
            }
            savePath = path;
            return ResponseEntity.ok(Map.of("success", true,
                "message", "Đã chọn: " + path,
                "path", path));
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "Đường dẫn không hợp lệ: " + e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", "Lỗi tạo thư mục: " + e.getMessage()));
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
        "      IntPtr pDlg; if(CoCreateInstance(ref clsid,IntPtr.Zero,1,ref iid,out pDlg)!=0) return null;\n" +
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
        "      if(dlg.Show(IntPtr.Zero)!=0){ Marshal.Release(pDlg); return null; }\n" +
        "      IntPtr pRes; dlg.GetResult(out pRes);\n" +
        "      var item=(IShellItem)Marshal.GetObjectForIUnknown(pRes);\n" +
        "      string path; item.GetDisplayName(0x80028000,out path);\n" +
        "      Marshal.Release(pRes); Marshal.Release(pDlg); return path;\n" +
        "    } catch { return null; }\n" +
        "  }\n" +
        "}\n";

    private String getPickerDll() throws Exception {
        String dllPath = System.getProperty("java.io.tmpdir") + "ScannerFP_v6.dll";
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
                new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", compilePsFile.toString())
                    .redirectErrorStream(true).start().waitFor();
                Files.deleteIfExists(csFile);
                Files.deleteIfExists(compilePsFile);
            }
            cachedDllPath = dllPath;
        }
        return dllPath;
    }

    @GetMapping("/native-pick-folder")
    public ResponseEntity<Map<String, Object>> nativePickFolder(
            @RequestParam(defaultValue = "") String initial) {
        return runNativePicker("Chọn thư mục", initial, "", true);
    }

    @GetMapping("/native-pick-file")
    public ResponseEntity<Map<String, Object>> nativePickFile(
            @RequestParam(defaultValue = "") String initial,
            @RequestParam(defaultValue = "") String suggested) {
        return runNativePicker("Chọn tệp hình ảnh", initial, suggested, false);
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
            Files.writeString(tmpScript, ps, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", tmpScript.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String result = new String(proc.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            proc.waitFor();

            if (result.equals("__CANCELLED__") || result.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "cancelled", true));
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
            Path dir = Paths.get(path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Thư mục không tồn tại"));
            }
            List<String> images = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(dir, p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".png") || n.endsWith(".bmp") || n.endsWith(".webp");
            })) {
                for (Path img : stream) images.add(img.toAbsolutePath().toString());
            }
            Collections.sort(images);
            return ResponseEntity.ok(Map.of(
                "success", true, "images", images, "count", images.size()));
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
            Path dir = Paths.get(path);
            List<Path> images = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(dir, p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
            })) {
                for (Path img : stream) images.add(img);
            }
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
            Path p = Paths.get(path);
            if (!Files.exists(p) || Files.isDirectory(p)) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(p);
            String name = p.getFileName().toString().toLowerCase();
            MediaType mt = name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
            return ResponseEntity.ok().contentType(mt).body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Generate Word ──────────────────────────────────────────────
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
            @RequestParam(value = "image4", required = false) MultipartFile image4) {
        try {
            boolean snVerified = excelService.checkSN(serialNumber);

            // Convert MultipartFile → byte[] (để WordService xử lý song song)
            List<byte[]> imageBytesList = new ArrayList<>();
            for (MultipartFile mf : new MultipartFile[]{image1, image2, image3, image4}) {
                imageBytesList.add((mf != null && !mf.isEmpty()) ? mf.getBytes() : null);
            }

            byte[] wordBytes = wordService.generateReport(
                deviceName, productCode, poNumber,
                reportType, repairNotes, serialNumber, snVerified, imageBytesList
            );

            // Tên file
            String safeSN = serialNumber.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String prefix = switch (reportType) {
                case "TRUOC_SCC" -> "TrcSC";
                case "SAU_SCC"   -> "SauSC";
                case "CHAY_NO"   -> "ChayNo";
                default          -> reportType;
            };
            String rawName = prefix + "_" + safeSN + ".docx";

            // ── Đã cấu hình savePath: CHỈ LƯU server-side, không download ──
            if (!savePath.isEmpty()) {
                try {
                    Path dest = Paths.get(savePath, rawName);
                    Files.write(dest, wordBytes, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                    return ResponseEntity.ok(Map.of(
                        "success",   true,
                        "saved",     true,
                        "filename",  rawName,
                        "savedPath", dest.toString(),
                        "message",   "Đã lưu: " + dest.toString()
                    ));
                } catch (IOException e) {
                    System.err.println("[SavePath] Lỗi lưu file: " + e.getMessage());
                    return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "error",   "Lỗi lưu file: " + e.getMessage()
                    ));
                }
            }

            // ── Chưa có savePath: trả file để download ──
            String encodedName = URLEncoder.encode(rawName, StandardCharsets.UTF_8)
                .replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedName);
            return new ResponseEntity<>(wordBytes, headers, HttpStatus.OK);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }


    // ── Server-side OCR (ZXing Barcode + PaddleOCR via Python) ──────
    private static final String PYTHON_EXE =
        "C:\\Users\\Lecoo\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private static final String OCR_SCRIPT =
        Paths.get(System.getProperty("user.dir"), "ocr.py").toString();

    private static final ObjectMapper JSON = new ObjectMapper();

    // Timeout tối đa cho Python OCR (ms)
    private static final long OCR_TIMEOUT_MS = 25_000;

    /**
     * /api/ocr — Chiến lược 3 bước tối ưu tốc độ:
     *   Bước 1 (Java, ~30ms): ZXing dual-binarizer + multi-angle + inverted-color
     *   Bước 2 (Java, ~20ms): Preprocess ảnh (resize, sharpen) → lưu file tối ưu
     *   Bước 3 (Python, nếu cần): PaddleOCR với timeout 25s
     */
    @PostMapping("/ocr")
    public ResponseEntity<Map<String, Object>> performOcr(
            @RequestParam("image") MultipartFile image) {
        Path tempImage = null;
        try {
            byte[] rawBytes = image.getBytes();

            // ── BƯỚC 1: ZXing trực tiếp trên bytes (không cần lưu file) ──
            List<String> barcodeResults = scanBarcodesFromBytes(rawBytes);
            if (!barcodeResults.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "success",    true,
                    "candidates", barcodeResults,
                    "method",     "barcode_zxing",
                    "rawText",    String.join(", ", barcodeResults)
                ));
            }

            // ── BƯỚC 2: Preprocess + lưu ảnh tối ưu cho OCR ─────────────
            tempImage = Files.createTempFile("ocr_", ".jpg");
            preprocessAndSave(rawBytes, tempImage);

            // ── BƯỚC 3: Python PaddleOCR với timeout ─────────────────────
            ProcessBuilder pb = new ProcessBuilder(
                PYTHON_EXE, OCR_SCRIPT,
                tempImage.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Đọc stdout + stderr song song để tránh deadlock
            byte[] stdoutBytes, stderrBytes;
            try (var is = process.getInputStream();
                 var es = process.getErrorStream()) {
                // CompletableFuture đọc song song hai stream
                var stdoutFut = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return is.readAllBytes(); }
                    catch (Exception ex) { return new byte[0]; }
                });
                var stderrFut = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return es.readAllBytes(); }
                    catch (Exception ex) { return new byte[0]; }
                });
                boolean finished = process.waitFor(
                    OCR_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return ResponseEntity.ok(Map.of(
                        "success",    false,
                        "candidates", List.of(),
                        "method",     "timeout",
                        "error",      "OCR quá thời gian " + (OCR_TIMEOUT_MS/1000) + "s"
                    ));
                }
                stdoutBytes = stdoutFut.join();
                stderrBytes = stderrFut.join();
            }

            String stdout = new String(stdoutBytes, StandardCharsets.UTF_8).trim();
            String stderr = new String(stderrBytes, StandardCharsets.UTF_8).trim();
            int exitCode  = process.exitValue();

            if (!stderr.isEmpty())
                System.err.println("[OCR-PY] " + stderr);

            if (exitCode != 0) {
                return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error",   "ocr.py thoát với mã lỗi " + exitCode
                ));
            }

            // Parse JSON từ ocr.py
            List<String> candidates = new ArrayList<>();
            String method = "ocr";
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> pyResult = JSON.readValue(stdout, Map.class);
                @SuppressWarnings("unchecked")
                List<String> pyCandidates = (List<String>) pyResult.getOrDefault("candidates", List.of());
                candidates.addAll(pyCandidates);
                method = (String) pyResult.getOrDefault("method", "ocr");
            } catch (Exception parseEx) {
                if (!stdout.isEmpty() && !stdout.equals("NOT_FOUND")) candidates.add(stdout);
            }

            return ResponseEntity.ok(Map.of(
                "success",    true,
                "candidates", candidates,
                "method",     method,
                "rawText",    stdout
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error",   e.getMessage()
            ));
        } finally {
            if (tempImage != null)
                try { Files.deleteIfExists(tempImage); } catch (Exception ignored) {}
        }
    }

    /**
     * Preprocess ảnh trong Java trước khi gửi sang Python:
     * - Resize về tối đa 1800px để giảm thời gian OCR
     * - Lưu dưới dạng JPEG chất lượng cao
     */
    private void preprocessAndSave(byte[] rawBytes, Path dest) throws Exception {
        try (var bais = new java.io.ByteArrayInputStream(rawBytes)) {
            BufferedImage bi = ImageIO.read(bais);
            if (bi == null) {
                // Fallback: lưu nguyên bytes
                Files.write(dest, rawBytes);
                return;
            }

            // Resize nếu ảnh quá lớn
            final int MAX_DIM = 1800;
            int w = bi.getWidth(), h = bi.getHeight();
            if (Math.max(w, h) > MAX_DIM) {
                double ratio = (double) MAX_DIM / Math.max(w, h);
                int nw = (int)(w * ratio), nh = (int)(h * ratio);
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(bi, 0, 0, nw, nh, null);
                g2.dispose();
                bi = scaled;
            }

            // Lưu JPEG chất lượng 92%
            var jpegParams = ImageIO.getImageWritersByFormatName("jpeg").next();
            var jpegWriteParam = jpegParams.getDefaultWriteParam();
            jpegWriteParam.setCompressionMode(
                javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            jpegWriteParam.setCompressionQuality(0.92f);
            try (var ios = ImageIO.createImageOutputStream(dest.toFile())) {
                jpegParams.setOutput(ios);
                jpegParams.write(null,
                    new javax.imageio.IIOImage(bi, null, null), jpegWriteParam);
            } finally {
                jpegParams.dispose();
            }
        }
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

            // Resize nếu ảnh quá lớn (ZXing nhanh hơn với ảnh nhỏ hơn)
            final int MAX_DIM = 1200;
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
