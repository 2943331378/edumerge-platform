package com.edumerge.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.ConceptDocument;
import com.edumerge.entity.ConceptRelationship;
import com.edumerge.entity.Document;
import com.edumerge.entity.KnowledgeConcept;
import com.edumerge.mapper.ConceptDocumentMapper;
import com.edumerge.mapper.ConceptRelationshipMapper;
import com.edumerge.mapper.KnowledgeConceptMapper;
import com.edumerge.service.DocumentService;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.data.segment.TextSegment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiKnowledgeGraphGenerator extends AiGeneratorBase {

    @Autowired
    private ChatModel chatLanguageModel;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeConceptMapper conceptMapper;

    @Autowired
    private ConceptDocumentMapper conceptDocMapper;

    @Autowired
    private ConceptRelationshipMapper relationMapper;

    // DTOs for LLM JSON parsing
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class ConceptDto {
        private String name;
        private String definition;
        private int importance;
        private List<Integer> documents;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class RelationDto {
        private String source;
        private String target;
        private String relationship;
        private String description;
        private double strength;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class GraphDto {
        private List<ConceptDto> concepts;
        private List<RelationDto> relationships;
    }

    @Data @AllArgsConstructor
    public static class KnowledgeGraphResult {
        private boolean success;
        private int conceptCount;
        private int relationshipCount;
        private String message;

        public static KnowledgeGraphResult ok(int concepts, int relations) {
            return new KnowledgeGraphResult(true, concepts, relations, null);
        }
        public static KnowledgeGraphResult empty(String msg) {
            return new KnowledgeGraphResult(false, 0, 0, msg);
        }
    }

    /** 主入口: 为用户的所有文档生成知识图谱 */
    @Transactional
    public KnowledgeGraphResult generate(Long userId) {
        // 1. 获取所有已完成文档
        List<Document> docs = documentService.listByUserId(userId);
        if (docs.isEmpty()) {
            return KnowledgeGraphResult.empty("暂无已完成处理的文档，请先上传并等待处理完成");
        }

        log.info("开始生成知识图谱: userId={}, 文档数={}", userId, docs.size());

        // 2. 为每个文档提取前5个代表片段
        Map<Integer, DocMeta> docMetaMap = new LinkedHashMap<>();
        StringBuilder allContext = new StringBuilder();
        int docIdx = 0;

        for (Document doc : docs) {
            docIdx++;
            // 使用语义查询检索代表片段
            List<EmbeddingMatch<TextSegment>> matches =
                    retrieveTopChunks(doc.getDocumentId(), 5, "核心概念 定义 原理 方法 公式 定理 分类 特征 应用总结");
            if (matches.isEmpty()) continue;

            docMetaMap.put(docIdx, new DocMeta(doc.getId(), doc.getDocumentId(),
                    doc.getFileName() != null ? doc.getFileName() : doc.getTitle()));

            allContext.append("【文档").append(docIdx).append(": ")
                    .append(doc.getFileName() != null ? doc.getFileName() : doc.getTitle())
                    .append("】\n");
            allContext.append(buildContext(matches));
        }

        if (docMetaMap.isEmpty() || allContext.isEmpty()) {
            return KnowledgeGraphResult.empty("未从文档中检索到足够内容，请确认文档已向量化完成");
        }

        // 3. LLM 调用 — 上下文过大时缩减片段
        if (allContext.length() > 80_000) {
            int chunkLimit = docs.size() > 15 ? 2 : 3;
            log.info("上下文过大 ({} 字符), 缩减为每文档{}片段...", allContext.length(), chunkLimit);
            allContext = rebuildContext(docs, docMetaMap, chunkLimit);
        }

        String raw = callLLM(allContext.toString(), docMetaMap.size());
        String json = extractJsonArray(raw);

        // 4. 解析 JSON
        GraphDto graphDto;
        try {
            graphDto = objectMapper.readValue(json, new TypeReference<GraphDto>() {});
        } catch (Exception e) {
            log.warn("知识图谱 JSON 解析失败: {}，原始响应前200字符: {}", e.getMessage(),
                    raw.length() > 200 ? raw.substring(0, 200) : raw);
            return KnowledgeGraphResult.empty("AI 响应格式异常，请重试");
        }

        if (graphDto.getConcepts() == null || graphDto.getConcepts().isEmpty()) {
            return KnowledgeGraphResult.empty("AI 未提取到概念，请尝试添加不同类型的学习资料");
        }

        // 5. 先清除旧图谱 (在 JSON 验证通过之后，防止 LLM 返回畸形数据时丢失旧图谱)
        clearExisting(userId);

        // 概念持久化 + 名称→ID 映射
        Map<String, Long> nameToId = new HashMap<>();
        for (ConceptDto c : graphDto.getConcepts()) {
            KnowledgeConcept kc = KnowledgeConcept.builder()
                    .userId(userId)
                    .name(truncate(c.getName() != null ? c.getName() : "未命名概念", 200))
                    .definition(truncate(c.getDefinition() != null ? c.getDefinition() : "", 2000))
                    .importanceScore((double) Math.max(1, Math.min(10, c.getImportance())))
                    .documentCount(c.getDocuments() != null ? c.getDocuments().size() : 0)
                    .build();
            conceptMapper.insert(kc);
            nameToId.put(c.getName(), kc.getId());

            // 概念→文档关联
            if (c.getDocuments() != null) {
                for (Integer di : c.getDocuments()) {
                    DocMeta meta = docMetaMap.get(di);
                    if (meta == null) continue;
                    ConceptDocument cd = ConceptDocument.builder()
                            .conceptId(kc.getId())
                            .docId(meta.docId)
                            .docUuid(meta.docUuid)
                            .relevanceScore(1.0)
                            .build();
                    conceptDocMapper.insert(cd);
                }
            }
        }

        // 关系持久化
        int relCount = 0;
        if (graphDto.getRelationships() != null) {
            for (RelationDto r : graphDto.getRelationships()) {
                Long idA = nameToId.get(r.getSource());
                Long idB = nameToId.get(r.getTarget());
                if (idA == null || idB == null) continue;
                ConceptRelationship cr = ConceptRelationship.builder()
                        .conceptIdA(idA)
                        .conceptIdB(idB)
                        .relationshipType(truncate(r.getRelationship() != null ? r.getRelationship() : "RELATES_TO", 50))
                        .description(truncate(r.getDescription() != null ? r.getDescription() : "", 500))
                        .strength(r.getStrength() > 0 ? r.getStrength() : 0.5)
                        .build();
                relationMapper.insert(cr);
                relCount++;
            }
        }

        log.info("知识图谱生成完成: concepts={}, relationships={}", nameToId.size(), relCount);
        return KnowledgeGraphResult.ok(nameToId.size(), relCount);
    }

    private StringBuilder rebuildContext(List<Document> docs, Map<Integer, DocMeta> metaMap, int chunkLimit) {
        StringBuilder sb = new StringBuilder();
        int di = 0;
        for (Document doc : docs) {
            di++;
            if (!metaMap.containsKey(di)) continue;
            List<EmbeddingMatch<TextSegment>> matches =
                    retrieveTopChunks(doc.getDocumentId(), chunkLimit, "核心概念 关键知识点 定义 公式");
            if (matches.isEmpty()) continue;
            sb.append("【文档").append(di).append(": ")
                    .append(doc.getFileName() != null ? doc.getFileName() : doc.getTitle())
                    .append("】\n");
            sb.append(buildContext(matches));
        }
        return sb;
    }

    private String callLLM(String context, int docCount) {
        // SystemMessage: 仅静态指令（prefix cache 友好）
        String systemTemplate = """
                你是一个知识图谱构建专家，擅长从多篇学习文档中分析知识概念体系。

                # 任务
                分析以下文档片段，提取跨文档知识概念网络。

                ## 第一步：提取核心概念
                - "name": 概念名称（不超过20字）
                - "definition": 概念定义（100-200字，基于文档内容）
                - "importance": 重要程度（1-10整数）
                  * 高(8-10): 多篇文档出现、是核心主题
                  * 中(5-7): 有详细定义和解释
                  * 低(1-4): 辅助性概念
                - "documents": 出现的文档编号列表，如 [1,3,5]

                ## 第二步：识别概念关系
                - "source"/"target": 概念名称（必须是第一步已提取的）
                - "relationship": IS_A(是一种) | PART_OF(组成部分) | RELATES_TO(密切相关) | PREREQUISITE(前置知识) | APPLIES_TO(应用于)
                - "description": 关系描述（不超过100字）
                - "strength": 关系强度 0.3(弱) | 0.5(中) | 0.8(强)

                # 输出要求
                - 仅输出纯JSON: {"concepts":[...], "relationships":[...]}
                - 至少5个概念，最多30个概念，每个至少引用1个文档编号
                - 使用简体中文，严格基于文档内容""";
        SystemMessage system = new SystemMessage(systemTemplate);

        // UserMessage: 动态内容（文档上下文）
        String userText = "# 文档内容（共" + docCount + "篇）\n" + context + "\n请基于以上文档内容，提取跨文档知识概念网络。仅输出JSON。";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage(userText));

        ChatResponse response = AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(messages));
        return response.aiMessage().text();
    }

    public void clearExisting(Long userId) {
        List<KnowledgeConcept> existing = conceptMapper.selectList(
                new LambdaQueryWrapper<KnowledgeConcept>()
                        .eq(KnowledgeConcept::getUserId, userId));
        if (existing.isEmpty()) return;
        List<Long> ids = existing.stream().map(KnowledgeConcept::getId).toList();
        conceptDocMapper.delete(new LambdaQueryWrapper<ConceptDocument>()
                .in(ConceptDocument::getConceptId, ids));
        relationMapper.delete(new LambdaQueryWrapper<ConceptRelationship>()
                .in(ConceptRelationship::getConceptIdA, ids)
                .or().in(ConceptRelationship::getConceptIdB, ids));
        conceptMapper.deleteBatchIds(ids);
    }

    private static class DocMeta {
        final Long docId;
        final String docUuid;
        final String displayName;
        DocMeta(Long id, String uuid, String name) {
            this.docId = id; this.docUuid = uuid; this.displayName = name;
        }
    }
}
