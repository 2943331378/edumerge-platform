package com.edumerge.ai;

import com.edumerge.entity.CardDeck;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.StudyNoteService;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class AiNoteGenerator extends AiGeneratorBase {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private CardDeckService cardDeckService;

    @Autowired
    private StudyNoteService studyNoteService;

    public StudyNoteResult generate(Long docId, Long userId, String docUuid) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 25,
                "学习笔记 摘要 总结 章节要点 核心概念 关键知识点 方法 原理 结论 复习重点 study notes summary chapter highlights key concepts main ideas methods principles conclusions review points");
        if (matches.isEmpty()) {
            log.warn("未检索到文档块: docId={}, docUuid={}", docId, docUuid);
            return StudyNoteResult.empty(docId);
        }

        String context = buildContext(matches);
        String content = cleanMarkdown(callLLM(context));
        if (content.isBlank()) {
            return StudyNoteResult.empty(docId);
        }

        studyNoteService.deleteByDocId(docId);
        CardDeck deck = cardDeckService.create(docId, "NOTE");
        String title = LocalDateTime.now().format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) + " 学习笔记";
        String sourceSummary = buildSourceSummary(matches);
        studyNoteService.create(docId, deck.getId(), title, content, sourceSummary);

        return StudyNoteResult.success(docId, deck.getId(), title, content, sourceSummary);
    }

    private String callLLM(String context) {
        String template = """
                你是一个严谨的 AI 学习笔记助手。请基于提供的文档片段，生成一份适合学生复习的 Markdown 学习笔记。

                # 核心要求
                1. 必须以文档上下文为唯一事实依据，严禁编造文档外信息。
                2. 必须使用简体中文输出；如果文档是英文，请基于英文原文翻译、归纳和解释。
                3. 英文关键术语首次出现时保留英文原词，例如"形成性评价（formative assessment）"。
                4. 内容要面向学习者，不要写成论文摘要或产品介绍。
                5. 不要引用片段编号作为正文标题；可以在最后用"参考片段"列出使用过的片段。

                # 输出格式
                仅输出 Markdown，必须包含以下一级标题:
                # 中文学习笔记
                ## 文档概述
                ## 核心知识点
                ## 关键概念解释
                ## 易混淆点与注意事项
                ## 复习清单
                ## 可自测问题
                ## 参考片段

                # 写作约束
                - "文档概述"控制在 120-200 字。
                - "核心知识点"使用 5-8 条项目符号，每条包含简短解释。
                - "关键概念解释"选择 4-6 个最重要概念，每个概念用 2-3 句话说明。
                - "复习清单"使用可勾选 Markdown 任务列表。
                - "可自测问题"给出 5 个问题，不要直接附答案。
                - "参考片段"列出 [片段N]，只列真实使用到的片段编号。

                # 文档上下文
                {CONTEXT}
                """;

        List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(template.replace("{CONTEXT}", context)));
        messages.add(new UserMessage("请基于以上文档内容生成一份结构化中文学习笔记。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
    }

    private String cleanMarkdown(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int heading = trimmed.indexOf("# ");
        return heading > 0 ? trimmed.substring(heading).trim() : trimmed;
    }

    private String buildSourceSummary(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            TextSegment segment = matches.get(i).embedded();
            sb.append("[片段").append(i + 1).append("] ");
            String text = segment.text().replaceAll("\\s+", " ");
            sb.append(truncate(text, 180)).append("\n");
        }
        return sb.toString().trim();
    }

    public static class StudyNoteResult {
        private final boolean success;
        private final Long docId;
        private final Long deckId;
        private final String title;
        private final String content;
        private final String sourceSummary;

        private StudyNoteResult(boolean success, Long docId, Long deckId,
                                String title, String content, String sourceSummary) {
            this.success = success;
            this.docId = docId;
            this.deckId = deckId;
            this.title = title;
            this.content = content;
            this.sourceSummary = sourceSummary;
        }

        public static StudyNoteResult success(Long docId, Long deckId, String title,
                                              String content, String sourceSummary) {
            return new StudyNoteResult(true, docId, deckId, title, content, sourceSummary);
        }

        public static StudyNoteResult empty(Long docId) {
            return new StudyNoteResult(false, docId, null, null, null, null);
        }

        public boolean isSuccess() { return success; }
        public Long getDocId() { return docId; }
        public Long getDeckId() { return deckId; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getSourceSummary() { return sourceSummary; }
    }
}
