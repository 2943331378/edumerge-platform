package com.edumerge.ai;

import com.edumerge.entity.DocumentChunk;
import com.edumerge.entity.DocumentOutline;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.service.DocumentOutlineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private ChatModel chatLanguageModel;

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
        // 1. 从 MySQL 读取前 6 个 chunks (覆盖目录和正文开头)
        //    再读最后 2 个 chunks (覆盖结尾/总结), 拼接为 LLM 上下文
        List<DocumentChunk> frontChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex)
                        .last("LIMIT 6"));

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
        log.info("大纲生成上下文构建完成: docId={}, 前段{}块, 后段{}块, 总{}块, 耗时={}ms",
                docId, frontChunks.size(), tailChunks.size(), totalChunks, System.currentTimeMillis() - startTime);

        // 3. 调用 LLM
        long llmStart = System.currentTimeMillis();
        String subjectType = getSubjectType(docId);
        String llmResponse = callLLM(context, totalChunks, subjectType);
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

    // ═══════ 学科结构指导 ═══════

    private String buildOutlineSubjectHint(String subjectType) {
        if (subjectType == null) subjectType = SubjectClassifier.GENERAL;
        return switch (subjectType) {
            case "ALGORITHM" -> """
                    # 学科结构指导：算法设计与分析
                    算法类文档的典型章节结构：
                    - 按算法策略分章（分治、贪心、回溯、动态规划等），每章内按"问题描述→算法设计→正确性/复杂度分析→代码实现"展开
                    - 基础概念（算法复杂度、STL 工具等）单独成章
                    - 标题应体现具体算法名或问题名（如"快速排序"而非"排序算法"）
                    """;
            case "MATH" -> """
                    # 学科结构指导：数学
                    数学类文档的典型章节结构：
                    - 按知识模块分章（极限、微分、积分、级数等），每章内按"定义→性质→定理→计算方法→应用"展开
                    - 标题应体现具体概念或方法（如"洛必达法则"而非"求导方法"）
                    """;
            case "PROGRAMMING" -> """
                    # 学科结构指导：程序设计
                    编程类文档的典型章节结构：
                    - 按语言特性分章（数据类型、控制流、面向对象、异常处理等）
                    - 或按项目/功能模块分章
                    - 标题应体现具体语法或设计模式（如"泛型与类型擦除"而非"高级特性"）
                    """;
            case "SCIENCE" -> """
                    # 学科结构指导：自然科学
                    理科类文档的典型章节结构：
                    - 按知识体系递进（力学→热学→光学→电学等）
                    - 每章内按"概念→定律/公式→推导→例题"展开
                    - 标题应体现具体定律或现象（如"牛顿第二定律"而非"运动定律"）
                    """;
            case "THEORY" -> """
                    # 学科结构指导：计算机理论
                    计算机理论类文档的典型章节结构：
                    - 按系统模块分章（进程管理、内存管理、文件系统等）
                    - 或按协议/算法分层（物理层→数据链路层→网络层等）
                    - 标题应体现具体机制或协议（如"页面置换算法"而非"内存管理"）
                    """;
            case "MEDICAL" -> """
                    # 学科结构指导：医学
                    医学类文档的典型章节结构：
                    - 按器官系统或疾病分类分章
                    - 每章内按"解剖→生理→病理→临床表现→诊断→治疗"展开
                    - 标题应体现具体器官/疾病/药物（如"2 型糖尿病的发病机制"而非"代谢疾病"）
                    """;
            case "HUMANITIES" -> """
                    # 学科结构指导：人文社科
                    人文社科类文档的典型章节结构：
                    - 按理论流派或历史阶段分章
                    - 或按主题/案例分章
                    - 标题应体现具体理论或事件（如"需求层次理论"而非"激励理论"）
                    """;
            default -> "";
        };
    }

    // ═══════ LLM 调用 ═══════

    private String callLLM(String context, int totalChunks, String subjectType) {
        String subjectHint = buildOutlineSubjectHint(subjectType);

        String template = """
                你是一个专业的文档结构分析专家。分析文档内容，判断文档类型，提取精确的章节大纲。

                # 文档类型（先判断，再生成大纲）
                TEXTBOOK(教材) — 按"章→节→小节"组织，通常有明确的知识体系递进
                PAPER(论文) — 按"引言→方法→实验→结论"等学术结构组织
                NOTE(笔记) — 按主题聚类，结构可能较松散
                SLIDE(课件) — 按幻灯片/讲次组织，每节内容较短
                MANUAL(手册) — 按功能模块/操作步骤组织
                OTHER(其他) — 以上都不匹配时使用

                # 标题质量要求（核心）
                - 标题必须反映该节的具体内容，禁止使用"概述""绪论""简介""其他"等泛泛标题
                - 好标题示例："快速排序的划分策略"、"TCP 三次握手过程"、"胰岛素的作用机制"
                - 坏标题示例："第一章 概述"、"基本内容"、"补充说明"
                - 标题长度 6-20 字，以关键词或核心概念开头
                - 如果是课件(SLIDE)，标题应体现该幻灯片的核心知识点

                # chunk 范围分配策略
                你只能直接看到文档的前段和末尾内容，中间部分需要根据标题语义合理推断分配。
                - 每个一级章节的 chunk 范围必须连续且不重叠
                - 所有一级章节的范围之和必须完整覆盖 [0, {TOTAL_CHUNKS}-1]
                - 子节的范围必须在父节范围之内
                - 如果某章内容密集（如含代码/公式），分配更多 chunks；纯标题/过渡页分配较少

                {SUBJECT_HINT}

                # JSON 格式
                {"docType":"类型标识","docTypeLabel":"中文标签","totalChunks":数量,"sections":[章节树]}

                # 章节树结构
                - 最多3级: 章(level=1) → 节(level=2) → 小节(level=3)
                - ID规则: s1, s1-1, s1-1-1
                - startChunk/endChunk 为闭区间, 范围 [0, {TOTAL_CHUNKS}-1]
                - 一级章节数: 3-8 个, 每章子节数: 2-6 个
                - 中文标题, 无标点结尾

                # 文档内容
                {CONTEXT}
                """;

        String prompt = template
                .replace("{TOTAL_CHUNKS}", String.valueOf(totalChunks))
                .replace("{SUBJECT_HINT}", subjectHint)
                .replace("{CONTEXT}", context);

        SystemMessage system = new SystemMessage(prompt);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请分析以上文档内容, 判断文档类型并提取章节大纲。仅输出 JSON。"));

        try {
            ChatResponse response = chatLanguageModel.chat(messages);
            return response.aiMessage().text();
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
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
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
    private static final Map<String, String> DOC_TYPE_LABELS = Map.of(
            "TEXTBOOK", "教材/教科书",
            "PAPER", "学术论文",
            "NOTE", "学习笔记",
            "SLIDE", "演示文稿",
            "MANUAL", "技术手册",
            "OTHER", "其他文档"
    );
}
