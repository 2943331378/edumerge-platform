package com.edumerge.ai;

import com.edumerge.entity.CardDeck;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.MindMapService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 思维导图生成器 (架构红线: LangChain4j 隔离在 ai 包)
 *
 * 核心流程: Milvus 检索非结构化文本块 → LLM 提取层级知识结构 → 输出 Markdown 格式思维导图
 * 体现"非结构化数据 → 结构化知识"的数据治理转化路径
 */
@Slf4j
@Service
public class AiMindMapGenerator extends AiGeneratorBase {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private CardDeckService cardDeckService;

    @Autowired
    private MindMapService mindMapService;

    /**
     * 根据文档内容生成思维导图
     * @param docId   文档数据库 ID
     * @param userId  用户 ID
     * @param docUuid 文档 Milvus UUID (用于向量检索)
     * @return 生成结果 (含 deckId、title、content)
     */
    public MindMapResult generate(Long docId, Long userId, String docUuid) {
        // 步骤 1: 从 Milvus 检索文档核心内容 — 非结构化数据提取
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 20,
                "文档结构 章节标题 核心主题 关键概念 层级关系 目录大纲 主要内容 定义 原理 方法 总结");
        if (matches.isEmpty()) {
            log.warn("未检索到文档块: docId={}, docUuid={}", docId, docUuid);
            return MindMapResult.empty(docId);
        }

        // 步骤 2: 拼装上下文 — 标注来源信息实现数据溯源
        String context = buildContextWithPages(matches);
        log.info("思维导图上下文构建完成: docId={}, 块数={}", docId, matches.size());

        // 步骤 3: 调用 LLM 生成 Markdown 思维导图 — 非结构化→结构化转化核心
        String markdown = callLLM(context);
        log.info("LLM 思维导图生成完成: docId={}, 长度={}", docId, markdown.length());

        // 步骤 4: 清理验证 — 确保输出符合 Markdown 层级格式
        markdown = cleanMarkdown(markdown);
        if (!isValidMindMap(markdown)) {
            log.error("思维导图格式验证失败: docId={}, content={}", docId, markdown.substring(0, Math.min(200, markdown.length())));
            return MindMapResult.empty(docId);
        }

        // 步骤 5: 持久化 — 删除旧记录, 创建 deck + mind_map
        mindMapService.deleteByDocId(docId);
        CardDeck deck = cardDeckService.create(docId, "MIND_MAP");
        mindMapService.create(docId, deck.getId(), markdown);

        String createdAt = deck.getCreatedAt() != null
                ? deck.getCreatedAt().toString() : java.time.LocalDateTime.now().toString();
        log.info("思维导图已持久化: docId={}, deckId={}", docId, deck.getId());
        return MindMapResult.success(docId, deck.getId(), deck.getTitle(), markdown, createdAt);
    }

    /** 调用大模型, 使用专用 Prompt 强制输出结构化 Markdown */
    private String callLLM(String context) {
        String template = """
                你是一个严谨的 AI 知识架构师，擅长从非结构化文本中提取层级知识结构。

                # 任务
                分析提供的文档片段，生成一份 Markdown 格式的思维导图。
                  #  = 中心主题（概括文档的核心主题，1个）
                  ## = 主要分支（一级关键概念/章节，3-6个）
                  ### = 子细节（支撑要点、定义、原理，每个分支2-4个）
                  列表项 - = 补充细节（可选，用于需要进一步展开的概念）

                # 格式约束 (严格执行)
                1. **仅输出 Markdown 内容，严禁包含任何多余的解释文字**
                2. 严禁输出"好的"、"以下是"、"这是一份"等引导语或结束语
                3. 严禁编造文档中不存在的内容，必须以文档为依据
                4. 每个 ### 和 - 节点必须是完整的短语或句子，不可仅为单个词
                5. 层级之间不要有空行，保持紧凑的树状结构

                # 文档上下文
                {CONTEXT}

                基于以上文档内容，生成一份结构清晰的 Markdown 思维导图。
                仅输出 Markdown 内容，严禁包含任何多余的解释文字。""";

        SystemMessage system = new SystemMessage(template.replace("{CONTEXT}", context));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成一份结构清晰的 Markdown 思维导图。仅输出 Markdown 内容。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
    }

    /** 拼装上下文 — 标注片段来源以实现数据溯源 */
    private String buildContextWithPages(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            TextSegment seg = matches.get(i).embedded();
            sb.append("【片段").append(i + 1).append("】");
            String page = seg.metadata().getString("page_number");
            if (page != null && !page.isBlank()) {
                sb.append("(页码:").append(page).append(")");
            }
            sb.append("\n").append(seg.text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 清理 LLM 输出: 去除引导语, 确保以 # 开头 */
    private String cleanMarkdown(String raw) {
        String trimmed = raw.trim();
        // 定位第一个 # 标题
        int hashIdx = trimmed.indexOf("# ");
        if (hashIdx == -1) hashIdx = trimmed.indexOf("#");
        if (hashIdx > 0) {
            // 去除 # 之前的引导文字
            trimmed = trimmed.substring(hashIdx);
        }
        return trimmed;
    }

    /** 验证: 必须至少包含一个 # 标题 */
    private boolean isValidMindMap(String markdown) {
        return markdown != null && !markdown.isBlank() && markdown.contains("# ");
    }

    // ===== 结果封装 =====

    public static class MindMapResult {
        private final boolean success;
        private final Long docId;
        private final Long deckId;
        private final String title;
        private final String content;
        private final String createdAt;

        private MindMapResult(boolean s, Long d, Long dk, String t, String c, String ca) {
            this.success = s; this.docId = d; this.deckId = dk; this.title = t; this.content = c; this.createdAt = ca;
        }

        public static MindMapResult success(Long docId, Long deckId, String title, String content, String createdAt) {
            return new MindMapResult(true, docId, deckId, title, content, createdAt);
        }

        public static MindMapResult empty(Long docId) {
            return new MindMapResult(false, docId, null, null, null, null);
        }

        public boolean isSuccess() { return success; }
        public Long getDocId() { return docId; }
        public Long getDeckId() { return deckId; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getCreatedAt() { return createdAt; }
    }
}
