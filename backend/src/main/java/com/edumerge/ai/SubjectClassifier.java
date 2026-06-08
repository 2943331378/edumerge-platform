package com.edumerge.ai;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档学科分类器 — 在文档上传处理时自动判断学科类型，持久化到 documents.subject_type
 */
@Slf4j
@Service
public class SubjectClassifier {

    @Autowired
    private ChatModel chatLanguageModel;

    public static final String GENERAL = "GENERAL";

    private static final String CLASSIFICATION_PROMPT = """
            你是一个文档学科分类器。根据文档内容片段，判断该文档属于哪个学科方向。

            # 分类选项（仅输出以下标识符之一，不要输出其他内容）
            ALGORITHM    — 算法设计与分析、数据结构、计算理论
            MATH         — 高等数学、线代、概率统计、离散数学
            PROGRAMMING  — 程序设计语言（Java/Python/C/C++/C#等）、软件工程、设计模式
            SCIENCE      — 物理、化学、生物、自然科学
            THEORY       — 操作系统、计算机网络、数据库原理、编译原理、计算机组成
            MEDICAL      — 医学、药学、护理、临床、解剖、病理、生理
            HUMANITIES   — 管理学、经济学、法学、教育学、心理学、社会学、文学、历史
            GENERAL      — 无法归类或不属于以上任何类别

            # 判断依据
            - 优先看标题和文件名中的关键词
            - 其次看内容中的专业术语和知识体系
            - 如果内容是混合型，选择占比最大的学科

            # 输出
            仅输出分类标识符（如 ALGORITHM），不要输出任何解释。
            """;

    /**
     * 根据文档内容判断学科类型
     * @param text 文档提取的前 2000 字文本
     * @return 学科标识符，分类失败时返回 GENERAL
     */
    public String classify(String text) {
        if (text == null || text.isBlank()) return GENERAL;

        String sample = text.length() > 2000 ? text.substring(0, 2000) : text;
        try {
            ChatResponse response = AiGeneratorBase.AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(
                    new SystemMessage(CLASSIFICATION_PROMPT),
                    new UserMessage("以下是文档内容片段：\n\n" + sample)
            ));
            String result = response.aiMessage().text().trim().toUpperCase()
                    .replaceAll("[^A-Z_]", "");

            return switch (result) {
                case "ALGORITHM", "MATH", "PROGRAMMING", "SCIENCE",
                     "THEORY", "MEDICAL", "HUMANITIES" -> result;
                default -> GENERAL;
            };
        } catch (Exception e) {
            log.warn("学科分类失败，回退为 GENERAL: {}", e.getMessage());
            return GENERAL;
        }
    }
}
