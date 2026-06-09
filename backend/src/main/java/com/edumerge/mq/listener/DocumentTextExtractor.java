package com.edumerge.mq.listener;

import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * 文本提取器 — 按文件格式分发提取逻辑，支持 PDF/Word/PPT/TXT/HTML/Excel/CSV/图片
 * 含 Vision LLM OCR 回退（扫描版 PDF 和图片）
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    static {
        // 放宽 POI 的 Zip Bomb 检测阈值 — 教学 PPT 常含大量高清图片导致压缩比过低
        ZipSecureFile.setMinInflateRatio(0.001);
    }

    private final ChatModel visionChatModel;
    private final ExecutorService documentTaskExecutor;

    @Value("${app.ai.vision.ocr-concurrency:10}")
    private int visionOcrConcurrency;

    public DocumentTextExtractor(
            @Qualifier("visionChatModel") ChatModel visionChatModel,
            @Qualifier("documentTaskExecutor") ExecutorService documentTaskExecutor) {
        this.visionChatModel = visionChatModel;
        this.documentTaskExecutor = documentTaskExecutor;
    }

    /** 文本提取结果 — 文本内容 + 页数/幻灯片数/工作表数 */
    public record ExtractionResult(String text, int pageCount) {}

    /**
     * 按文件扩展名分发文本提取逻辑
     */
    public ExtractionResult extract(Path documentPath) throws IOException {
        String extension = getExtension(documentPath);
        return switch (extension) {
            case "pdf" -> extractPdfText(documentPath);
            case "docx" -> extractDocxText(documentPath);
            case "doc" -> extractDocText(documentPath);
            case "pptx" -> extractPptxText(documentPath);
            case "ppt" -> extractPptText(documentPath);
            case "txt", "md" -> extractTxtText(documentPath);
            case "html", "htm" -> extractHtmlText(documentPath);
            case "xlsx" -> extractXlsxText(documentPath);
            case "csv" -> extractCsvText(documentPath);
            case "jpg", "jpeg", "png", "bmp", "tiff" -> extractImageText(documentPath);
            default -> throw new IOException("不支持的文件类型: " + extension);
        };
    }

    public String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    // ═══════ 各格式提取实现 ═══════

    private ExtractionResult extractDocxText(Path docxPath) throws IOException {
        try (InputStream input = Files.newInputStream(docxPath);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return new ExtractionResult(extractor.getText(), 0);
        }
    }

    private ExtractionResult extractDocText(Path docPath) throws IOException {
        try (InputStream input = Files.newInputStream(docPath);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return new ExtractionResult(extractor.getText(), 0);
        }
    }

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

    private ExtractionResult extractTxtText(Path txtPath) throws IOException {
        String text;
        try {
            text = Files.readString(txtPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            text = Files.readString(txtPath, Charset.forName("GB18030"));
        }
        return new ExtractionResult(text, 0);
    }

    private ExtractionResult extractHtmlText(Path htmlPath) throws IOException {
        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        return new ExtractionResult(doc.text(), 0);
    }

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

    private ExtractionResult extractCsvText(Path csvPath) throws IOException {
        try (var reader = new CSVReaderBuilder(
                Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)).build()) {
            StringBuilder text = new StringBuilder();
            String[] line;
            int rowCount = 0;
            while ((line = reader.readNext()) != null) {
                text.append(String.join("\t", line)).append("\n");
                rowCount++;
            }
            return new ExtractionResult(text.toString(), rowCount);
        } catch (CsvException e) {
            throw new IOException("CSV 解析失败: " + e.getMessage(), e);
        }
    }

    // ═══════ PDF + Vision OCR ═══════

    private ExtractionResult extractPdfText(Path pdfPath) throws IOException {
        PDDocument pdfDoc;
        try {
            pdfDoc = Loader.loadPDF(pdfPath.toFile());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            log.warn("PDF 加密无法读取: {}", pdfPath.getFileName());
            throw new IOException("该 PDF 文件已加密，请上传未加密版本", e);
        }
        try (pdfDoc) {
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

            // 文字层为空 → Vision LLM 并发逐页识别
            log.info("PDF 无文字层, 启用 Vision OCR: pages={}, concurrency={}", pageCount, visionOcrConcurrency);
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            long startTime = System.currentTimeMillis();
            Semaphore semaphore = new Semaphore(visionOcrConcurrency);
            String[] pageResults = new String[pageCount];
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[pageCount];

            for (int i = 0; i < pageCount; i++) {
                final int pageIdx = i;
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        BufferedImage image = renderer.renderImageWithDPI(pageIdx, 150);
                        try {
                            if (isBlankPage(image)) {
                                log.info("跳过空白页: 第{}页", pageIdx + 1);
                                return;
                            }
                            String base64 = imageToBase64(image);
                            pageResults[pageIdx] = callVisionApi(base64);
                        } finally {
                            image.flush();
                        }
                    } catch (Exception e) {
                        log.warn("Vision OCR 第{}页失败: {}", pageIdx + 1, e.getMessage());
                    } finally {
                        semaphore.release();
                    }
                    if ((pageIdx + 1) % 10 == 0) {
                        log.info("Vision OCR 进度: {}/{} 页 (已耗时 {}s)", pageIdx + 1, pageCount,
                                (System.currentTimeMillis() - startTime) / 1000);
                    }
                }, documentTaskExecutor);
            }
            CompletableFuture.allOf(futures).join();
            StringBuilder text = new StringBuilder();
            int recognizedPages = 0;
            for (int i = 0; i < pageCount; i++) {
                if (pageResults[i] != null && !pageResults[i].isBlank()) {
                    recognizedPages++;
                    text.append(pageResults[i]).append("\n");
                }
            }
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Vision OCR 完成: {}/{} 页识别到文字, 总计 {} 字符, 耗时 {}s",
                    recognizedPages, pageCount, text.length(), elapsed);
            return new ExtractionResult(text.toString(), pageCount);
        }
    }

    private ExtractionResult extractImageText(Path imagePath) throws IOException {
        log.info("图片 OCR 开始: {}", imagePath.getFileName());
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String text = callVisionApi(base64);
        log.info("图片 OCR 完成: {}, 提取 {} 字符", imagePath.getFileName(), text.length());
        return new ExtractionResult(text, 1);
    }

    // ═══════ 工具方法 ═══════

    private String callVisionApi(String base64Image) {
        try {
            UserMessage userMessage = UserMessage.from(
                    ImageContent.from(base64Image, "image/jpeg"),
                    TextContent.from("请精确识别图片中的所有文字内容，包括手写文字、印刷文字、公式、图表文字。保持原文结构和段落划分。仅输出识别到的文字，不要添加任何解释或说明。"));
            ChatResponse response = visionChatModel.chat(userMessage);
            String text = response.aiMessage().text();
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            log.error("Vision API 调用失败: {}", e.getMessage());
        }
        return "";
    }

    private boolean isBlankPage(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int step = Math.max(1, Math.min(w, h) / 50);
        int total = 0, blank = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r > 240 && g > 240 && b > 240) blank++;
                total++;
            }
        }
        return total > 0 && (blank * 100 / total) >= 98;
    }

    private String imageToBase64(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        var writers = javax.imageio.ImageIO.getImageWritersByFormatName("jpg");
        if (writers.hasNext()) {
            var writer = writers.next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            writer.setOutput(javax.imageio.ImageIO.createImageOutputStream(baos));
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            writer.dispose();
        } else {
            javax.imageio.ImageIO.write(image, "jpg", baos);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private void appendIfNotBlank(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value).append("\n");
        }
    }
}
