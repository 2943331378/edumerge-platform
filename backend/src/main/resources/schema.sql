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
    file_type VARCHAR(20) COMMENT '文件类型：pdf, doc, docx, txt, md',
    file_path VARCHAR(500) COMMENT '文件存储路径',
    status VARCHAR(20) DEFAULT 'UPLOADING' COMMENT '文档状态：UPLOADING, PROCESSING, COMPLETED, FAILED',
    status_message VARCHAR(500) COMMENT '状态信息',
    chunk_count INT COMMENT '分块数量',
    vector_count INT COMMENT '向量数量',
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

-- ===== 学习卡片表 =====
-- 数据素质: Flashcards 通过 doc_id 关联源文档, source_segment 记录内容出处,
-- 实现学习资源的"可追溯性"与"组织管理", 符合数据要素治理要求
CREATE TABLE IF NOT EXISTS flashcards (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '卡片ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    user_id BIGINT COMMENT '创建用户ID',
    question TEXT NOT NULL COMMENT '问题',
    answer TEXT NOT NULL COMMENT '答案',
    explanation TEXT COMMENT '解析/知识扩展',
    source_segment TEXT COMMENT '内容源自文档的片段引用 (实现数据可追溯)',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, ARCHIVED',
    difficulty INT DEFAULT 0 COMMENT '难度等级: 0=未评估, 1-5',
    review_count INT DEFAULT 0 COMMENT '复习次数',
    last_reviewed_at DATETIME COMMENT '最近复习时间',
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
-- 数据素质: Quizzes 通过 doc_id 关联源文档, source_segment 记录题目出处,
-- options 使用 JSON 格式存储, 实现结构化数据的"组织管理"与"可追溯性"
CREATE TABLE IF NOT EXISTS quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '测试题ID',
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    user_id BIGINT COMMENT '创建用户ID',
    question TEXT NOT NULL COMMENT '问题',
    options JSON COMMENT '选项列表 (JSON格式, 如: ["A.选项1","B.选项2","C.选项3","D.选项4"])',
    answer VARCHAR(500) NOT NULL COMMENT '正确答案',
    explanation TEXT COMMENT '解析/知识扩展',
    source_segment TEXT COMMENT '题目源自文档的片段引用 (实现数据可追溯)',
    quiz_type VARCHAR(20) DEFAULT 'SINGLE' COMMENT '题型: SINGLE=单选, MULTIPLE=多选, JUDGE=判断',
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

-- ===== 初始化示例数据 =====
INSERT IGNORE INTO users (username, email, password, display_name, status)
VALUES ('admin', 'admin@edumerge.com', 'admin123', '管理员', 1);

COMMIT;
