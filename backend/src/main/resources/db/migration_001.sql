-- ============================================================
-- EduMerge 增量迁移: card_decks / mind_maps 补列 + flashcards 补索引
-- 执行方式: mysql -u root -p edumerge < migration_001.sql
-- 适用场景: 已有数据库升级（新部署直接用 schema.sql 即可）
-- ============================================================

-- card_decks 补 deleted + updated_at
ALTER TABLE card_decks
    ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除' AFTER type,
    ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER created_at;

-- mind_maps 补 deleted + updated_at
ALTER TABLE mind_maps
    ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除' AFTER content,
    ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER created_at;

-- flashcards 补 deck_id 索引
ALTER TABLE flashcards
    ADD INDEX idx_deck_id (deck_id);
