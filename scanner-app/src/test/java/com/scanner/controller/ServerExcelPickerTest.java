package com.scanner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bộ chọn Excel trên máy chủ phải duyệt được thư mục nhưng không vượt
 * khỏi phạm vi storage đã cấu hình, và chỉ nhận đúng file .xlsx/.xls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServerExcelPickerTest {

    static Path root;
    static Path images;
    static Path reports;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("scanner.storage.restricted", () -> true);
        properties.add("scanner.storage.images-root", images::toString);
        properties.add("scanner.storage.reports-root", reports::toString);
    }

    // Thư mục và file mẫu phải có trước khi Spring nạp context, nên tạo ở static
    // initializer thay vì @BeforeAll (Spring nạp context sau @BeforeAll).
    static {
        try {
            root = Files.createTempDirectory("scanner-excel-picker-");
            images = Files.createDirectory(root.resolve("images"));
            reports = Files.createDirectory(root.resolve("reports"));
            Files.createDirectory(reports.resolve("PO-2026"));
            try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("PO");
                sheet.createRow(0).createCell(0).setCellValue("SN");
                sheet.createRow(1).createCell(0).setCellValue("SN0001");
                sheet.createRow(2).createCell(0).setCellValue("SN0002");
                workbook.write(output);
                Files.write(reports.resolve("PO.xlsx"), output.toByteArray());
            }
            Files.writeString(reports.resolve("ghi-chu.txt"), "không phải Excel");
            Files.writeString(root.resolve("ngoai-pham-vi.xlsx"), "không được đọc");
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void browseListsFoldersAndExcelFilesInConfiguredRoot() throws Exception {
        mvc.perform(get("/api/excel/browse").param("path", reports.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.current").value(reports.toString()))
            .andExpect(jsonPath("$.entries[0].name").value("PO-2026"))
            .andExpect(jsonPath("$.files.length()").value(1))
            .andExpect(jsonPath("$.files[0].name").value("PO.xlsx"))
            .andExpect(jsonPath("$.files[0].path").value(reports.resolve("PO.xlsx").toString()));
    }

    @Test
    void browseWithoutPathStartsAtConfiguredReportsRoot() throws Exception {
        mvc.perform(get("/api/excel/browse"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].path").value(reports.toString()))
            .andExpect(jsonPath("$.files.length()").value(0));
    }

    @Test
    void loadingSelectedWorkbookReturnsItsSerialNumbers() throws Exception {
        mvc.perform(post("/api/excel/load-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("path", reports.resolve("PO.xlsx").toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.filename").value("PO.xlsx"))
            .andExpect(jsonPath("$.totalValues").value(2));
    }

    @Test
    void pickerRejectsPathsOutsideConfiguredRoot() throws Exception {
        mvc.perform(get("/api/excel/browse").param("path", root.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/excel/load-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("path", root.resolve("ngoai-pham-vi.xlsx").toString()))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pickerRejectsTraversalNonExcelFilesAndFolders() throws Exception {
        Path traversal = reports.resolve("..").resolve("ngoai-pham-vi.xlsx");
        mvc.perform(post("/api/excel/load-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("path", traversal.toString()))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/excel/load-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("path", reports.resolve("ghi-chu.txt").toString()))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/excel/browse").param("path", reports.resolve("PO.xlsx").toString()))
            .andExpect(status().isBadRequest());
    }
}
