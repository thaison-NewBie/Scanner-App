package com.scanner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class NasApiTest {
    static Path root;
    static Path images;
    static Path reports;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) throws Exception {
        root = Files.createTempDirectory("scanner-nas-test-");
        images = Files.createDirectory(root.resolve("images"));
        reports = Files.createDirectory(root.resolve("reports"));
        properties.add("scanner.storage.restricted", () -> true);
        properties.add("scanner.storage.images-root", images::toString);
        properties.add("scanner.storage.reports-root", reports::toString);
        // Không spawn daemon ocr_server.py khi chạy test.
        properties.add("scanner.ocr.server-enabled", () -> false);
    }

    @AfterAll static void cleanup() throws Exception {
        if (root != null) {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    void chooseSave(MockHttpSession session, Path path) throws Exception {
        mvc.perform(post("/api/config/save-path").session(session).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("path", path.toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.path").value(path.toString()));
    }

    MockHttpServletRequestBuilder report(String sn) {
        return multipart("/api/report/generate").param("deviceName", "Thiết bị thử")
            .param("productCode", "TEST").param("poNumber", "PO-TEST")
            .param("reportType", "TRUOC_SCC").param("serialNumber", sn);
    }

    @Test void reportIsCreatedAndDuplicateReturns409WithoutChangingOriginal() throws Exception {
        var session = new MockHttpSession();
        chooseSave(session, reports);
        mvc.perform(report("12345").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true));
        Path dest = reports.resolve("TrcSC_12345.docx");
        byte[] original = Files.readAllBytes(dest);
        assertTrue(original.length > 500);
        mvc.perform(report("12345").session(session)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FILE_EXISTS"))
            .andExpect(jsonPath("$.error").value("File đã tồn tại, vui lòng kiểm tra lại"));
        assertArrayEquals(original, Files.readAllBytes(dest));
    }

    @Test void nextImageUsesTheNextExistingFilenameInsteadOfIncrementing() throws Exception {
        Files.write(images.resolve("IMG_3428.jpg"), new byte[]{1});
        Files.write(images.resolve("IMG_3430.jpg"), new byte[]{1});
        Files.write(images.resolve("IMG_3431.jpg"), new byte[]{1});

        mvc.perform(get("/api/next-image").param("folder", images.toString())
                .param("after", "IMG_3428.jpg"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.name").value("IMG_3430.jpg"));
    }

    @Test void nasEndpointsRejectOtherDisks() throws Exception {
        mvc.perform(get("/api/browse").param("purpose", "reports"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].path").value(reports.toString()));
        mvc.perform(get("/api/browse").param("path", root.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/folder-images").param("path", root.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/random-image").param("path", root.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/image").param("path", root.resolve("secret.jpg").toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/config/save-path").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("path", images.toString()))))
            .andExpect(status().isBadRequest());
    }

    @Test void refreshingPhoneDoesNotClearAnotherBrowsersExcelOrSaveFolder() throws Exception {
        var laptop = new MockHttpSession();
        var phone = new MockHttpSession();
        chooseSave(laptop, reports);
        byte[] excel;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("SN");
            sheet.createRow(1).createCell(0).setCellValue("SERIAL001");
            workbook.write(output);
            excel = output.toByteArray();
        }
        mvc.perform(multipart("/api/excel/upload").file(new MockMultipartFile("file", "PO.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel)).session(laptop))
            .andExpect(status().isOk());
        mvc.perform(post("/api/session/reset").session(phone)).andExpect(status().isOk());
        mvc.perform(get("/api/status").session(laptop)).andExpect(jsonPath("$.snLoaded").value(1))
            .andExpect(jsonPath("$.savePath").value(reports.toString()));
        mvc.perform(get("/api/status").session(phone)).andExpect(jsonPath("$.snLoaded").value(0))
            .andExpect(jsonPath("$.savePath").value(""));
        // Chưa chọn nơi lưu thì trả file Word để trình duyệt tự tải xuống.
        mvc.perform(report("NO_DEST").session(phone)).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        mvc.perform(post("/api/session/reset").session(laptop)).andExpect(status().isOk());
        mvc.perform(get("/api/status").session(laptop)).andExpect(jsonPath("$.snLoaded").value(0))
            .andExpect(jsonPath("$.savePath").value(""));
    }
}
