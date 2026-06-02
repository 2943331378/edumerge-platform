package com.edumerge.ai;

import com.edumerge.entity.DocumentChunk;
import com.edumerge.entity.DocumentOutline;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.service.DocumentOutlineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 文档大纲生成器 (架构红线: LangChain4j 隔离在 ai 包)
 *
 * 核心流程:
 * 1. 从 MySQL document_chunks 表读取文档的前 N 个 chunks (覆盖目录+正文开头)
 * 2. 调用 LLM 识别文档类型 + 生成章节大纲 (含 chunk 范围映射)
 * 3. 解析 JSON 并校验 chunk 范围合法性
 * 4. 持久化到 document_outlines 表
 *
 * 触发时机: DocumentListener 向量化完成后自动调用
 */
@Slf4j
@Service
public class AiOutlineGenerator extends AiGeneratorBase {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private DocumentOutlineService outlineService;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    /**
     * 生成文档大纲并持久化
     *
     * @param docId    文档数据库 ID
     * @param userId   用户 ID
     * @param totalChunks 文档总切块数
     * @return 生成的大纲, 失败时返回 null
     */
    public DocumentOutline generateAndSave(Long docId, Long userId, int totalChunks) {
        // 1. 从 MySQL 读取前 8 个 chunks (约 4000 字, 覆盖目录和正文开头)
        //    再读最后 2 个 chunks (覆盖结尾/总结), 拼接为 LLM 上下文
        List<DocumentChunk> frontChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex)
                        .last("LIMIT 8"));

        List<DocumentChunk> tailChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByDesc(DocumentChunk::getChunkIndex)
                        .last("LIMIT 2"));

        if (frontChunks.isEmpty()) {
            log.warn("文档无切块, 跳过大纲生成: docId={}", docId);
            return null;
        }

        // 2. 拼装上下文
        String context = buildOutlineContext(frontChunks, tailChunks, totalChunks);
        log.info("大纲生成上下文构建完成: docId={}, 前段{}块, 后段{}块, 总{}块",
                docId, frontChunks.size(), tailChunks.size(), totalChunks);

        // 3. 调用 LLM
        String llmResponse = callLLM(context, totalChunks);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.error("LLM 大纲生成返回空: docId={}", docId);
            return null;
        }
        log.info("LLM 大纲生成完成: docId={}, 响应长度={}字符", docId, llmResponse.length());

        // 4. 清理并提取 JSON
        String json = extractJsonObject(llmResponse);
        if (json == null) {
            log.error("无法从 LLM 响应中提取 JSON: docId={}, raw={}", docId, llmResponse.substring(0, Math.min(200, llmResponse.length())));
            return null;
        }

        // 5. 解析文档类型
        String docType = extractDocType(json);
        String docTypeLabel = DOC_TYPE_LABELS.getOrDefault(docType, "其他文档");

        // 6. 持久化
        DocumentOutline outline = DocumentOutline.builder()
                .docId(docId)
                .userId(userId)
                .docType(docType)
                .docTypeLabel(docTypeLabel)
                .outlineJson(json)
                .version(1)
                .build();
        outlineService.create(outline);

        log.info("文档大纲已生成并持久化: docId={}, docType={}, version={}", docId, docType, 1);
        return outline;
    }

    // ═══════ 上下文构建 ═══════

    private String buildOutlineContext(List<DocumentChunk> frontChunks, List<DocumentChunk> tailChunks, int totalChunks) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== 文档前段内容 (共").append(totalChunks).append("个切块, 以下是前").append(frontChunks.size()).append("个) ===\n\n");
        for (DocumentChunk chunk : frontChunks) {
            sb.append("【切块 ").append(chunk.getChunkIndex()).append("】\n");
            sb.append(chunk.getContent()).append("\n\n");
        }

        if (!tailChunks.isEmpty()) {
            sb.append("\n=== 文档末尾内容 (最后").append(tailChunks.size()).append("个切块) ===\n\n");
            // 反转为正序
            for (int i = tailChunks.size() - 1; i >= 0; i--) {
                DocumentChunk chunk = tailChunks.get(i);
                sb.append("【切块 ").append(chunk.getChunkIndex()).append("】\n");
                sb.append(chunk.getContent()).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ═══════ LLM 调用 ═══════

    private String callLLM(String context, int totalChunks) {
        String template = """
                # 角色
                你是一个专业的文档结构分析专家, 擅长从非结构化文本中识别文档类型、提取章节层级结构。

                # 任务
                请分析以下文档内容, 完成两个任务:
                1. **判断文档类型** — 从以下类型中选择最合适的一个:
                   - TEXTBOOK (教材/教科书): 系统性知识讲解, 含章节编号, 有习题/思考题
                   - PAPER (学术论文): 含摘要/关键词/引言/方法/结果/参考文献
                   - NOTE (学习笔记): 个人整理的知识点, 格式灵活, 含要点罗列
                   - SLIDE (演示文稿/课件): 幻灯片提取的文本, 含【幻灯片N】标记, 要点式
                   - MANUAL (技术手册/规范): 操作指南, API 文档, 标准规范
                   - OTHER (其他): 无法归类的文档

                2. **提取章节大纲** — 识别文档的层级结构, 输出为 JSON 格式

                # 大纲 JSON 格式要求
                ```json
                {
                  "docType": "TEXTBOOK",
                  "docTypeLabel": "教材/教科书",
                  "totalChunks": {TOTAL_CHUNKS},
                  "sections": [
                    {
                      "id": "s1",
                      "title": "第一章 章节标题",
                      "level": 1,
                      "startChunk": 0,
                      "endChunk": 15,
                      "children": [
                        {
                          "id": "s1-1",
                          "title": "1.1 小节标题",
                          "level": 2,
                          "startChunk": 0,
                          "endChunk": 7,
                          "children": []
                        },
                        {
                          "id": "s1-2",
                          "title": "1.2 小节标题",
                          "level": 2,
                          "startChunk": 8,
                          "endChunk": 15,
                          "children": []
                        }
                      ]
                    }
                  ]
                }
                ```

                # 大纲生成规则 (严格执行)
                1. **层级**: 最多 3 级 (章 → 节 → 小节), level 对应 1/2/3
                2. **ID 规则**: 章 = "s1", 节 = "s1-1", 小节 = "s1-1-1"
                3. **chunk 范围**: startChunk/endChunk 必须是闭区间, 范围在 [0, {TOTAL_CHUNKS}-1] 内
                4. **覆盖完整性**: 所有章节的 chunk 范围必须覆盖 [0, {TOTAL_CHUNKS}-1], 不得有遗漏
                5. **章节不重叠**: 同级章节的 chunk 范围不得交叉
                6. **章节数量**: 一级章节 3-8 个, 每个章节下 2-6 个子节
                7. **标题语言**: 必须使用简体中文; 英文文档请翻译章节标题
                8. **标题风格**: 保持学术严谨, 使用名词短语或动宾结构, 不要加标点符号结尾
                9. **如果文档无明显章节结构**: 按内容主题自动划分, 并在 docTypeLabel 中注明

                # chunk 范围估算方法
                - 文档总共有 {TOTAL_CHUNKS} 个切块, 每个切块约 500 字
                - 根据切块内容中出现的标题/主题变化来划分章节边界
                - 如果无法精确判断, 按内容比例均匀分配

                # 输出要求
                - 仅输出 JSON, 严禁包含任何解释文字、引导语、markdown 代码块标记
                - 确保 JSON 格式合法, 可被直接解析

                # 文档内容
                {CONTEXT}
                """;

        String prompt = template
                .replace("{TOTAL_CHUNKS}", String.valueOf(totalChunks))
                .replace("{CONTEXT}", context);

        SystemMessage system = new SystemMessage(prompt);
        List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请分析以上文档内容, 判断文档类型并提取章节大纲。仅输出 JSON。"));

        try {
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            return response.content().text();
        } catch (Exception e) {
            log.error("LLM 大纲生成调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ═══════ JSON 提取与解析 ═══════

    /** 从 LLM 响应中提取 JSON 对象 (去除 markdown 代码块标记等) */
    /** 从 JSON 中提取 docType 字段 (使用 ObjectMapper 安全解析) */
    @SuppressWarnings("unchecked")
    private String extractDocType(String json) {
        try {
            java.util.Map<String, Object> map = objectMapper.readValue(json, java.util.Map.class);
            Object docTypeObj = map.get("docType");
            if (docTypeObj instanceof String type) {
                String upper = type.toUpperCase();
                return DOC_TYPE_LABELS.containsKey(upper) ? upper : "OTHER";
            }
        } catch (Exception e) {
            log.warn("解析 docType 失败: {}", e.getMessage());
        }
        return "OTHER";
    }

    /** 文档类型中文标签映射 */
    private static final java.util.Map<String, String> DOC_TYPE_LABELS = java.util.Map.of(
            "TEXTBOOK", "教材/教科书",
            "PAPER", "学术论文",
            "NOTE", "学习笔记",
            "SLIDE", "演示文稿",
            "MANUAL", "技术手册",
            "OTHER", "其他文档"
    );
}
