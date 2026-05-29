package com.rag.demo.controller;

import com.rag.demo.model.DocumentInfo;
import com.rag.demo.service.DocumentIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @PostMapping("/upload")
    public ResponseEntity<DocumentInfo> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            return ResponseEntity.badRequest().build();
        }
        String ext = originalName.toLowerCase();
        if (!ext.endsWith(".pdf") && !ext.endsWith(".txt") && !ext.endsWith(".md")
                && !ext.endsWith(".csv") && !ext.endsWith(".json") && !ext.endsWith(".xml")
                && !ext.endsWith(".xlsx") && !ext.endsWith(".xls")
                && !ext.endsWith(".docx")
                && !ext.endsWith(".pptx")
                && !ext.endsWith(".html") && !ext.endsWith(".htm")) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Path tempFile = Files.createTempFile("rag-upload-", originalName);
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

            DocumentInfo result = documentIngestionService.ingestDocument(tempFile);

            Files.deleteIfExists(tempFile);

            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<String>> listDocuments() {
        return ResponseEntity.ok(documentIngestionService.getIngestedDocuments());
    }

    @PostMapping("/scan")
    public ResponseEntity<List<DocumentInfo>> scanDocuments() {
        return ResponseEntity.ok(documentIngestionService.scan());
    }
}
