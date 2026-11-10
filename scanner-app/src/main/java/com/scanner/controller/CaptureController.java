package com.scanner.controller;

import com.scanner.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

/**
 * Tự lưu ảnh chụp từ camera vào thư mục lưu trữ trên máy chạy app,
 * để lần sau có thể chọn lại qua bộ chọn thư mục ảnh.
 */
@RestController
@RequestMapping("/api")
public class CaptureController {

    @Autowired private StorageService storageService;

    @PostMapping("/capture/save")
    public ResponseEntity<Map<String, Object>> saveCapture(
            @RequestParam("image") MultipartFile image,
            @RequestParam(defaultValue = "1") int slot,
            @RequestParam(defaultValue = "") String serialNumber) {
        try {
            String ext = "jpg";
            String name = image.getOriginalFilename();
            if (name != null && name.lastIndexOf('.') >= 0) {
                ext = name.substring(name.lastIndexOf('.') + 1);
            }
            Path saved = storageService.saveCapture(slot, serialNumber, image.getBytes(), ext);
            return ResponseEntity.ok(Map.of(
                "success",  true,
                "saved",    true,
                "filename", saved.getFileName().toString(),
                "path",     saved.toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false, "error", e.getMessage()));
        }
    }
}
