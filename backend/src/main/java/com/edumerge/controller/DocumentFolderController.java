package com.edumerge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentFolder;
import com.edumerge.mapper.DocumentFolderMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档文件夹管理接口 — 组织文档的文件夹/集合系统
 */
@Slf4j
@RestController
@RequestMapping("/folders")
public class DocumentFolderController {

    private final DocumentFolderMapper folderMapper;
    private final DocumentMapper documentMapper;

    @Autowired
    public DocumentFolderController(DocumentFolderMapper folderMapper, DocumentMapper documentMapper) {
        this.folderMapper = folderMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 查询当前用户的所有文件夹（含文档计数）
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<DocumentFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<DocumentFolder>()
                        .eq(DocumentFolder::getUserId, userId)
                        .orderByAsc(DocumentFolder::getSortOrder)
                        .orderByAsc(DocumentFolder::getCreatedAt));

        List<Map<String, Object>> result = new ArrayList<>();
        for (DocumentFolder f : folders) {
            Long docCount = documentMapper.selectCount(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getFolderId, f.getId())
                            .eq(Document::getUserId, userId));
            Map<String, Object> item = new HashMap<>();
            item.put("id", f.getId());
            item.put("name", f.getName());
            item.put("color", f.getColor());
            item.put("parentId", f.getParentId());
            item.put("sortOrder", f.getSortOrder());
            item.put("docCount", docCount);
            item.put("createdAt", f.getCreatedAt());
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 创建文件夹
     */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return Result.fail("文件夹名称不能为空");
        }
        String color = body.get("color") != null ? (String) body.get("color") : "#6366f1";
        Long parentId = body.get("parentId") != null ? Long.valueOf(body.get("parentId").toString()) : null;
        Integer sortOrder = body.get("sortOrder") != null ? Integer.valueOf(body.get("sortOrder").toString()) : 0;

        DocumentFolder folder = DocumentFolder.builder()
                .userId(userId)
                .name(name.trim())
                .color(color)
                .parentId(parentId)
                .sortOrder(sortOrder)
                .build();
        folderMapper.insert(folder);

        Map<String, Object> result = new HashMap<>();
        result.put("id", folder.getId());
        result.put("name", folder.getName());
        result.put("color", folder.getColor());
        result.put("parentId", folder.getParentId());
        result.put("sortOrder", folder.getSortOrder());
        result.put("docCount", 0);
        result.put("createdAt", folder.getCreatedAt());
        return Result.success("文件夹已创建", result);
    }

    /**
     * 更新文件夹（名称、颜色、排序）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        DocumentFolder folder = folderMapper.selectById(id);
        if (folder == null || !folder.getUserId().equals(userId)) {
            return Result.fail("文件夹不存在");
        }

        DocumentFolder update = new DocumentFolder();
        update.setId(id);
        if (body.containsKey("name")) {
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) return Result.fail("名称不能为空");
            update.setName(name.trim());
        }
        if (body.containsKey("color")) update.setColor((String) body.get("color"));
        if (body.containsKey("sortOrder")) update.setSortOrder(Integer.valueOf(body.get("sortOrder").toString()));
        folderMapper.updateById(update);
        return Result.success("已更新", null);
    }

    /**
     * 删除文件夹（文件夹内的文档回到根目录）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        DocumentFolder folder = folderMapper.selectById(id);
        if (folder == null || !folder.getUserId().equals(userId)) {
            return Result.fail("文件夹不存在");
        }

        // 将文件夹内的文档移回根目录
        Document docUpdate = new Document();
        docUpdate.setFolderId(null);
        documentMapper.update(docUpdate,
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getFolderId, id)
                        .eq(Document::getUserId, userId));

        folderMapper.deleteById(id);
        log.info("文件夹已删除: id={}, name={}", id, folder.getName());
        return Result.success("文件夹已删除", null);
    }

    /**
     * 移动文档到文件夹
     */
    @PutMapping("/documents/{docId}/move")
    public Result<Void> moveDocument(@PathVariable Long docId, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getUserId().equals(userId)) {
            return Result.fail("文档不存在");
        }

        Long folderId = body.get("folderId") != null ? Long.valueOf(body.get("folderId").toString()) : null;

        // 验证目标文件夹存在且属于当前用户
        if (folderId != null) {
            DocumentFolder folder = folderMapper.selectById(folderId);
            if (folder == null || !folder.getUserId().equals(userId)) {
                return Result.fail("目标文件夹不存在");
            }
        }

        Document update = new Document();
        update.setId(docId);
        update.setFolderId(folderId);
        documentMapper.updateById(update);
        return Result.success("已移动", null);
    }
}
