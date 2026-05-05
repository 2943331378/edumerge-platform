package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.MindMap;
import com.edumerge.mapper.MindMapMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MindMapService {

    private final MindMapMapper mindMapMapper;

    @Autowired
    public MindMapService(MindMapMapper mindMapMapper) {
        this.mindMapMapper = mindMapMapper;
    }

    /** 按文档 ID 查询最新一条思维导图 */
    public MindMap getByDocId(Long docId) {
        return mindMapMapper.selectOne(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDocId, docId)
                        .orderByDesc(MindMap::getCreatedAt)
                        .last("LIMIT 1"));
    }

    /** 创建思维导图记录 */
    public MindMap create(Long docId, Long deckId, String content) {
        MindMap entity = MindMap.builder()
                .docId(docId).deckId(deckId).content(content).build();
        mindMapMapper.insert(entity);
        log.info("思维导图已创建: id={}, docId={}, deckId={}, contentLen={}",
                entity.getId(), docId, deckId, content.length());
        return entity;
    }

    /** 删除文档关联的旧思维导图 (覆盖前清理) */
    public void deleteByDocId(Long docId) {
        mindMapMapper.delete(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDocId, docId));
        log.info("旧思维导图已清理: docId={}", docId);
    }
}
