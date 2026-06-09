-- ===== EduMerge 数据库初始化脚本 =====
-- 合并所有增量变更，新设备首次执行即可获得完整表结构
-- Spring Boot 通过 sql.init.continue-on-error=true 保证重复执行安全

-- ===== 创建数据库 =====
CREATE DATABASE IF NOT EXISTS edumerge CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE edumerge;

-- ===== 用户表 =====
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    password VARCHAR(255) NOT NULL COMMENT '密码（加密后）',
    display_name VARCHAR(100) COMMENT '显示名称',
    status INT DEFAULT 1 COMMENT '用户状态：0=禁用，1=启用',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0=未删除，1=已删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ===== 文档表 =====
CREATE TABLE IF NOT EXISTS documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文档ID',
    document_id VARCHAR(64) COMMENT 'Milvus 文档UUID (向量检索标识)',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    title VARCHAR(200) NOT NULL COMMENT '文档标题',
    description TEXT COMMENT '文档描述',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_size BIGINT COMMENT '文件大小（字节）',
    file_type VARCHAR(20) COMMENT '文件类型：pdf, doc, docx, ppt, pptx, txt, md, html, xlsx, csv',
    file_path VARCHAR(500) COMMENT '文件存储路径',
    status VARCHAR(20) DEFAULT 'UPLOADING' COMMENT '文档状态：UPLOADING, PROCESSING, COMPLETED, FAILED',
    status_message VARCHAR(500) COMMENT '状态信息',
    chunk_count INT COMMENT '分块数量',
    vector_count INT COMMENT '向量数量',
    page_count INT COMMENT '文档页数/幻灯片数/工作表数',
    subject_type VARCHAR(20) DEFAULT 'GENERAL' COMMENT '学科类型: ALGORITHM/MATH/PROGRAMMING/SCIENCE/THEORY/MEDICAL/HUMANITIES/GENERAL',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_document_id (document_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- ===== 文档分块表 =====
CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分块ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL COMMENT '分块索引',
    content LONGTEXT NOT NULL COMMENT '分块内容',
    token_count INT COMMENT '令牌数量',
    embedding_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '向量化状态：PENDING, PROCESSING, COMPLETED, FAILED',
    milvus_id BIGINT COMMENT 'Milvus 中的向量ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_document_id (document_id),
    INDEX idx_embedding_status (embedding_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块表';

-- ===== 会话表 =====
CREATE TABLE IF NOT EXISTS sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    title VARCHAR(200) COMMENT '会话标题',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, ARCHIVED',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_doc_id (doc_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- ===== 对话历史表 =====
CREATE TABLE IF NOT EXISTS chat_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '对话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    session_id VARCHAR(100) COMMENT '会话ID',
    query TEXT NOT NULL COMMENT '用户问题',
    response LONGTEXT COMMENT '模型回答',
    retrieved_documents INT COMMENT '检索到的文档数量',
    confidence DECIMAL(5, 4) COMMENT '置信度',
    tokens_used INT COMMENT '使用的令牌数',
    is_helpful INT COMMENT '用户反馈：1=有用，0=无用，null=未反馈',
    feedback_reason VARCHAR(500) COMMENT '反馈原因 (用户自定义)',
    activity_type VARCHAR(20) DEFAULT NULL COMMENT '活动上下文: notes/mindmap/flashcards/quiz/flownote',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话历史表';

-- ===== 系统日志表 =====
CREATE TABLE IF NOT EXISTS system_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    user_id BIGINT COMMENT '用户ID',
    action VARCHAR(100) COMMENT '操作类型',
    resource_type VARCHAR(50) COMMENT '资源类型',
    resource_id BIGINT COMMENT '资源ID',
    request_data LONGTEXT COMMENT '请求数据',
    response_data LONGTEXT COMMENT '响应数据',
    status INT COMMENT '状态码',
    error_message VARCHAR(500) COMMENT '错误信息',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '用户代理',
    duration_ms BIGINT COMMENT '执行耗时（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统日志表';

-- ===== 对话会话表 =====
CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    session_id VARCHAR(64) NOT NULL UNIQUE COMMENT '前端对话UUID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_id BIGINT COMMENT '关联文档ID',
    doc_uuid VARCHAR(128) COMMENT 'Milvus 文档UUID (向量检索标识)',
    title VARCHAR(200) DEFAULT '新对话' COMMENT '对话标题',
    exchange_count INT DEFAULT 0 COMMENT '累计对话轮数 (FlowNote 自动提取触发依据)',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';

-- ===== 卡片组表 =====
CREATE TABLE IF NOT EXISTS card_decks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '卡片组ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    title VARCHAR(200) NOT NULL COMMENT '组标题',
    type VARCHAR(20) NOT NULL COMMENT 'FLASHCARD / QUIZ / MIND_MAP / NOTE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_doc_id (doc_id),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='卡片组表';

-- ===== 思维导图表 =====
CREATE TABLE IF NOT EXISTS mind_maps (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '思维导图ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    deck_id BIGINT NOT NULL COMMENT '关联卡片组ID',
    content TEXT NOT NULL COMMENT 'Markdown格式的树状思维导图',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (deck_id) REFERENCES card_decks(id) ON DELETE CASCADE,
    INDEX idx_doc_id (doc_id),
    INDEX idx_deck_id (deck_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='思维导图表';

-- ===== 学习笔记表 =====
CREATE TABLE IF NOT EXISTS study_notes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '学习笔记ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    deck_id BIGINT NOT NULL COMMENT '关联卡片组ID',
    title VARCHAR(200) NOT NULL COMMENT '笔记标题',
    content LONGTEXT NOT NULL COMMENT 'Markdown格式的学习笔记',
    source_summary LONGTEXT COMMENT '参考片段摘要',
    requirements VARCHAR(500) COMMENT '用户自定义生成要求',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (deck_id) REFERENCES card_decks(id) ON DELETE CASCADE,
    INDEX idx_doc_id (doc_id),
    INDEX idx_deck_id (deck_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习笔记表';

-- ===== 学习卡片表 =====
CREATE TABLE IF NOT EXISTS flashcards (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '卡片ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    deck_id BIGINT COMMENT '关联卡片组ID',
    user_id BIGINT COMMENT '创建用户ID',
    question TEXT NOT NULL COMMENT '问题',
    answer TEXT NOT NULL COMMENT '答案',
    explanation TEXT COMMENT '解析/知识扩展',
    source_segment TEXT COMMENT '内容源自文档的片段引用 (实现数据可追溯)',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, ARCHIVED',
    difficulty INT DEFAULT 0 COMMENT '难度等级: 0=未评估, 1-5',
    review_count INT DEFAULT 0 COMMENT '复习次数',
    last_reviewed_at DATETIME COMMENT '最近复习时间',
    ease_factor DOUBLE DEFAULT 2.5 COMMENT 'SM-2 简易因子 (最低1.3, 默认2.5)',
    review_interval INT DEFAULT 0 COMMENT '当前复习间隔(天), 0=新卡片未复习',
    next_review_at DATETIME DEFAULT NULL COMMENT '下次复习时间 (NULL=新卡片或已归档)',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_doc_id (doc_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习卡片表';

-- ===== 测试题表 =====
CREATE TABLE IF NOT EXISTS quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '测试题ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    deck_id BIGINT COMMENT '关联卡片组ID',
    user_id BIGINT COMMENT '创建用户ID',
    question TEXT NOT NULL COMMENT '问题',
    options JSON COMMENT '选项列表 (JSON格式, 如: ["A.选项1","B.选项2","C.选项3","D.选项4"])',
    answer VARCHAR(500) NOT NULL COMMENT '正确答案',
    explanation TEXT COMMENT '解析/知识扩展',
    source_segment TEXT COMMENT '题目源自文档的片段引用 (实现数据可追溯)',
    quiz_type VARCHAR(20) DEFAULT 'SINGLE' COMMENT '题型: SINGLE=单选, FILL_BLANK=填空',
    difficulty INT DEFAULT 0 COMMENT '难度等级: 0=未评估, 1-5',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, ARCHIVED',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_doc_id (doc_id),
    INDEX idx_user_id (user_id),
    INDEX idx_quiz_type (quiz_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试题表';

-- ===== 测验答题记录表 =====
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    deck_id BIGINT NOT NULL COMMENT '关联卡片组ID',
    total_questions INT NOT NULL COMMENT '总题数',
    correct_count INT NOT NULL COMMENT '正确数',
    score_percent DOUBLE COMMENT '正确率 (%)',
    answer_details JSON COMMENT '答题详情 [{quizId, selectedAnswer, correct}]',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_doc_id (doc_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测验答题记录表';

-- ===== FlowNote 持续学习日志表 =====
CREATE TABLE IF NOT EXISTS flow_notes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '条目ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    session_id VARCHAR(64) COMMENT '关联对话会话ID',
    category VARCHAR(20) NOT NULL COMMENT '分类: KEY_POINT | QUESTION | EXAMPLE | REVIEW',
    title VARCHAR(300) NOT NULL COMMENT '条目标题',
    content LONGTEXT NOT NULL COMMENT 'Markdown 正文',
    source_segment LONGTEXT COMMENT '源文档片段引用',
    source_type VARCHAR(20) NOT NULL DEFAULT 'AI_GENERATED' COMMENT '来源: AI_GENERATED | USER_WRITTEN | CHAT_EXTRACTED',
    chat_history_id BIGINT COMMENT '关联原始对话记录ID',
    is_reviewed TINYINT DEFAULT 0 COMMENT '是否已复习: 0=未复习 1=已复习',
    reviewed_at DATETIME COMMENT '最近复习时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_doc_id (doc_id),
    INDEX idx_session_id (session_id),
    INDEX idx_category (category),
    INDEX idx_is_reviewed (is_reviewed),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='FlowNote 学习日志表';

-- ===== 闪卡复习记录表 (SM-2) =====
CREATE TABLE IF NOT EXISTS flashcard_review_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    flashcard_id BIGINT NOT NULL COMMENT '卡片ID',
    quality TINYINT NOT NULL COMMENT '自评分数: 1=忘了 2=模糊 3=记住 4=秒答',
    ease_factor DOUBLE NOT NULL COMMENT '本次复习后的简易因子',
    review_interval INT NOT NULL COMMENT '本次复习后的间隔(天)',
    next_review_at DATETIME NOT NULL COMMENT '下次复习时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '复习时间',
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (flashcard_id) REFERENCES flashcards(id) ON DELETE CASCADE,
    INDEX idx_frl_user_id (user_id),
    INDEX idx_frl_flashcard_id (flashcard_id),
    INDEX idx_frl_next_review (next_review_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='闪卡复习记录表(SM-2)';

-- ===== 知识概念表 =====
CREATE TABLE IF NOT EXISTS knowledge_concepts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '概念ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    name VARCHAR(200) NOT NULL COMMENT '概念名称',
    definition TEXT COMMENT '概念定义',
    importance_score DOUBLE DEFAULT 0.0 COMMENT '重要程度 1-10',
    document_count INT DEFAULT 0 COMMENT '涉及的文档数',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_kg_user_id (user_id),
    INDEX idx_kg_importance (importance_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识概念表';

-- ===== 概念-文档关联表 =====
CREATE TABLE IF NOT EXISTS concept_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '关联ID',
    concept_id BIGINT NOT NULL COMMENT '概念ID',
    doc_id BIGINT NOT NULL COMMENT '文档ID',
    doc_uuid VARCHAR(64) COMMENT 'Milvus文档UUID',
    chunk_index INT COMMENT '具体切片索引',
    mention_text TEXT COMMENT '提及该概念的原文片段',
    relevance_score DOUBLE DEFAULT 1.0 COMMENT '概念在该文档中的相关度',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (concept_id) REFERENCES knowledge_concepts(id) ON DELETE CASCADE,
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_cd_concept_id (concept_id),
    INDEX idx_cd_doc_id (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='概念-文档关联表';

-- ===== 概念关系表 =====
CREATE TABLE IF NOT EXISTS concept_relationships (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '关系ID',
    concept_id_a BIGINT NOT NULL COMMENT '源概念ID',
    concept_id_b BIGINT NOT NULL COMMENT '目标概念ID',
    relationship_type VARCHAR(50) NOT NULL COMMENT '关系类型: IS_A/PART_OF/RELATES_TO/PREREQUISITE/CONTRADICTS/APPLIES_TO',
    description TEXT COMMENT '关系描述',
    strength DOUBLE DEFAULT 1.0 COMMENT '关系强度 0.0-1.0',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (concept_id_a) REFERENCES knowledge_concepts(id) ON DELETE CASCADE,
    FOREIGN KEY (concept_id_b) REFERENCES knowledge_concepts(id) ON DELETE CASCADE,
    INDEX idx_cr_concept_a (concept_id_a),
    INDEX idx_cr_concept_b (concept_id_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='概念关系表';

-- ===== 文档大纲表 =====
CREATE TABLE IF NOT EXISTS document_outlines (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '大纲ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_type VARCHAR(30) NOT NULL COMMENT '文档类型: TEXTBOOK/PAPER/NOTE/SLIDE/MANUAL/OTHER',
    doc_type_label VARCHAR(50) COMMENT '文档类型中文标签',
    outline_json LONGTEXT NOT NULL COMMENT '大纲JSON(树状结构含chunk范围映射)',
    version INT DEFAULT 1 COMMENT '版本号(用户编辑后递增)',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_do_doc_id (doc_id),
    INDEX idx_do_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档大纲表';

-- ===== 文档文件夹表 =====
CREATE TABLE IF NOT EXISTS document_folders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文件夹ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    name VARCHAR(100) NOT NULL COMMENT '文件夹名称',
    color VARCHAR(20) DEFAULT '#6366f1' COMMENT '颜色标签 (hex)',
    parent_id BIGINT DEFAULT NULL COMMENT '父文件夹ID (NULL=顶级)',
    sort_order INT DEFAULT 0 COMMENT '排序权重',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_df_user_id (user_id),
    INDEX idx_df_parent_id (parent_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES document_folders(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档文件夹表';

-- ===== 增量变更：documents 表新增 folder_id 列 =====
ALTER TABLE documents ADD COLUMN folder_id BIGINT DEFAULT NULL COMMENT '所属文件夹ID';
ALTER TABLE documents ADD FOREIGN KEY (folder_id) REFERENCES document_folders(id) ON DELETE SET NULL;

-- ===== 增量变更：documents 表新增 subject_type 列 =====
-- 已有数据库执行此语句安全（IF NOT EXISTS 不支持，用 IGNORE 跳过重复列错误）
ALTER TABLE documents ADD COLUMN subject_type VARCHAR(20) DEFAULT 'GENERAL' COMMENT '学科类型: ALGORITHM/MATH/PROGRAMMING/SCIENCE/THEORY/MEDICAL/HUMANITIES/GENERAL';

-- ===== 增量变更：flashcards 表新增 is_important 列 =====
ALTER TABLE flashcards ADD COLUMN is_important TINYINT DEFAULT 0 COMMENT '是否重要标记: 0=普通 1=重要';

-- ===== 初始化示例数据 =====
-- 默认管理员账号: admin / admin123
INSERT IGNORE INTO users (username, email, password, display_name, status)
VALUES ('admin', 'admin@edumerge.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '管理员', 1);

-- ===== 用户已掌握错题表 =====
CREATE TABLE IF NOT EXISTS user_mastered_quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    quiz_id BIGINT NOT NULL COMMENT '测试题ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '掌握时间',
    UNIQUE KEY uk_user_quiz (user_id, quiz_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    INDEX idx_umq_user_id (user_id),
    INDEX idx_umq_quiz_id (quiz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户已掌握错题表';

COMMIT;
