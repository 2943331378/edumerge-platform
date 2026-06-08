package com.edumerge.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件魔数（Magic Bytes）校验器 — 防止伪造扩展名/Content-Type 的恶意文件上传。
 * <p>
 * 读取上传文件的前 8 字节，与已知文件类型的签名比对，
 * 确保文件内容与声明的扩展名一致。
 */
public final class FileMagicValidator {

    private FileMagicValidator() {}

    /** 可跳过魔数校验的纯文本格式（无固定签名） */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

    /** 二进制格式的魔数签名（小写扩展名 → 字节前缀） */
    private static final Map<String, byte[]> MAGIC_BYTES = Map.ofEntries(
            Map.entry("pdf",  new byte[]{0x25, 0x50, 0x44, 0x46}),             // %PDF
            Map.entry("doc",  new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}), // OLE2
            Map.entry("ppt",  new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}), // OLE2
            Map.entry("docx", new byte[]{0x50, 0x4B, 0x03, 0x04}),             // ZIP (OOXML)
            Map.entry("pptx", new byte[]{0x50, 0x4B, 0x03, 0x04}),             // ZIP (OOXML)
            Map.entry("xlsx", new byte[]{0x50, 0x4B, 0x03, 0x04}),             // ZIP (OOXML)
            Map.entry("jpg",  new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            Map.entry("jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            Map.entry("png",  new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}),      // .PNG
            Map.entry("bmp",  new byte[]{0x42, 0x4D}),                          // BM
            Map.entry("tiff", new byte[]{0x49, 0x49, 0x2A, 0x00}),             // TIFF LE
            Map.entry("html", new byte[]{0x3C}),                                // '<'
            Map.entry("htm",  new byte[]{0x3C})                                 // '<'
    );

    /**
     * 校验文件内容的魔数是否与期望的扩展名匹配。
     *
     * @param inputStream      文件输入流（方法内部会读取前 8 字节，调用方需确保流支持 mark/reset 或从头读取）
     * @param expectedExtension 期望的文件扩展名（如 "pdf"、"docx"），大小写不敏感
     * @throws IllegalArgumentException 如果魔数与扩展名不匹配
     * @throws IOException              如果读取流失败
     */
    public static void validate(InputStream inputStream, String expectedExtension) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (expectedExtension == null || expectedExtension.isBlank()) {
            throw new IllegalArgumentException("文件扩展名不能为空");
        }

        String ext = expectedExtension.toLowerCase(Locale.ROOT);

        // 纯文本格式：无固定魔数，跳过校验
        if (TEXT_EXTENSIONS.contains(ext)) {
            return;
        }

        // 读取前 8 字节
        byte[] header = new byte[8];
        int bytesRead = readHeaderBytes(inputStream, header);
        if (bytesRead == 0) {
            throw new IllegalArgumentException("文件内容为空，无法校验文件类型");
        }

        // 查找该扩展名对应的魔数签名
        byte[] expectedMagic = MAGIC_BYTES.get(ext);
        if (expectedMagic == null) {
            // 未知扩展名（理论上不会到这里，因为 DocumentService 已做过白名单校验）
            return;
        }

        // 二进制格式：严格比对魔数
        if (!matchesMagic(header, expectedMagic)) {
            String detected = detectType(header);
            if (detected != null) {
                throw new IllegalArgumentException(
                        "文件内容与扩展名不匹配：检测到 " + detected + " 格式，但文件扩展名为 ." + ext);
            }
            throw new IllegalArgumentException(
                    "文件内容与扩展名不匹配：无法识别文件内容，请确认文件未损坏");
        }
    }

    /**
     * 从输入流中读取头部字节，尽可能填满 buffer。
     *
     * @return 实际读取的字节数
     */
    private static int readHeaderBytes(InputStream in, byte[] buffer) throws IOException {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int n = in.read(buffer, totalRead, buffer.length - totalRead);
            if (n < 0) break;
            totalRead += n;
        }
        return totalRead;
    }

    /**
     * 检查 header 是否以 expectedMagic 开头。
     */
    private static boolean matchesMagic(byte[] header, byte[] expectedMagic) {
        if (header.length < expectedMagic.length) return false;
        for (int i = 0; i < expectedMagic.length; i++) {
            if (header[i] != expectedMagic[i]) return false;
        }
        return true;
    }

    /**
     * 尝试根据头部字节识别实际文件类型（用于生成更友好的错误提示）。
     *
     * @return 可读的类型描述，无法识别时返回 null
     */
    private static String detectType(byte[] header) {
        if (header.length < 4) return null;

        if (matchesMagic(header, MAGIC_BYTES.get("pdf")))  return "PDF";
        if (matchesMagic(header, MAGIC_BYTES.get("doc")))  return "OLE2 (DOC/PPT)";
        if (matchesMagic(header, MAGIC_BYTES.get("docx"))) return "ZIP/OOXML (DOCX/PPTX/XLSX)";
        if (matchesMagic(header, MAGIC_BYTES.get("jpg")))  return "JPEG";
        if (matchesMagic(header, MAGIC_BYTES.get("png")))  return "PNG";
        if (matchesMagic(header, MAGIC_BYTES.get("bmp")))  return "BMP";
        if (matchesMagic(header, MAGIC_BYTES.get("tiff"))) return "TIFF";

        // ELF 可执行文件
        if (header[0] == 0x7F && header[1] == 0x45 && header[2] == 0x4C && header[3] == 0x46) {
            return "ELF 可执行文件";
        }
        // PE/Windows 可执行文件
        if (header[0] == 0x4D && header[1] == 0x5A) {
            return "Windows 可执行文件 (PE)";
        }
        // Mach-O
        if ((header[0] == (byte) 0xFE && header[1] == (byte) 0xED && header[2] == (byte) 0xFA)
                || (header[0] == (byte) 0xCF && header[1] == (byte) 0xFA && header[2] == (byte) 0xED)) {
            return "Mach-O 可执行文件";
        }

        return null;
    }
}
