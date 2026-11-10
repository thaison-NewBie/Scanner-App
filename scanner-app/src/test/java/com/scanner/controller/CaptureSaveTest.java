package com.scanner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Ảnh chụp từ camera được tự lưu vào captures-root với tên không ghi đè,
 * chứa slot và SN đã làm sạch; file không phải ảnh bị từ chối.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptureSaveTest {

    static Path captures;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        try {
            captures = Files.createTempDirectory("scanner-captures-");
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
        properties.add("scanner.storage.restricted", () -> false);
        properties.add("scanner.storage.captures-root", captures::toString);
        // Không spawn daemon ocr_server.py khi chạy test.
        properties.add("scanner.ocr.server-enabled", () -> false);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (captures != null) {
            try (var paths = Files.walk(captures)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void savingCaptureReturnsPathAndCreatesFile() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        String body = mvc.perform(multipart("/api/capture/save")
                .file(new MockMultipartFile("image", "photo.jpg", "image/jpeg", jpeg))
                .param("slot", "2")
                .param("serialNumber", "SN 123/45"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.filename").value(org.hamcrest.Matchers.containsString("slot2")))
            .andExpect(jsonPath("$.filename").value(org.hamcrest.Matchers.containsString("SN_123_45")))
            .andExpect(jsonPath("$.filename").value(org.hamcrest.Matchers.endsWith(".jpg")))
            .andReturn().getResponse().getContentAsString();
        String filename = json.readTree(body).get("filename").asText();
        assertTrue(Files.exists(captures.resolve(filename)));
    }

    @Test
    void savingCaptureRejectsNonImageExtension() throws Exception {
        mvc.perform(multipart("/api/capture/save")
                .file(new MockMultipartFile("image", "note.txt", "text/plain", "hello".getBytes()))
                .param("slot", "1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void storageConfigExposesCapturesRoot() throws Exception {
        mvc.perform(get("/api/storage/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.capturesRoot").value(captures.toString()));
    }
}
