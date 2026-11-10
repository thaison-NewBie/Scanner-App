package com.scanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * Chạy OCR Python cho /api/ocr.
 *
 * Ưu tiên ocr_server.py — daemon HTTP trên localhost nạp model ONNX MỘT lần:
 * mỗi lần quét chỉ mất thời gian inference (khoảng 1-3s) thay vì phải trả
 * thêm 4-8s nạp model như khi spawn ocr.py từng lần.
 *
 * Nếu daemon không khởi động được (thiếu Python, lỗi model...) thì fallback
 * về spawn ocr.py như hành vi cũ.
 */
@Service
public class OcrService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private static final long SERVER_READY_TIMEOUT_MS = 120_000;
    private static final long SERVER_CALL_TIMEOUT_MS = 45_000;
    private static final long CLI_TIMEOUT_MS = 60_000;

    private final boolean serverEnabled;
    private final int serverPort;
    private final String pythonExe;
    private final Path ocrScript;
    private final Path ocrServerScript;
    private final Path ocrServerLog;

    private static volatile Process serverProcess;
    private static final Object SERVER_LOCK = new Object();

    public OcrService(
            @Value("${scanner.ocr.server-enabled:true}") boolean serverEnabled,
            @Value("${scanner.ocr.server-port:18081}") int serverPort) {
        this.serverEnabled = serverEnabled;
        this.serverPort = serverPort;
        this.pythonExe = resolvePythonExecutable();
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        this.ocrScript = userDir.resolve("ocr.py");
        this.ocrServerScript = userDir.resolve("ocr_server.py");
        this.ocrServerLog = userDir.resolve("ocr-server.log");
    }

    /**
     * Nạp sẵn model ngay khi app khởi động xong (chạy nền) để lần quét đầu
     * tiên không phải chờ nạp model. Không chặn khởi động của Spring.
     */
    @org.springframework.context.event.EventListener(
        org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void prewarm() {
        if (!serverEnabled) {
            System.err.println("[OCR-SERVER] Tắt bằng scanner.ocr.server-enabled=false");
            return;
        }
        if (!Files.exists(ocrServerScript)) {
            System.err.println("[OCR-SERVER] Thiếu ocr_server.py tại " + ocrServerScript);
            return;
        }
        Thread thread = new Thread(this::ensureServerReady, "ocr-server-prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    /** Thông tin chẩn đoán cho /api/ocr/diagnose (mở bằng trình duyệt trên máy chủ). */
    public Map<String, Object> diagnose() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("serverEnabled", serverEnabled);
        info.put("serverPort", serverPort);
        info.put("userDir", System.getProperty("user.dir"));
        info.put("pythonExe", pythonExe);
        info.put("pythonExists", Files.exists(Paths.get(pythonExe)));
        info.put("ocrServerScript", ocrServerScript.toString());
        info.put("ocrServerScriptExists", Files.exists(ocrServerScript));
        info.put("ocrScriptExists", Files.exists(ocrScript));
        Process process = serverProcess;
        info.put("daemonAlive", process != null && process.isAlive());
        info.put("healthStatus", healthStatus());
        info.put("lastHealthError", lastHealthError);
        info.put("cooldownMs", Math.max(0, serverCooldownUntil - System.currentTimeMillis()));
        info.put("daemonLog", ocrServerLog.toString());
        info.put("daemonLogExists", Files.exists(ocrServerLog));
        if (Files.exists(ocrServerLog)) {
            try {
                List<String> lines = Files.readAllLines(ocrServerLog, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 25);
                info.put("daemonLogTail", lines.subList(from, lines.size()));
            } catch (Exception e) {
                info.put("daemonLogTail", List.of("Không đọc được: " + e.getMessage()));
            }
        }
        return info;
    }

    private static String resolvePythonExecutable() {
        String configured = System.getenv("SCANNER_PYTHON");
        if (configured != null && !configured.isBlank()) return configured;
        for (String relative : List.of(".venv/Scripts/python.exe", ".venv/bin/python")) {
            Path executable = Paths.get(System.getProperty("user.dir"), relative);
            if (Files.isRegularFile(executable)) return executable.toString();
        }
        return "python";
    }

    /**
     * Quét OCR một ảnh. Luôn trả map hợp lệ cho frontend:
     * {success, candidates, method, rawText} hoặc {success:false, error}.
     */
    public Map<String, Object> ocr(byte[] imageBytes, List<String> knownSerials) {
        if (serverEnabled && Files.exists(ocrServerScript)
                && System.currentTimeMillis() >= serverCooldownUntil) {
            try {
                Map<String, Object> result = ocrViaServer(imageBytes, knownSerials, true);
                if (result != null) {
                    logResult(result, "daemon");
                    return result;
                }
                // Daemon không dùng được: thử lại sau 2 phút thay vì chờ mỗi lần quét.
                serverCooldownUntil = System.currentTimeMillis() + 120_000;
            } catch (Exception ignored) {
                serverCooldownUntil = System.currentTimeMillis() + 120_000;
            }
        }
        Map<String, Object> result = ocrViaCli(imageBytes, knownSerials);
        logResult(result, "cli");
        return result;
    }

    private volatile long serverCooldownUntil = 0;

    private void logResult(Map<String, Object> result, String via) {
        String method = String.valueOf(result.getOrDefault("method", "?"));
        int count = result.get("candidates") instanceof Collection<?> collection ? collection.size() : 0;
        Object error = result.get("error");
        System.err.println("[OCR] via=" + via + " method=" + method
            + " candidates=" + count + " ms=" + result.getOrDefault("elapsed_ms", "?")
            + (error == null ? "" : " error=" + error));
    }

    // ── Daemon ocr_server.py ────────────────────────────────────────────────

    private Map<String, Object> ocrViaServer(byte[] imageBytes, List<String> knownSerials, boolean retryOnce) {
        if (!ensureServerReady()) return null;
        Map<String, Object> payload = new HashMap<>();
        payload.put("image", Base64.getEncoder().encodeToString(imageBytes));
        payload.put("serials", knownSerials == null ? List.of() : knownSerials);
        try {
            String body = JSON.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + "/ocr"))
                .timeout(Duration.ofMillis(SERVER_CALL_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(response.body(), Map.class);
            return toResponse(parsed);
        } catch (ConnectException e) {
            if (retryOnce) {
                killServer();
                return ocrViaServer(imageBytes, knownSerials, false);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toResponse(Map<String, Object> parsed) {
        Object error = parsed.get("error");
        List<String> candidates = toStringList(parsed.get("candidates"));
        String method = Objects.toString(parsed.getOrDefault("method", "ocr"), "ocr");
        Map<String, Object> result = new LinkedHashMap<>();
        if (error != null && !String.valueOf(error).isBlank() && !"null".equals(String.valueOf(error))) {
            result.put("success", false);
            result.put("candidates", List.of());
            result.put("error", String.valueOf(error));
        } else {
            result.put("success", true);
            result.put("candidates", candidates);
            result.put("method", method);
            result.put("rawText", String.join(", ", candidates));
        }
        if (parsed.get("elapsed_ms") != null) result.put("elapsed_ms", parsed.get("elapsed_ms"));
        return result;
    }

    private boolean ensureServerReady() {
        if (serverProcess != null && serverProcess.isAlive()) {
            int status = healthStatus();
            if (status == 200) return true;
            if (status == 500) {
                System.err.println("[OCR-SERVER] Engine lỗi: " + lastHealthError);
                killServer();
                return false;
            }
            killServer();
        }
        synchronized (SERVER_LOCK) {
            if (serverProcess != null && serverProcess.isAlive()) return healthStatus() == 200;
            if (!Files.exists(ocrServerScript)) {
                System.err.println("[OCR-SERVER] Thiếu ocr_server.py tại " + ocrServerScript);
                return false;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    pythonExe, ocrServerScript.toString(), "--port", String.valueOf(serverPort));
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(ocrServerLog.toFile()));
                serverProcess = pb.start();
            } catch (IOException e) {
                System.err.println("[OCR-SERVER] Không khởi động được: " + e.getMessage());
                return false;
            }
            long deadline = System.currentTimeMillis() + SERVER_READY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!serverProcess.isAlive()) {
                    System.err.println("[OCR-SERVER] Tiến trình thoát sớm; xem " + ocrServerLog);
                    killServer();
                    return false;
                }
                int status = healthStatus();
                if (status == 200) return true;
                if (status == 500) {
                    // Engine báo lỗi nạp model (thiếu thư viện, model hỏng...):
                    // dừng chờ ngay thay vì treo cả phút.
                    System.err.println("[OCR-SERVER] Engine lỗi: " + lastHealthError);
                    killServer();
                    return false;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            System.err.println("[OCR-SERVER] Quá thời gian chờ sẵn sàng; dùng CLI fallback.");
            return false;
        }
    }

    private volatile String lastHealthError = null;

    private int healthStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 500) lastHealthError = response.body();
            return status;
        } catch (Exception e) {
            return 0;
        }
    }

    private void killServer() {
        Process process = serverProcess;
        serverProcess = null;
        if (process != null && process.isAlive()) process.destroy();
    }

    // ── Fallback: spawn ocr.py từng lần (hành vi cũ) ──────────────────────

    private Map<String, Object> ocrViaCli(byte[] imageBytes, List<String> knownSerials) {
        Path tempImage = null;
        Path tempSerials = null;
        try {
            tempImage = Files.createTempFile("ocr_", ".jpg");
            Files.write(tempImage, imageBytes);
            tempSerials = Files.createTempFile("ocr_serials_", ".json");
            Files.writeString(tempSerials, JSON.writeValueAsString(knownSerials), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                pythonExe, ocrScript.toString(),
                tempImage.toAbsolutePath().toString(), tempSerials.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            byte[] stdoutBytes, stderrBytes;
            try (var is = process.getInputStream(); var es = process.getErrorStream()) {
                var stdoutFut = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return is.readAllBytes(); } catch (Exception ex) { return new byte[0]; }
                });
                var stderrFut = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return es.readAllBytes(); } catch (Exception ex) { return new byte[0]; }
                });
                boolean finished = process.waitFor(CLI_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    Map<String, Object> timeout = new LinkedHashMap<>();
                    timeout.put("success", false);
                    timeout.put("candidates", List.of());
                    timeout.put("method", "timeout");
                    timeout.put("error", "OCR quá thời gian " + (CLI_TIMEOUT_MS / 1000) + "s");
                    return timeout;
                }
                stdoutBytes = stdoutFut.join();
                stderrBytes = stderrFut.join();
            }

            String stdout = new String(stdoutBytes, StandardCharsets.UTF_8).trim();
            String stderr = new String(stderrBytes, StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();
            if (!stderr.isEmpty()) System.err.println("[OCR-PY] " + stderr);
            if (exitCode != 0) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("success", false);
                failed.put("error", "ocr.py thoát với mã lỗi " + exitCode);
                return failed;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> pyResult = JSON.readValue(stdout, Map.class);
            return toResponse(pyResult);
        } catch (Exception e) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("success", false);
            failed.put("error", e.getMessage());
            return failed;
        } finally {
            if (tempImage != null) { try { Files.deleteIfExists(tempImage); } catch (Exception ignored) {} }
            if (tempSerials != null) { try { Files.deleteIfExists(tempSerials); } catch (Exception ignored) {} }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : collection) if (item != null) result.add(String.valueOf(item));
        return result;
    }
}
