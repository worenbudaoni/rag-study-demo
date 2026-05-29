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
        if (originalName == null || (!originalName.toLowerCase().endsWith(".pdf")
                && !originalName.toLowerCase().endsWith(".txt"))) {
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
}
