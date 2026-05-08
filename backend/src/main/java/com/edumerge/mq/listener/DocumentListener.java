package com.edumerge.mq.listener;

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

    @Value("${app.document.ocr.enabled:true}")
    private boolean ocrEnabled;
    @Value("${app.document.ocr.tesseract-data-path}")
    private String tesseractDataPath;
    @Value("${app.document.ocr.language:chi_sim+eng}")
    private String ocrLanguage;
    @Value("${app.document.ocr.render-dpi:200}")
    private int renderDpi;

    private Tesseract tesseract;

    @Autowired
    public DocumentListener(EmbeddingModel embeddingModel,
                            MilvusEmbeddingStore embeddingStore,
                            DocumentService documentService,
                            DocumentChunkMapper documentChunkMapper) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentService = documentService;
        this.documentChunkMapper = documentChunkMapper;
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
            // 2. 提取文本
            String text = extractDocumentText(documentPath);
            if (text.isBlank()) {
                log.warn("文档文本为空: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "无法提取文本(可能为加密、图片型文档或不支持的文件格式)");
                return;
            }
            log.info("文档文本提取完成: documentId={}, 长度={} 字符", documentId, text.length());

            // 3. 使用递归切分器切块 (chunkSize=500, overlap=50)
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
            Document langDoc = Document.from(text);
            List<TextSegment> segments = splitter.split(langDoc);
            log.info("文本切块完成: documentId={}, 块数={}", documentId, segments.size());

            if (segments.isEmpty()) {
                log.warn("文本切块后无内容: documentId={}", documentId);
                return;
            }

            // 3.5 保存切块到 MySQL document_chunks 表
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc != null) {
                for (int i = 0; i < segments.size(); i++) {
                    DocumentChunk chunk = DocumentChunk.builder()
                            .documentId(doc.getId())
                            .chunkIndex(i)
                            .content(segments.get(i).text())
                            .embeddingStatus("PENDING")
                            .build();
                    documentChunkMapper.insert(chunk);
                }
                log.info("文档切块已入库: docId={}, 块数={}", doc.getId(), segments.size());
            }

            // 4. 为每个块附加元数据 (document_id + chunk_index)
            List<TextSegment> enrichedSegments = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                Metadata meta = new Metadata()
                        .put("document_id", documentId)
                        .put("chunk_index", i);
                enrichedSegments.add(TextSegment.from(segments.get(i).text(), meta));
            }

            // 5. 批量向量化
            log.info("开始批量向量化: documentId={}, 块数={}", documentId, enrichedSegments.size());
            List<Embedding> embeddings = new ArrayList<>();
            for (TextSegment segment : enrichedSegments) {
                Response<Embedding> response = embeddingModel.embed(segment);
                embeddings.add(response.content());
            }
            log.info("向量化完成: documentId={}, 向量数={}", documentId, embeddings.size());

            // 6. 存入 Milvus
            embeddingStore.addAll(embeddings, enrichedSegments);
            log.info("向量存储完成: documentId={}, 块数={}", documentId, enrichedSegments.size());

            // 6.5 批量更新 document_chunks 状态为 COMPLETED
            if (doc != null) {
                documentChunkMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, doc.getId())
                                .set(DocumentChunk::getEmbeddingStatus, "COMPLETED"));
            }

            // 7. 更新 MySQL 文档状态为 COMPLETED
            updateDocStatus(filePath, "COMPLETED", enrichedSegments.size(), embeddings.size(), null);

        } catch (IOException e) {
            log.error("文档解析失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        } catch (Exception e) {
            log.error("向量化流程异常: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        }
    }

    /**
     * 按文件扩展名分发文本提取逻辑
     */
    private String extractDocumentText(Path documentPath) throws IOException {
        String extension = getExtension(documentPath);
        return switch (extension) {
            case "pdf" -> extractPdfText(documentPath);
            case "docx" -> extractDocxText(documentPath);
            case "doc" -> extractDocText(documentPath);
            case "pptx" -> extractPptxText(documentPath);
            case "ppt" -> extractPptText(documentPath);
            case "txt" -> extractTxtText(documentPath);
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
    private String extractDocxText(Path docxPath) throws IOException {
        try (InputStream input = Files.newInputStream(docxPath);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 提取旧版 DOC 文本
     */
    private String extractDocText(Path docPath) throws IOException {
        try (InputStream input = Files.newInputStream(docPath);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 提取 PPTX 文本
     */
    private String extractPptxText(Path pptxPath) throws IOException {
        try (InputStream input = Files.newInputStream(pptxPath);
             XMLSlideShow slideShow = new XMLSlideShow(input)) {
            StringBuilder text = new StringBuilder();
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
            return text.toString();
        }
    }

    /**
     * 提取旧版 PPT 文本
     */
    private String extractPptText(Path pptPath) throws IOException {
        try (InputStream input = Files.newInputStream(pptPath);
             HSLFSlideShow slideShow = new HSLFSlideShow(input)) {
            StringBuilder text = new StringBuilder();
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
            return text.toString();
        }
    }

    /**
     * 提取 TXT 文本 — 优先 UTF-8, 失败时回退到 GB18030
     */
    private String extractTxtText(Path txtPath) throws IOException {
        try {
            return Files.readString(txtPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(txtPath, Charset.forName("GB18030"));
        }
    }

    private void appendIfNotBlank(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value).append("\n");
        }
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
    private String extractPdfText(Path pdfPath) throws IOException {
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
                return allText.toString();
            }

            // 文字层为空 → OCR 回退
            if (ocrEnabled) {
                log.info("启用 OCR 回退: DPI={}, 语言={}, tessdata={}", renderDpi, ocrLanguage, tesseractDataPath);
                return ocrPdfPages(pdfDoc, pageCount);
            }

            log.warn("PDF 无文字层且 OCR 未启用 (app.document.ocr.enabled=false)");
            return "";
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
