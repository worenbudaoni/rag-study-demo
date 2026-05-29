package com.rag.demo.service;

import com.rag.demo.model.DocumentInfo;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${app.document-scan-path}")
    private String scanPath;

    private final List<String> ingestedDocuments = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("Scanning document directory: {}", scanPath);
        File dir = new File(scanPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created document directory: {}", scanPath);
            return;
        }
        File[] files = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".txt"));
        if (files == null || files.length == 0) {
            log.info("No documents found in {}", scanPath);
            return;
        }
        for (File file : files) {
            try {
                ingestDocument(file.toPath());
            } catch (Exception e) {
                log.error("Failed to ingest document: {}", file.getName(), e);
            }
        }
        log.info("Document scan complete. Total ingested: {}", ingestedDocuments.size());
    }

    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        log.info("Ingesting document: {}", fileName);

        Document document;
        if (fileName.toLowerCase().endsWith(".pdf")) {
            document = loadDocument(filePath, new ApachePdfBoxDocumentParser());
        } else if (fileName.toLowerCase().endsWith(".txt")) {
            document = loadDocument(filePath, new TextDocumentParser());
        } else {
            throw new IllegalArgumentException("Unsupported file: " + fileName);
        }

        // Split into chunks (~2000 chars ≈ 500 tokens, 200 char overlap ≈ 50 tokens)
        DocumentByCharacterSplitter splitter = new DocumentByCharacterSplitter(2000, 200);
        List<TextSegment> segments = splitter.split(document);

        // Embed and store in Milvus
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        ingestedDocuments.add(fileName);
        log.info("Ingested {} with {} chunks", fileName, segments.size());
        return new DocumentInfo(fileName, segments.size());
    }

    public List<String> getIngestedDocuments() {
        return new ArrayList<>(ingestedDocuments);
    }
}
