package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiKnowledgeGraphGenerator;
import com.edumerge.entity.*;
import com.edumerge.mapper.*;
import com.edumerge.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeGraphService {

    private final KnowledgeConceptMapper conceptMapper;
    private final ConceptDocumentMapper conceptDocMapper;
    private final ConceptRelationshipMapper relationMapper;
    private final SessionMapper sessionMapper;
    private final DocumentMapper documentMapper;
    private final AiKnowledgeGraphGenerator generator;

    public KnowledgeGraphService(KnowledgeConceptMapper conceptMapper,
                                  ConceptDocumentMapper conceptDocMapper,
                                  ConceptRelationshipMapper relationMapper,
                                  SessionMapper sessionMapper,
                                  DocumentMapper documentMapper,
                                  AiKnowledgeGraphGenerator generator) {
        this.conceptMapper = conceptMapper;
        this.conceptDocMapper = conceptDocMapper;
        this.relationMapper = relationMapper;
        this.sessionMapper = sessionMapper;
        this.documentMapper = documentMapper;
        this.generator = generator;
    }

    /** 触发 AI 生成 */
    @Transactional
    public AiKnowledgeGraphGenerator.KnowledgeGraphResult generate(Long userId) {
        return generator.generate(userId);
    }

    /** 获取完整图谱数据 */
    @Transactional(readOnly = true)
    public Map<String, Object> getGraph(Long userId) {
        List<KnowledgeConcept> concepts = conceptMapper.selectList(
                new LambdaQueryWrapper<KnowledgeConcept>()
                        .eq(KnowledgeConcept::getUserId, userId));
        if (concepts.isEmpty()) return null;

        List<Long> conceptIds = concepts.stream().map(KnowledgeConcept::getId).toList();
        List<ConceptRelationship> relations = relationMapper.selectList(
                new LambdaQueryWrapper<ConceptRelationship>()
                        .in(ConceptRelationship::getConceptIdA, conceptIds));

        Map<String, Object> result = new LinkedHashMap<>();
        // 预加载所有概念→文档的关联（用于前端文档筛选）
        List<ConceptDocument> allDocs = conceptDocMapper.selectList(
                new LambdaQueryWrapper<ConceptDocument>().in(ConceptDocument::getConceptId, conceptIds));
        Map<Long, List<Long>> conceptDocMap = new HashMap<>();
        for (ConceptDocument cd : allDocs) {
            conceptDocMap.computeIfAbsent(cd.getConceptId(), k -> new ArrayList<>()).add(cd.getDocId());
        }

        result.put("concepts", concepts.stream().map(c -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", c.getId());
            node.put("name", c.getName());
            node.put("definition", c.getDefinition());
            node.put("importance", c.getImportanceScore());
            node.put("documentCount", c.getDocumentCount());
            node.put("documentIds", conceptDocMap.getOrDefault(c.getId(), List.of()));
            return node;
        }).toList());

        result.put("relationships", relations.stream().map(r -> {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("id", r.getId());
            link.put("sourceConceptId", r.getConceptIdA());
            link.put("targetConceptId", r.getConceptIdB());
            link.put("relationshipType", r.getRelationshipType());
            link.put("description", r.getDescription());
            link.put("strength", r.getStrength());
            return link;
        }).toList());

        return result;
    }

    /** 获取概念详情 */
    @Transactional(readOnly = true)
    public Map<String, Object> getConceptDetail(Long conceptId) {
        KnowledgeConcept concept = conceptMapper.selectById(conceptId);
        if (concept == null) return null;
        if (!concept.getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此概念");
        }

        // 关联概念（从关系表中查找）
        List<ConceptRelationship> allRels = relationMapper.selectList(
                new LambdaQueryWrapper<ConceptRelationship>()
                        .eq(ConceptRelationship::getConceptIdA, conceptId)
                        .or()
                        .eq(ConceptRelationship::getConceptIdB, conceptId));

        Set<Long> relatedIds = new HashSet<>();
        for (ConceptRelationship r : allRels) {
            if (r.getConceptIdA().equals(conceptId)) relatedIds.add(r.getConceptIdB());
            else relatedIds.add(r.getConceptIdA());
        }

        List<KnowledgeConcept> related = relatedIds.isEmpty() ? List.of()
                : conceptMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeConcept>().in(KnowledgeConcept::getId, relatedIds));

        // 源文档
        List<ConceptDocument> docs = conceptDocMapper.selectList(
                new LambdaQueryWrapper<ConceptDocument>().eq(ConceptDocument::getConceptId, conceptId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("concept", toMap(concept));
        result.put("relationships", allRels.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("sourceId", r.getConceptIdA());
            m.put("targetId", r.getConceptIdB());
            m.put("type", r.getRelationshipType());
            m.put("description", r.getDescription());
            m.put("strength", r.getStrength());
            return m;
        }).toList());
        result.put("relatedConcepts", related.stream().map(this::toMap).toList());
        result.put("sourceDocuments", docs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", d.getDocId());
            m.put("docUuid", d.getDocUuid());
            m.put("mentionText", d.getMentionText());
            return m;
        }).toList());

        return result;
    }

    /** 获取概念在各文档中的来源 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getConceptDocuments(Long conceptId) {
        KnowledgeConcept concept = conceptMapper.selectById(conceptId);
        if (concept == null) return List.of();
        if (!concept.getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此概念");
        }

        List<ConceptDocument> docs = conceptDocMapper.selectList(
                new LambdaQueryWrapper<ConceptDocument>().eq(ConceptDocument::getConceptId, conceptId));

        // 批量查询文档和 session，避免 N+1
        List<Long> docIds = docs.stream().map(ConceptDocument::getDocId).toList();
        Map<Long, Document> docMap = docIds.isEmpty() ? Map.of() :
                documentMapper.selectBatchIds(docIds).stream()
                        .collect(Collectors.toMap(Document::getId, d -> d));
        Map<Long, Session> sessionMap = docIds.isEmpty() ? Map.of() :
                sessionMapper.selectList(new LambdaQueryWrapper<Session>().in(Session::getDocId, docIds))
                        .stream().collect(Collectors.toMap(Session::getDocId, s -> s, (a, b) -> a));

        return docs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", d.getDocId());
            m.put("docUuid", d.getDocUuid());
            m.put("mentionText", d.getMentionText());

            Document doc = docMap.get(d.getDocId());
            if (doc != null) {
                m.put("docTitle", doc.getFileName() != null ? doc.getFileName() : doc.getTitle());
                m.put("fileName", doc.getFileName());
            }

            Session session = sessionMap.get(d.getDocId());
            if (session != null) {
                m.put("sessionId", session.getId());
            }

            return m;
        }).toList();
    }

    private Map<String, Object> toMap(KnowledgeConcept c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("definition", c.getDefinition());
        m.put("importance", c.getImportanceScore());
        m.put("documentCount", c.getDocumentCount());
        return m;
    }
}
