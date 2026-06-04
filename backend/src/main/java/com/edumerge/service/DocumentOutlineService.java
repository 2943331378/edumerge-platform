package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.DocumentOutline;
import com.edumerge.mapper.DocumentOutlineMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档大纲 CRUD 服务 — 业务逻辑层
 *
 * 职责:
 * - 大纲的创建、查询、更新、删除
 * - 版本管理 (用户编辑后 version++)
 * - AI 生成逻辑委托给 ai.AiOutlineGenerator
 */
@Slf4j
@Service
public class DocumentOutlineService {

    private final DocumentOutlineMapper outlineMapper;

    @Autowired
    public DocumentOutlineService(DocumentOutlineMapper outlineMapper) {
        this.outlineMapper = outlineMapper;
    }

    /**
     * 按文档 ID 查询最新版本的大纲
     */
    @Transactional(readOnly = true)
    public DocumentOutline getByDocId(Long docId) {
        return outlineMapper.selectOne(
                new LambdaQueryWrapper<DocumentOutline>()
                        .eq(DocumentOutline::getDocId, docId)
                        .orderByDesc(DocumentOutline::getVersion)
                        .last("LIMIT 1"));
    }

    /** 创建大纲记录 */
    @Transactional
    public DocumentOutline create(DocumentOutline outline) {
        outlineMapper.insert(outline);
        log.info("文档大纲已创建: id={}, docId={}, docType={}", outline.getId(), outline.getDocId(), outline.getDocType());
        return outline;
    }

    /** 更新大纲内容 (用户编辑后调用, version 自增) */
    @Transactional
    public DocumentOutline update(Long docId, String outlineJson, Long userId) {
        DocumentOutline existing = getByDocId(docId);
        if (existing == null) {
            throw new IllegalArgumentException("文档大纲不存在: docId=" + docId);
        }
        existing.setOutlineJson(outlineJson);
        existing.setVersion((existing.getVersion() != null ? existing.getVersion() : 0) + 1);
        outlineMapper.updateById(existing);
        log.info("文档大纲已更新: docId={}, newVersion={}", docId, existing.getVersion());
        return existing;
    }

    /** 删除文档的大纲 (级联删除文档时调用) */
    @Transactional
    public void deleteByDocId(Long docId) {
        int rows = outlineMapper.delete(
                new LambdaQueryWrapper<DocumentOutline>()
                        .eq(DocumentOutline::getDocId, docId));
        if (rows > 0) log.info("文档大纲已删除: docId={}, rows={}", docId, rows);
    }

    /** 删除指定版本以外的所有旧版本大纲 (重新生成后清理) */
    @Transactional
    public void deleteOldVersions(Long docId, int keepVersion) {
        int rows = outlineMapper.delete(
                new LambdaQueryWrapper<DocumentOutline>()
                        .eq(DocumentOutline::getDocId, docId)
                        .ne(DocumentOutline::getVersion, keepVersion));
        if (rows > 0) log.info("旧版本大纲已清理: docId={}, keepVersion={}, deleted={}", docId, keepVersion, rows);
    }

    /** 判断文档是否已有大纲 */
    @Transactional(readOnly = true)
    public boolean existsByDocId(Long docId) {
        return outlineMapper.selectCount(
                new LambdaQueryWrapper<DocumentOutline>()
                        .eq(DocumentOutline::getDocId, docId)) > 0;
    }
}
