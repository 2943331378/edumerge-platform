package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.DocumentChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 批量插入文档切块 — 单条 SQL，比逐条 insert 快 10-50 倍
     * MySQL 默认 max_allowed_packet=4MB，每批最多 100 条避免超限
     */
    @Insert("<script>" +
            "INSERT INTO document_chunks (document_id, chunk_index, content, embedding_status) VALUES " +
            "<foreach collection='chunks' item='c' separator=','>" +
            "(#{c.documentId}, #{c.chunkIndex}, #{c.content}, #{c.embeddingStatus})" +
            "</foreach>" +
            "</script>")
    void insertBatch(@Param("chunks") List<DocumentChunk> chunks);
}
