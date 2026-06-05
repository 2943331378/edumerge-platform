package com.edumerge.mq.listener;

import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.config.RabbitMQConfig;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mq.message.DocumentProcessMessage;
import com.edumerge.service.DocumentService;
import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 文档向量化消费者 — 监听向量化队列, 执行文档解析 → 文本切块 → 向量化 → 存入 Milvus 全流程
 */
@Slf4j
@Component
public class DocumentListener {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final DocumentService documentService;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiOutlineGenerator outlineGenerator;
    private final ExecutorService documentTaskExecutor;

    @Value("${app.document.ocr.enabled:true}")
    private boolean ocrEnabled;
    @Value("${app.document.ocr.tesseract-data-path}")
    private String tesseractDataPath;
    @Value("${app.document.ocr.language:chi_sim+eng}")
    private String ocrLanguage;
    @Value("${app.document.ocr.render-dpi:200}")
    private int renderDpi;
    @Value("${app.rag.chunk-size:1000}")
    private int chunkSize;
    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    private Tesseract tesseract;

    @Autowired
    public DocumentListener(EmbeddingModel embeddingModel,
                            MilvusEmbeddingStore embeddingStore,
                            DocumentService documentService,
                            DocumentChunkMapper documentChunkMapper,
                            AiOutlineGenerator outlineGenerator,
                            @org.springframework.beans.factory.annotation.Qualifier("documentTaskExecutor") ExecutorService documentTaskExecutor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentService = documentService;
        this.documentChunkMapper = documentChunkMapper;
        this.outlineGenerator = outlineGenerator;
        this.documentTaskExecutor = documentTaskExecutor;
    }

    @RabbitListener(queues = RabbitMQConfig.EMBEDDING_QUEUE)
    public void handleEmbeddingTask(DocumentProcessMessage message) {
        String documentId = message.getDocumentId();
        String filePath = message.getFilePath();
        log.info("收到向量化任务: documentId={}, filePath={}", documentId, filePath);

        Path documentPath = Path.of(filePath);

        // 0. 幂等检查: 文档已处理完成或文件已删除, 直接跳过
        if (!Files.exists(documentPath)) {
            log.info("文件已删除, 跳过: {}", filePath);
            return;
        }
        com.edumerge.entity.Document existing = documentService.getByFilePath(filePath);
        if (existing != null && "COMPLETED".equals(existing.getStatus())) {
            log.info("文档已完成向量化, 跳过: id={}", existing.getId());
            return;
        }

        try {
            long pipelineStart = System.currentTimeMillis();
            updateDocStatus(filePath, "PROCESSING", null, null, null);

            // 2. 提取文本
            long stepStart = System.currentTimeMillis();
            ExtractionResult result = extractDocumentText(documentPath);
            if (result.text.isBlank()) {
                log.warn("文档文本为空: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "无法提取文本(可能为加密、图片型文档或不支持的文件格式)");
                return;
            }
            String text = result.text;
            log.info("文本提取完成: documentId={}, 长度={} 字符, 页数={}, 耗时={}ms",
                    documentId, text.length(), result.pageCount, System.currentTimeMillis() - stepStart);

            // 3. 切块 — PPT 按幻灯片边界切分，其他格式递归切分
            stepStart = System.currentTimeMillis();
            String extension = getExtension(documentPath);
            List<TextSegment> segments;
            if (extension.equals("ppt") || extension.equals("pptx")) {
                segments = splitBySlide(text);
            } else {
                DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
                segments = splitter.split(Document.from(text));
            }
            log.info("文本切块完成: documentId={}, 块数={}, 耗时={}ms", documentId, segments.size(), System.currentTimeMillis() - stepStart);

            if (segments.isEmpty()) {
                log.warn("文本切块后无内容: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "文本切块后无有效内容");
                return;
            }

            // 3.5 批量保存切块到 MySQL document_chunks 表
            stepStart = System.currentTimeMillis();
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc != null) {
                List<DocumentChunk> chunkBatch = new ArrayList<>(segments.size());
                for (int i = 0; i < segments.size(); i++) {
                    chunkBatch.add(DocumentChunk.builder()
                            .documentId(doc.getId())
                            .chunkIndex(i)
                            .content(segments.get(i).text())
                            .embeddingStatus("PENDING")
                            .build());
                }
                documentChunkMapper.insertBatch(chunkBatch);
                // 保存文档页数/幻灯片数
                if (result.pageCount > 0) {
                    documentService.updatePageCount(doc.getId(), result.pageCount);
                }
                log.info("文档切块批量入库: docId={}, 块数={}, 耗时={}ms", doc.getId(), chunkBatch.size(), System.currentTimeMillis() - stepStart);
            }

            // 4. 为每个块附加元数据 (document_id + chunk_index)
            List<TextSegment> enrichedSegments = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                Metadata meta = new Metadata()
                        .put("document_id", documentId)
                        .put("chunk_index", i);
                enrichedSegments.add(TextSegment.from(segments.get(i).text(), meta));
            }

            // 4.5 提前触发大纲生成 — 大纲只需前 8 个 chunk (从 MySQL 读取)，不依赖向量化
            if (doc != null) {
                final Long outlineDocId = doc.getId();
                final Long outlineUserId = doc.getUserId();
                final int outlineChunkCount = enrichedSegments.size();
                CompletableFuture.runAsync(() -> {
                    try {
                        outlineGenerator.generateAndSave(outlineDocId, outlineUserId, outlineChunkCount);
                        log.info("文档大纲生成完成: docId={}", outlineDocId);
                    } catch (Exception e) {
                        log.warn("文档大纲生成失败(不影响主流程): docId={}, error={}", outlineDocId, e.getMessage());
                    }
                }, documentTaskExecutor);
            }

            // 5. 并发向量化 — 多线程分批调用 embedAll (DashScope text-embedding-v4 单次上限 25 条)
            long embedStart = System.currentTimeMillis();
            int totalChunks = enrichedSegments.size();
            int batchSize = 25;
            int batchCount = (totalChunks + batchSize - 1) / batchSize;

            // 按批次提交并发任务
            @SuppressWarnings("unchecked")
            CompletableFuture<Response<List<Embedding>>>[] futures = new CompletableFuture[batchCount];
            for (int b = 0; b < batchCount; b++) {
                final int offset = b * batchSize;
                final int end = Math.min(offset + batchSize, totalChunks);
                final List<TextSegment> batch = enrichedSegments.subList(offset, end);
                futures[b] = CompletableFuture.supplyAsync(() -> embeddingModel.embedAll(batch), documentTaskExecutor);
            }

            // 等待所有批次完成并按顺序收集结果
            List<Embedding> embeddings = new ArrayList<>(totalChunks);
            for (int b = 0; b < batchCount; b++) {
                Response<List<Embedding>> resp = futures[b].join();
                embeddings.addAll(resp.content());
                log.info("向量化进度: {}/{} 块", Math.min((b + 1) * batchSize, totalChunks), totalChunks);
            }
            log.info("向量化完成: documentId={}, 向量数={}, 批次={}, 耗时={}ms",
                    documentId, embeddings.size(), batchCount, System.currentTimeMillis() - embedStart);

            // 6. 存入 Milvus
            stepStart = System.currentTimeMillis();
            log.info("开始写入 Milvus: documentId={}, 块数={}", documentId, enrichedSegments.size());
            embeddingStore.addAll(embeddings, enrichedSegments);
            log.info("向量存储完成: documentId={}, 块数={}, 耗时={}ms", documentId, enrichedSegments.size(), System.currentTimeMillis() - stepStart);

            // 6.5 批量更新 document_chunks 状态为 COMPLETED
            if (doc != null) {
                documentChunkMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, doc.getId())
                                .set(DocumentChunk::getEmbeddingStatus, "COMPLETED"));
            }

            // 7. 更新 MySQL 文档状态为 COMPLETED
            updateDocStatus(filePath, "COMPLETED", enrichedSegments.size(), embeddings.size(), null);
            log.info("文档处理全流程完成: documentId={}, 总块数={}, 总耗时={}ms",
                    documentId, enrichedSegments.size(), System.currentTimeMillis() - pipelineStart);

        } catch (IOException e) {
            log.error("文档解析失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        } catch (Exception e) {
            log.error("向量化流程异常: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        }
    }

    /** 文本提取结果 — 文本内容 + 页数/幻灯片数/工作表数 */
    private record ExtractionResult(String text, int pageCount) {}

    /**
     * 按文件扩展名分发文本提取逻辑
     */
    private ExtractionResult extractDocumentText(Path documentPath) throws IOException {
        String extension = getExtension(documentPath);
        return switch (extension) {
            case "pdf" -> extractPdfText(documentPath);
            case "docx" -> extractDocxText(documentPath);
            case "doc" -> extractDocText(documentPath);
            case "pptx" -> extractPptxText(documentPath);
            case "ppt" -> extractPptText(documentPath);
            case "txt" -> extractTxtText(documentPath);
            case "md" -> extractTxtText(documentPath);
            case "html", "htm" -> extractHtmlText(documentPath);
            case "xlsx" -> extractXlsxText(documentPath);
            case "csv" -> extractCsvText(documentPath);
            default -> throw new IOException("不支持的文件类型: " + extension);
        };
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 提取 DOCX 文本
     */
    private ExtractionResult extractDocxText(Path docxPath) throws IOException {
        try (InputStream input = Files.newInputStream(docxPath);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return new ExtractionResult(extractor.getText(), 0);
        }
    }

    /**
     * 提取旧版 DOC 文本
     */
    private ExtractionResult extractDocText(Path docPath) throws IOException {
        try (InputStream input = Files.newInputStream(docPath);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return new ExtractionResult(extractor.getText(), 0);
        }
    }

    /**
     * 提取 PPTX 文本
     */
    private ExtractionResult extractPptxText(Path pptxPath) throws IOException {
        try (InputStream input = Files.newInputStream(pptxPath);
             XMLSlideShow slideShow = new XMLSlideShow(input)) {
            StringBuilder text = new StringBuilder();
            int slideCount = slideShow.getSlides().size();
            int slideIndex = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                text.append("【幻灯片").append(slideIndex++).append("】\n");
                for (XSLFTextShape shape : slide.getShapes().stream()
                        .filter(XSLFTextShape.class::isInstance)
                        .map(XSLFTextShape.class::cast)
                        .toList()) {
                    appendIfNotBlank(text, shape.getText());
                }
                text.append("\n");
            }
            return new ExtractionResult(text.toString(), slideCount);
        }
    }

    /**
     * 提取旧版 PPT 文本
     */
    private ExtractionResult extractPptText(Path pptPath) throws IOException {
        try (InputStream input = Files.newInputStream(pptPath);
             HSLFSlideShow slideShow = new HSLFSlideShow(input)) {
            StringBuilder text = new StringBuilder();
            int slideCount = slideShow.getSlides().size();
            int slideIndex = 1;
            for (HSLFSlide slide : slideShow.getSlides()) {
                text.append("【幻灯片").append(slideIndex++).append("】\n");
                for (HSLFTextShape shape : slide.getShapes().stream()
                        .filter(HSLFTextShape.class::isInstance)
                        .map(HSLFTextShape.class::cast)
                        .toList()) {
                    appendIfNotBlank(text, shape.getText());
                }
                text.append("\n");
            }
            return new ExtractionResult(text.toString(), slideCount);
        }
    }

    /**
     * 提取 TXT/MD 文本 — 优先 UTF-8, 失败时回退到 GB18030
     */
    private ExtractionResult extractTxtText(Path txtPath) throws IOException {
        String text;
        try {
            text = Files.readString(txtPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            text = Files.readString(txtPath, Charset.forName("GB18030"));
        }
        return new ExtractionResult(text, 0);
    }

    /**
     * 提取 HTML 文本 — Jsoup 解析后提取纯文本
     */
    private ExtractionResult extractHtmlText(Path htmlPath) throws IOException {
        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        return new ExtractionResult(doc.text(), 0);
    }

    /**
     * 提取 XLSX 文本 — POI 逐 sheet 逐行提取，首行作为表头
     */
    private ExtractionResult extractXlsxText(Path xlsxPath) throws IOException {
        try (InputStream input = Files.newInputStream(xlsxPath);
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(input)) {
            StringBuilder text = new StringBuilder();
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                var sheet = workbook.getSheetAt(i);
                text.append("【").append(sheet.getSheetName()).append("】\n");
                for (var row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (var cell : row) {
                        if (cell == null) continue;
                        if (line.length() > 0) line.append("\t");
                        line.append(cell.toString());
                    }
                    if (!line.toString().isBlank()) {
                        text.append(line).append("\n");
                    }
                }
                text.append("\n");
            }
            return new ExtractionResult(text.toString(), sheetCount);
        }
    }

    /**
     * 提取 CSV 文本 — OpenCSV 逐行读取，逗号分隔拼成制表符分隔文本
     */
    private ExtractionResult extractCsvText(Path csvPath) throws IOException {
        try (var reader = new com.opencsv.CSVReaderBuilder(
                Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)).build()) {
            StringBuilder text = new StringBuilder();
            String[] line;
            int rowCount = 0;
            while ((line = reader.readNext()) != null) {
                text.append(String.join("\t", line)).append("\n");
                rowCount++;
            }
            return new ExtractionResult(text.toString(), rowCount);
        } catch (com.opencsv.exceptions.CsvException e) {
            throw new IOException("CSV 解析失败: " + e.getMessage(), e);
        }
    }

    private void appendIfNotBlank(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value).append("\n");
        }
    }

    /**
     * 按幻灯片边界切分 PPT 文本 — 每张幻灯片一个 chunk，超大幻灯片递归拆分
     */
    private List<TextSegment> splitBySlide(String text) {
        String[] slides = text.split("(?=【幻灯片\\d+】)");
        List<TextSegment> segments = new ArrayList<>();
        DocumentSplitter fallback = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        for (String slide : slides) {
            if (slide.isBlank()) continue;
            if (slide.length() <= chunkSize) {
                segments.add(TextSegment.from(slide.trim()));
            } else {
                // 超大幻灯片递归拆分
                segments.addAll(fallback.split(Document.from(slide)));
            }
        }
        return segments;
    }

    /**
     * 按文件路径查找文档并更新处理状态 (委托 Service 层)
     */
    private void updateDocStatus(String filePath, String status, Integer chunkCount, Integer vectorCount, String statusMessage) {
        try {
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc == null) {
                log.warn("未找到文档记录: filePath={}", filePath);
                return;
            }
            documentService.updateStatus(doc.getId(), status, chunkCount, vectorCount, statusMessage);
            log.info("文档状态更新: id={}, status={}", doc.getId(), status);
        } catch (Exception e) {
            log.error("更新文档状态失败: filePath={}, error={}", filePath, e.getMessage());
        }
    }

    /**
     * 提取 PDF 全文 — 优先文字层, 扫描版自动 OCR 回退
     */
    private ExtractionResult extractPdfText(Path pdfPath) throws IOException {
        try (PDDocument pdfDoc = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = pdfDoc.getNumberOfPages();
            log.info("PDF 加载成功: 页数={}, 文件={}", pageCount, pdfPath.getFileName());

            // 先尝试文字层提取
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder allText = new StringBuilder();
            int pagesWithText = 0;
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdfDoc);
                if (pageText != null && !pageText.isBlank()) {
                    pagesWithText++;
                    allText.append(pageText).append("\n");
                }
            }
            log.info("PDF 文字页统计: {}/{} 页含文字层, 总计 {} 字符", pagesWithText, pageCount, allText.length());

            if (!allText.isEmpty()) {
                return new ExtractionResult(allText.toString(), pageCount);
            }

            // 文字层为空 → OCR 回退
            if (ocrEnabled) {
                log.info("启用 OCR 回退: DPI={}, 语言={}, tessdata={}", renderDpi, ocrLanguage, tesseractDataPath);
                return new ExtractionResult(ocrPdfPages(pdfDoc, pageCount), pageCount);
            }

            log.warn("PDF 无文字层且 OCR 未启用 (app.document.ocr.enabled=false)");
            return new ExtractionResult("", pageCount);
        }
    }

    /**
     * OCR 识别扫描版 PDF — PDFBox 渲染每页为图片, Tesseract 逐页识别
     */
    private String ocrPdfPages(PDDocument pdfDoc, int pageCount) {
        initTesseract();
        if (tesseract == null) {
            log.error("Tesseract 初始化失败, OCR 不可用");
            return "";
        }

        PDFRenderer renderer = new PDFRenderer(pdfDoc);
        StringBuilder text = new StringBuilder();
        int renderedPages = 0;
        long startTime = System.currentTimeMillis();

        for (int page = 0; page < pageCount; page++) {
            BufferedImage image = null;
            try {
                image = renderer.renderImage(page, renderDpi / 72f);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank()) {
                    renderedPages++;
                    text.append(pageText).append("\n");
                }
            } catch (IOException e) {
                log.warn("OCR 渲染第{}页失败: {}", page + 1, e.getMessage());
            } catch (TesseractException e) {
                log.warn("OCR 识别第{}页失败: {}", page + 1, e.getMessage());
            } catch (Throwable t) {
                log.error("OCR 第{}页 JNA 错误 (语言包缺失?): {}", page + 1, t.toString());
                break; // 跳过后续页面, 语言包问题不会自愈
            } finally {
                if (image != null) image.flush();
            }
            if ((page + 1) % 10 == 0) {
                log.info("OCR 进度: {}/{} 页 (已耗时 {}s)", page + 1, pageCount,
                        (System.currentTimeMillis() - startTime) / 1000);
            }
        }
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.info("OCR 完成: {}/{} 页识别到文字, 总计 {} 字符, 耗时 {}s",
                renderedPages, pageCount, text.length(), elapsed);
        return text.toString();
    }

    private void initTesseract() {
        if (tesseract != null) return;
        try {
            // 校验语言包文件存在, 避免 doOCR 时 JNA 内存访问错误导致线程崩溃
            for (String lang : ocrLanguage.split("\\+")) {
                Path dataFile = Path.of(tesseractDataPath, lang + ".traineddata");
                if (!Files.exists(dataFile)) {
                    log.error("Tesseract 语言包缺失: {} (OCR 不可用)", dataFile);
                    return;
                }
            }
            tesseract = new Tesseract();
            tesseract.setDatapath(tesseractDataPath);
            tesseract.setLanguage(ocrLanguage);
            tesseract.setOcrEngineMode(1);
            log.info("Tesseract 初始化成功: datapath={}, language={}", tesseractDataPath, ocrLanguage);
        } catch (Throwable t) {
            log.error("Tesseract 初始化失败 (OCR 不可用): {}", t.toString());
            tesseract = null;
        }
    }
}
