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
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
        scan();
    }

    public List<DocumentInfo> scan() {
        log.info("Scanning document directory: {}", scanPath);
        List<DocumentInfo> results = new ArrayList<>();
        File dir = new File(scanPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created document directory: {}", scanPath);
            return results;
        }
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                || n.endsWith(".json") || n.endsWith(".xml")
                || n.endsWith(".pdf")
                || n.endsWith(".xlsx") || n.endsWith(".xls")
                || n.endsWith(".docx")
                || n.endsWith(".pptx")
                || n.endsWith(".html") || n.endsWith(".htm");
        });
        if (files == null || files.length == 0) {
            log.info("No documents found in {}", scanPath);
            return results;
        }
        for (File file : files) {
            try {
                DocumentInfo info = ingestDocument(file.toPath());
                results.add(info);
            } catch (Exception e) {
                log.error("Failed to ingest document: {}", file.getName(), e);
            }
        }
        log.info("Document scan complete. Total ingested: {}", ingestedDocuments.size());
        return results;
    }

    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        log.info("Ingesting document: {}", fileName);

        Document document;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            document = loadDocument(filePath, new ApachePdfBoxDocumentParser());
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".xml")) {
            document = loadDocument(filePath, new TextDocumentParser());
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            document = parseExcel(filePath);
        } else if (lower.endsWith(".docx")) {
            document = parseWord(filePath);
        } else if (lower.endsWith(".pptx")) {
            document = parsePowerPoint(filePath);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            document = parseHtml(filePath);
        } else {
            throw new IllegalArgumentException("Unsupported file: " + fileName);
        }

        // 中文约 1-2 tokens/字，BGE 限制 512 tokens，取 300 字符
        DocumentByCharacterSplitter splitter = new DocumentByCharacterSplitter(300, 30);
        List<TextSegment> segments = splitter.split(document);

        // 给每个 chunk 加上文档名作为前缀，保留来源信息
        for (int i = 0; i < segments.size(); i++) {
            segments.set(i, TextSegment.from(
                    "[来源:" + fileName + "]\n" + segments.get(i).text()));
        }

        // Embed chunks in batches to avoid overwhelming the API
        List<Embedding> allEmbeddings = new ArrayList<>();
        int batchSize = 10;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            try {
                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                allEmbeddings.addAll(embeddings);
                log.info("  Embedded batch {}-{}/{}", i, end, segments.size());
            } catch (Exception e) {
                log.warn("  Batch {}-{} failed, trying one-by-one", i, end);
                for (TextSegment seg : batch) {
                    try {
                        allEmbeddings.add(embeddingModel.embed(seg.text()).content());
                    } catch (Exception e2) {
                        log.warn("  Skipping chunk after retry: {}", seg.text().substring(
                                0, Math.min(50, seg.text().length())));
                    }
                }
            }
        }

        int stored = Math.min(allEmbeddings.size(), segments.size());
        embeddingStore.addAll(allEmbeddings, segments.subList(0, stored));

        ingestedDocuments.add(fileName);
        log.info("Ingested {} with {}/{} chunks", fileName, stored, segments.size());
        return new DocumentInfo(fileName, stored);
    }

    private Document parseExcel(Path filePath) throws IOException {
        StringBuilder text = new StringBuilder();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                boolean firstRow = true;
                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            switch (cell.getCellType()) {
                                case STRING:
                                    text.append(cell.getStringCellValue());
                                    break;
                                case NUMERIC:
                                    if (DateUtil.isCellDateFormatted(cell)) {
                                        text.append(dateFmt.format(cell.getDateCellValue()));
                                    } else {
                                        double val = cell.getNumericCellValue();
                                        if (val == Math.floor(val) && !Double.isInfinite(val)) {
                                            text.append((long) val);
                                        } else {
                                            text.append(val);
                                        }
                                    }
                                    break;
                                case BOOLEAN:
                                    text.append(cell.getBooleanCellValue());
                                    break;
                                case FORMULA:
                                    try {
                                        String formulaVal = cell.getStringCellValue();
                                        text.append(formulaVal);
                                    } catch (Exception e) {
                                        text.append(cell.getCellFormula());
                                    }
                                    break;
                                default:
                                    text.append(" ");
                            }
                        }
                        if (c < row.getLastCellNum() - 1) {
                            text.append(" | ");
                        }
                    }
                    text.append("\n");
                    firstRow = false;
                }
                text.append("\n");
            }
        }
        String content = text.toString();
        log.info("  Extracted {} chars from Excel", content.length());
        return Document.from(content);
    }

    private Document parseWord(Path filePath) throws IOException {
        StringBuilder text = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                text.append(para.getText()).append("\n");
            }
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append(" | ");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    private Document parsePowerPoint(Path filePath) throws IOException {
        StringBuilder text = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            int slideNum = 1;
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                text.append("=== 幻灯片 ").append(slideNum++).append(" ===\n");
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        text.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    private Document parseHtml(Path filePath) throws IOException {
        org.jsoup.nodes.Document html = Jsoup.parse(filePath.toFile(), "UTF-8");
        html.select("script, style, nav, footer, header").remove();
        return Document.from(html.body().text());
    }

    public List<String> getIngestedDocuments() {
        return new ArrayList<>(ingestedDocuments);
    }
}
