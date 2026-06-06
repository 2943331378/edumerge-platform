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
import java.util.concurrent.ConcurrentHashMap;

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
    @org.springframework.beans.factory.annotation.Qualifier("outlineChatModel")
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private DocumentOutlineService outlineService;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    /** 防重复生成锁: docId → true 表示正在生成中 */
    private final ConcurrentHashMap<Long, Boolean> generatingLocks = new ConcurrentHashMap<>();

    /**
     * 生成文档大纲并持久化
     *
     * @param docId    文档数据库 ID
     * @param userId   用户 ID
     * @param totalChunks 文档总切块数
     * @return 生成的大纲, 失败时返回 null
     */
    public DocumentOutline generateAndSave(Long docId, Long userId, int totalChunks) {
        // 防重复: 如果该文档正在生成中，直接返回已有大纲
        if (generatingLocks.putIfAbsent(docId, true) != null) {
            log.info("大纲生成已在进行中, 跳过重复调用: docId={}", docId);
            return outlineService.getByDocId(docId);
        }

        // 如果大纲已存在，也跳过
        DocumentOutline existing = outlineService.getByDocId(docId);
        if (existing != null) {
            generatingLocks.remove(docId);
            log.info("大纲已存在, 跳过生成: docId={}", docId);
            return existing;
        }

        long startTime = System.currentTimeMillis();
        log.info("开始生成文档大纲: docId={}, userId={}, totalChunks={}", docId, userId, totalChunks);

        try {
        // 1. 从 MySQL 读取前 4 个 chunks (覆盖目录和正文开头)
        //    再读最后 1 个 chunk (覆盖结尾/总结), 拼接为 LLM 上下文
        List<DocumentChunk> frontChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex)
                        .last("LIMIT 4"));

        List<DocumentChunk> tailChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByDesc(DocumentChunk::getChunkIndex)
                        .last("LIMIT 1"));

        if (frontChunks.isEmpty()) {
            log.warn("文档无切块, 跳过大纲生成: docId={}", docId);
            return null;
        }

        // 2. 拼装上下文
        String context = buildOutlineContext(frontChunks, tailChunks, totalChunks);
        log.info("大纲生成上下文构建完成: docId={}, 前段{}块, 后段{}块, 总{}块, 耗时={}ms",
                docId, frontChunks.size(), tailChunks.size(), totalChunks, System.currentTimeMillis() - startTime);

        // 3. 调用 LLM
        long llmStart = System.currentTimeMillis();
        String llmResponse = callLLM(context, totalChunks);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.error("LLM 大纲生成返回空: docId={}, LLM耗时={}ms", docId, System.currentTimeMillis() - llmStart);
            return null;
        }
        log.info("LLM 大纲生成完成: docId={}, 响应长度={}字符, LLM耗时={}ms", docId, llmResponse.length(), System.currentTimeMillis() - llmStart);

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

        log.info("文档大纲已生成并持久化: docId={}, docType={}, version={}, 总耗时={}ms",
                docId, docType, 1, System.currentTimeMillis() - startTime);
        return outline;
        } finally {
            generatingLocks.remove(docId);
        }
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
                分析文档内容, 判断类型并提取章节大纲, 仅输出JSON。

                文档类型: TEXTBOOK(教材) | PAPER(论文) | NOTE(笔记) | SLIDE(课件) | MANUAL(手册) | OTHER(其他)

                JSON格式示例:
                {"docType":"TEXTBOOK","docTypeLabel":"教材","totalChunks":20,"sections":[{"id":"s1","title":"绪论","level":1,"startChunk":0,"endChunk":5,"children":[{"id":"s1-1","title":"研究背景","level":2,"startChunk":0,"endChunk":2,"children":[]},{"id":"s1-2","title":"研究意义","level":2,"startChunk":3,"endChunk":5,"children":[]}]}]}

                规则:
                1. 最多3级(章→节→小节), ID: s1, s1-1, s1-1-1
                2. startChunk/endChunk闭区间, 范围在[0,{TOTAL_CHUNKS}-1]内
                3. 覆盖完整性: 所有章节chunk范围必须覆盖[0,{TOTAL_CHUNKS}-1], 不得遗漏
                4. 章节不重叠: 同级章节chunk范围不得交叉
                5. 一级3-8个章, 每章2-6个子节
                6. 中文标题, 学术风格, 无标点结尾
                7. 无明显结构时按主题划分

                文档内容:
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
